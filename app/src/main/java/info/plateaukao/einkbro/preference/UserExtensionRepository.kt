package info.plateaukao.einkbro.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
enum class ExtensionType {
    ACTIVE,
    PASSIVE
}

@Serializable
enum class PassiveMatchType {
    DOMAIN,
    LINK
}

@Serializable
enum class PassiveRunAt {
    EARLY,
    DOM_CONTENT_LOADED
}

@Serializable
data class UserExtensionMeta(
    val id: String,
    val type: ExtensionType,
    val name: String,
    val enabled: Boolean = true,
    val scriptFileName: String,
    val matchType: PassiveMatchType? = null,
    val matchValue: String? = null,
    val runAt: PassiveRunAt? = null,
    val origin: ExtensionOrigin = ExtensionOrigin.USER,
)

@Serializable
enum class ExtensionOrigin {
    BUNDLED,
    USER,
    IMPORTED,
    LEGACY
}

data class UserExtension(
    val meta: UserExtensionMeta,
    val scriptContent: String,
)

@Serializable
data class AppManagedMetadata(
    val extensions: List<ExtensionIndex> = emptyList(),
)

@Serializable
data class ExtensionIndex(
    val id: String,
    val fileName: String,
    val enabled: Boolean,
    val origin: ExtensionOrigin = ExtensionOrigin.USER,
    val matchType: PassiveMatchType? = null,
)

class UserExtensionRepository(
    private val context: Context,
    private val sharedPreferences: SharedPreferences,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val scriptsDir: File by lazy {
        File(context.filesDir, SCRIPT_DIRECTORY).apply { mkdirs() }
    }

    private val appMetadataFile: File by lazy {
        File(context.filesDir, APP_METADATA_FILE)
    }

    fun getExtensions(): List<UserExtensionMeta> {
        migrateLegacyScriptsIfNeeded()
        installBundledExtensionsIfNeeded()

        val appMetadata = readAppMetadata()
        val extensions = readInstalledExtensionFiles(appMetadata)

        if (extensions.isEmpty()) {
            installBundledExtensionsIfNeeded()
            return readInstalledExtensionFiles(readAppMetadata())
        }

        return extensions
    }

    private fun installBundledExtensionsIfNeeded() {
        if (sharedPreferences.getBoolean(KEY_BUNDLED_INSTALLED, false)) return

        val bundledAssets = context.assets.list(ASSET_EXTENSION_DIRECTORY)
            ?.filter { it.endsWith(USER_SCRIPT_SUFFIX) }
            ?.sorted()
            ?.map { "$ASSET_EXTENSION_DIRECTORY/$it" }
            .orEmpty()

        val appMetadata = readAppMetadata()
        val existingIds = appMetadata.extensions.map { it.id }.toMutableSet()
        val newExtensions = mutableListOf<ExtensionIndex>()

        for (assetPath in bundledAssets) {
            val assetContent = try {
                context.assets.open(assetPath).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                continue
            }

            val parsed = UserScriptParser.parse(assetContent)
            val fileName = assetPath.substringAfterLast('/')
            val id = fileName.removeSuffix(".user.js")
            val scriptFile = File(scriptsDir, fileName)

            if (!scriptFile.exists()) {
                scriptFile.writeText(assetContent)
            }

            if (id !in existingIds) {
                val bundledMeta = UserScriptParser.parseToUserExtensionMeta(
                    parsed = parsed,
                    id = id,
                    scriptFileName = fileName,
                )
                newExtensions.add(
                    ExtensionIndex(
                        id = id,
                        fileName = fileName,
                        enabled = bundledMeta.type == ExtensionType.ACTIVE,
                        origin = ExtensionOrigin.BUNDLED,
                        matchType = bundledMeta.matchType,
                    )
                )
            }
        }

        if (newExtensions.isNotEmpty()) {
            writeAppMetadata(AppManagedMetadata(extensions = appMetadata.extensions + newExtensions))
        }
        sharedPreferences.edit { putBoolean(KEY_BUNDLED_INSTALLED, true) }
    }

    private fun readInstalledExtensionFiles(appMetadata: AppManagedMetadata): List<UserExtensionMeta> {
        val metadataByFileName = appMetadata.extensions.associateBy { it.fileName }
        val metadataById = appMetadata.extensions.associateBy { it.id }
        val files = scriptsDir.listFiles { file -> file.extension == "js" || file.extension == "user.js" }
            ?: return emptyList()

        return files.mapNotNull { file ->
            val fallbackId = file.nameWithoutExtension
                .removeSuffix(".user")
                .ifEmpty { file.name }
            val prefixedId = file.name.substringBefore('_', missingDelimiterValue = "")
                .takeIf { it.isNotBlank() && metadataById.containsKey(it) }
            val metadata = metadataByFileName[file.name]
                ?: prefixedId?.let { metadataById[it] }
                ?: metadataById[fallbackId]

            val id = metadata?.id ?: fallbackId
            val enabled = metadata?.enabled ?: true
            val origin = metadata?.origin ?: ExtensionOrigin.USER

            val rawContent = readScript(file.name) ?: return@mapNotNull null
            val parsed = UserScriptParser.parse(rawContent)

            if (parsed.scriptBody.isBlank() && parsed.meta.name.isBlank()) {
                return@mapNotNull null
            }

            UserScriptParser.parseToUserExtensionMeta(
                parsed = parsed,
                id = id,
                scriptFileName = file.name,
                originalMatchType = metadata?.matchType,
                enabled = enabled,
            ).let { it.copy(origin = origin) }
        }
    }

    fun getActiveExtensions(): List<UserExtensionMeta> =
        getExtensions().filter { it.type == ExtensionType.ACTIVE }

    fun getPassiveExtensions(): List<UserExtensionMeta> =
        getExtensions().filter { it.type == ExtensionType.PASSIVE }

    fun getExtension(id: String): UserExtension? {
        val meta = getExtensions().find { it.id == id } ?: return null
        val scriptContent = readScript(meta.scriptFileName) ?: ""
        return UserExtension(meta, scriptContent)
    }

    fun createExtension(type: ExtensionType): UserExtension {
        val id = UUID.randomUUID().toString()
        val fileName = "${id}.user.js"
        val initialContent = if (type == ExtensionType.ACTIVE) {
            """// ==UserScript==
// @name        New Extension
// @namespace   einkbro
// @match       *://*/*
// @version     1.0.0
// ==/UserScript==

// Your script here
"""
        } else {
            """// ==UserScript==
// @name        New Passive Extension
// @namespace   einkbro
// @match       *://*/*
// @run-at      document-end
// @version     1.0.0
// ==/UserScript==

// Your script here
"""
        }

        val meta = UserExtensionMeta(
            id = id,
            type = type,
            name = "",
            enabled = true,
            scriptFileName = fileName,
            matchType = if (type == ExtensionType.PASSIVE) PassiveMatchType.LINK else null,
            matchValue = if (type == ExtensionType.PASSIVE) "*" else null,
            runAt = if (type == ExtensionType.PASSIVE) PassiveRunAt.DOM_CONTENT_LOADED else null,
            origin = ExtensionOrigin.USER,
        )

        writeScript(fileName, initialContent)

        val appMetadata = readAppMetadata()
        writeAppMetadata(
            AppManagedMetadata(
                extensions = appMetadata.extensions + ExtensionIndex(
                    id = id,
                    fileName = fileName,
                    enabled = true,
                    origin = ExtensionOrigin.USER,
                    matchType = meta.matchType,
                )
            )
        )

        return UserExtension(meta, initialContent)
    }

    fun saveExtension(extension: UserExtension) {
        migrateLegacyScriptsIfNeeded()

        val normalizedContent = UserScriptParser.serializeFromExtension(extension)
        writeScript(extension.meta.scriptFileName, normalizedContent)

        val appMetadata = readAppMetadata()
        val existingIndex = appMetadata.extensions.indexOfFirst { it.id == extension.meta.id }
        val newExtensions = if (existingIndex >= 0) {
            appMetadata.extensions.mapIndexed { index, ext ->
                if (index == existingIndex) {
                    ExtensionIndex(
                        id = extension.meta.id,
                        fileName = extension.meta.scriptFileName,
                        enabled = extension.meta.enabled,
                        origin = extension.meta.origin,
                        matchType = extension.meta.matchType,
                    )
                } else ext
            }
        } else {
            appMetadata.extensions + ExtensionIndex(
                id = extension.meta.id,
                fileName = extension.meta.scriptFileName,
                enabled = extension.meta.enabled,
                origin = extension.meta.origin,
                matchType = extension.meta.matchType,
            )
        }
        writeAppMetadata(AppManagedMetadata(extensions = newExtensions))
    }

    fun deleteExtension(id: String) {
        migrateLegacyScriptsIfNeeded()

        val extensions = getExtensions()
        val target = extensions.find { it.id == id } ?: return

        File(scriptsDir, target.scriptFileName).delete()

        val appMetadata = readAppMetadata()
        writeAppMetadata(AppManagedMetadata(extensions = appMetadata.extensions.filter { it.id != id }))
    }

    fun toggleExtension(id: String) {
        migrateLegacyScriptsIfNeeded()

        val appMetadata = readAppMetadata()
        val newExtensions = appMetadata.extensions.map { ext ->
            if (ext.id == id) {
                ext.copy(enabled = !ext.enabled)
            } else ext
        }
        writeAppMetadata(AppManagedMetadata(extensions = newExtensions))
    }

    fun importExtension(rawScript: String): Result<UserExtension> {
        val parsed = UserScriptParser.parse(rawScript)
        if (parsed.meta.name.isBlank()) {
            return Result.failure(IllegalArgumentException("Script must have a @name"))
        }

        val id = UUID.randomUUID().toString()
        val safeName = parsed.meta.name.lowercase().replace(Regex("[^a-z0-9]"), "_")
        val fileName = "${id}_$safeName.user.js"

        val normalizedContent = UserScriptParser.serialize(parsed.meta, parsed.scriptBody)
        writeScript(fileName, normalizedContent)

        val meta = UserScriptParser.parseToUserExtensionMeta(
            parsed = parsed,
            id = id,
            scriptFileName = fileName,
            enabled = true,
        ).copy(origin = ExtensionOrigin.IMPORTED)

        val appMetadata = readAppMetadata()
        writeAppMetadata(
            AppManagedMetadata(
                extensions = appMetadata.extensions + ExtensionIndex(
                    id = id,
                    fileName = fileName,
                    enabled = true,
                    origin = ExtensionOrigin.IMPORTED,
                    matchType = meta.matchType,
                )
            )
        )

        return Result.success(UserExtension(meta, normalizedContent))
    }

    fun exportExtensions(): List<ExportedExtension> {
        return getExtensions().map { meta ->
            val content = readScript(meta.scriptFileName) ?: ""
            ExportedExtension(
                id = meta.id,
                fileName = meta.scriptFileName,
                content = content,
                enabled = meta.enabled,
                origin = meta.origin,
                matchType = meta.matchType,
            )
        }
    }

    fun restoreExtensions(exported: List<ExportedExtension>) {
        val appMetadata = readAppMetadata()
        val newExtensions = exported.map { extension ->
            writeScript(extension.fileName, extension.content)
            ExtensionIndex(
                id = extension.id,
                fileName = extension.fileName,
                enabled = extension.enabled,
                origin = extension.origin,
                matchType = extension.matchType,
            )
        }
        val mergedExtensions = appMetadata.extensions.filter { existing ->
            exported.none { it.id == existing.id }
        } + newExtensions
        writeAppMetadata(AppManagedMetadata(extensions = mergedExtensions))
    }

    fun getScriptContent(fileName: String): String? = readScript(fileName)

    fun readScript(meta: UserExtensionMeta): String? = readScript(meta.scriptFileName)

    fun splitMatchValues(raw: String?): List<String> =
        raw.orEmpty().split(MULTI_VALUE_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    private fun migrateLegacyScriptsIfNeeded() {
        if (sharedPreferences.getBoolean(KEY_MIGRATED_V2, false)) return

        val extensions = readLegacyExtensions()
        if (extensions.isEmpty()) {
            sharedPreferences.edit { putBoolean(KEY_MIGRATED_V2, true) }
            return
        }

        val appMetadata = readAppMetadata()
        val migratedExtensions = appMetadata.extensions.toMutableList()

        for (extension in extensions) {
            val id = extension.meta.id
            val scriptFileName = extension.meta.scriptFileName
            val scriptContent = extension.scriptContent

            if (scriptFileName.isNotBlank() && scriptContent.isNotBlank()) {
                val content = UserScriptParser.serializeFromExtension(extension)
                writeScript(scriptFileName, content)

                if (migratedExtensions.none { it.id == id }) {
                    migratedExtensions.add(
                        ExtensionIndex(
                            id = id,
                            fileName = scriptFileName,
                            enabled = extension.meta.enabled,
                            origin = ExtensionOrigin.LEGACY,
                            matchType = extension.meta.matchType,
                        )
                    )
                }
            }
        }

        writeAppMetadata(AppManagedMetadata(extensions = migratedExtensions))
        sharedPreferences.edit { putBoolean(KEY_MIGRATED_V2, true) }
    }

    private fun readLegacyExtensions(): List<UserExtension> {
        val metadata = readLegacyMetadata()
        return metadata.mapNotNull { meta ->
            val scriptContent = readScript(meta.scriptFileName)
            if (scriptContent != null) {
                UserExtension(meta, scriptContent)
            } else null
        }
    }

    private fun readLegacyMetadata(): List<UserExtensionMeta> {
        val raw = sharedPreferences.getString(KEY_EXTENSION_META, "[]") ?: "[]"
        return runCatching { json.decodeFromString<List<UserExtensionMeta>>(raw) }
            .getOrElse { emptyList() }
    }

    private fun readAppMetadata(): AppManagedMetadata {
        return if (appMetadataFile.exists()) {
            runCatching {
                json.decodeFromString<AppManagedMetadata>(appMetadataFile.readText())
            }.getOrElse { AppManagedMetadata() }
        } else {
            AppManagedMetadata()
        }
    }

    private fun writeAppMetadata(metadata: AppManagedMetadata) {
        appMetadataFile.writeText(json.encodeToString(metadata))
    }

    private fun readScript(fileName: String): String? {
        val file = File(scriptsDir, fileName)
        return if (file.exists()) file.readText() else null
    }

    private fun writeScript(fileName: String, content: String) {
        scriptsDir.mkdirs()
        File(scriptsDir, fileName).writeText(content)
    }

    companion object {
        const val MULTI_VALUE_SEPARATOR = "@@"
        private const val KEY_EXTENSION_META = "user_extensions_meta"
        private const val KEY_MIGRATED_V2 = "user_extensions_migrated_v2"
        private const val KEY_BUNDLED_INSTALLED = "user_extensions_bundled_installed"
        private const val KEY_LEGACY_SCRIPTS = "user_scripts"
        private const val SCRIPT_DIRECTORY = "user_extensions"
        private const val APP_METADATA_FILE = "extensions_index.json"
        private const val ASSET_EXTENSION_DIRECTORY = "extensions"
        private const val USER_SCRIPT_SUFFIX = ".user.js"
    }
}

data class ExportedExtension(
    val id: String,
    val fileName: String,
    val content: String,
    val enabled: Boolean,
    val origin: ExtensionOrigin,
    val matchType: PassiveMatchType? = null,
)
