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
)

data class UserExtension(
    val meta: UserExtensionMeta,
    val scriptContent: String,
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

    fun getExtensions(): List<UserExtensionMeta> {
        migrateLegacyScriptsIfNeeded()
        return readMetadata()
    }

    fun getActiveExtensions(): List<UserExtensionMeta> =
        getExtensions().filter { it.type == ExtensionType.ACTIVE }

    fun getPassiveExtensions(): List<UserExtensionMeta> =
        getExtensions().filter { it.type == ExtensionType.PASSIVE }

    fun getExtension(id: String): UserExtension? {
        val meta = getExtensions().find { it.id == id } ?: return null
        return UserExtension(meta, readScript(meta) ?: "")
    }

    fun createExtension(type: ExtensionType): UserExtension {
        val id = UUID.randomUUID().toString()
        return UserExtension(
            meta = UserExtensionMeta(
                id = id,
                type = type,
                name = "",
                enabled = true,
                scriptFileName = "$id.js",
                matchType = if (type == ExtensionType.PASSIVE) PassiveMatchType.LINK else null,
                matchValue = if (type == ExtensionType.PASSIVE) "" else null,
                runAt = if (type == ExtensionType.PASSIVE) PassiveRunAt.DOM_CONTENT_LOADED else null,
            ),
            scriptContent = "",
        )
    }

    fun saveExtension(extension: UserExtension) {
        migrateLegacyScriptsIfNeeded()
        scriptsDir.mkdirs()
        writeScript(extension.meta.scriptFileName, extension.scriptContent)

        val metadata = readMetadata().toMutableList()
        val index = metadata.indexOfFirst { it.id == extension.meta.id }
        if (index >= 0) {
            metadata[index] = extension.meta
        } else {
            metadata.add(extension.meta)
        }
        writeMetadata(metadata)
    }

    fun deleteExtension(id: String) {
        migrateLegacyScriptsIfNeeded()
        val metadata = readMetadata().toMutableList()
        val index = metadata.indexOfFirst { it.id == id }
        if (index < 0) return

        val target = metadata.removeAt(index)
        writeMetadata(metadata)
        File(scriptsDir, target.scriptFileName).delete()
    }

    fun toggleExtension(id: String) {
        migrateLegacyScriptsIfNeeded()
        val metadata = readMetadata().toMutableList()
        val index = metadata.indexOfFirst { it.id == id }
        if (index < 0) return

        metadata[index] = metadata[index].copy(enabled = !metadata[index].enabled)
        writeMetadata(metadata)
    }

    fun readScript(meta: UserExtensionMeta): String? = readScript(meta.scriptFileName)

    fun splitMatchValues(raw: String?): List<String> =
        raw.orEmpty().split(MULTI_VALUE_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    private fun migrateLegacyScriptsIfNeeded() {
        if (sharedPreferences.getBoolean(KEY_MIGRATED, false)) return

        val existing = readMetadata()
        if (existing.isNotEmpty()) {
            sharedPreferences.edit { putBoolean(KEY_MIGRATED, true) }
            return
        }

        val legacyJson = sharedPreferences.getString(KEY_LEGACY_SCRIPTS, "[]") ?: "[]"
        val legacyScripts = runCatching { json.decodeFromString<List<UserScript>>(legacyJson) }
            .getOrElse { emptyList() }

        if (legacyScripts.isNotEmpty()) {
            val migrated = legacyScripts.map { script ->
                val id = UUID.randomUUID().toString()
                val scriptFileName = "$id.js"
                writeScript(scriptFileName, script.scriptContent)
                UserExtensionMeta(
                    id = id,
                    type = ExtensionType.PASSIVE,
                    name = script.name,
                    enabled = script.enabled,
                    scriptFileName = scriptFileName,
                    matchType = PassiveMatchType.LINK,
                    matchValue = script.urlPattern,
                    runAt = PassiveRunAt.DOM_CONTENT_LOADED,
                )
            }
            writeMetadata(migrated)
        }

        sharedPreferences.edit {
            putBoolean(KEY_MIGRATED, true)
            remove(KEY_LEGACY_SCRIPTS)
        }
    }

    private fun readMetadata(): List<UserExtensionMeta> {
        val raw = sharedPreferences.getString(KEY_EXTENSION_META, "[]") ?: "[]"
        return runCatching { json.decodeFromString<List<UserExtensionMeta>>(raw) }
            .getOrElse { emptyList() }
    }

    private fun writeMetadata(metadata: List<UserExtensionMeta>) {
        sharedPreferences.edit { putString(KEY_EXTENSION_META, json.encodeToString(metadata)) }
    }

    private fun readScript(fileName: String): String? {
        val file = File(scriptsDir, fileName)
        return if (file.exists()) file.readText() else null
    }

    private fun writeScript(fileName: String, content: String) {
        File(scriptsDir, fileName).writeText(content)
    }

    companion object {
        const val MULTI_VALUE_SEPARATOR = "@@"

        private const val KEY_EXTENSION_META = "user_extensions_meta"
        private const val KEY_MIGRATED = "user_extensions_migrated_v1"
        private const val KEY_LEGACY_SCRIPTS = "user_scripts"
        private const val SCRIPT_DIRECTORY = "user_extensions"
    }
}
