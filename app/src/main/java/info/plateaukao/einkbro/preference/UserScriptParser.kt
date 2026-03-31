package info.plateaukao.einkbro.preference

import kotlinx.serialization.Serializable

@Serializable
data class ParsedUserScriptMeta(
    val name: String = "",
    val namespace: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val matchPatterns: List<String> = emptyList(),
    val includePatterns: List<String> = emptyList(),
    val excludePatterns: List<String> = emptyList(),
    val runAt: String = "",
    val grantList: List<String> = emptyList(),
)

data class ParsedUserScript(
    val meta: ParsedUserScriptMeta,
    val scriptBody: String,
)

object UserScriptParser {

    fun parse(rawScript: String): ParsedUserScript {
        val lines = rawScript.lines()
        val metaLines = mutableListOf<String>()
        val scriptBodyBuilder = StringBuilder()
        var inMetaBlock = false
        var metaBlockEnded = false

        for (line in lines) {
            when {
                line.trim() == "// ==UserScript==" -> {
                    inMetaBlock = true
                    continue
                }
                line.trim() == "// ==/UserScript==" -> {
                    inMetaBlock = false
                    metaBlockEnded = true
                    continue
                }
                inMetaBlock -> {
                    metaLines.add(line)
                }
                metaBlockEnded -> {
                    if (scriptBodyBuilder.isNotEmpty() || line.isNotBlank()) {
                        scriptBodyBuilder.appendLine(line)
                    }
                }
            }
        }

        val meta = parseMetaLines(metaLines)
        val scriptBody = scriptBodyBuilder.toString().trimEnd('\n', '\r')

        return ParsedUserScript(meta, scriptBody)
    }

    private fun parseMetaLines(lines: List<String>): ParsedUserScriptMeta {
        var name = ""
        var namespace = ""
        var version = ""
        var description = ""
        var author = ""
        val matchPatterns = mutableListOf<String>()
        val includePatterns = mutableListOf<String>()
        val excludePatterns = mutableListOf<String>()
        var runAt = ""
        val grantList = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("// @")) continue

            val content = trimmed.removePrefix("// @")
            val parts = content.split(Regex("\\s+"), limit = 2)
            if (parts.isEmpty()) continue

            val key = parts[0]
            val value = parts.getOrElse(1) { "" }

            when (key) {
                "name" -> name = value
                "namespace" -> namespace = value
                "version" -> version = value
                "description" -> description = value
                "author" -> author = value
                "match" -> matchPatterns.add(value)
                "include" -> includePatterns.add(value)
                "exclude" -> excludePatterns.add(value)
                "run-at" -> runAt = value
                "grant" -> grantList.add(value)
            }
        }

        return ParsedUserScriptMeta(
            name = name,
            namespace = namespace,
            version = version,
            description = description,
            author = author,
            matchPatterns = matchPatterns.toList(),
            includePatterns = includePatterns.toList(),
            excludePatterns = excludePatterns.toList(),
            runAt = runAt,
            grantList = grantList.toList(),
        )
    }

    fun serialize(meta: ParsedUserScriptMeta, scriptBody: String): String {
        val sb = StringBuilder()
        sb.appendLine("// ==UserScript==")

        if (meta.name.isNotEmpty()) {
            sb.appendLine("// @name        ${meta.name}")
        }
        if (meta.namespace.isNotEmpty()) {
            sb.appendLine("// @namespace   ${meta.namespace}")
        }
        if (meta.version.isNotEmpty()) {
            sb.appendLine("// @version     ${meta.version}")
        }
        if (meta.description.isNotEmpty()) {
            sb.appendLine("// @description ${meta.description}")
        }
        if (meta.author.isNotEmpty()) {
            sb.appendLine("// @author      ${meta.author}")
        }
        for (pattern in meta.matchPatterns) {
            sb.appendLine("// @match       $pattern")
        }
        for (pattern in meta.includePatterns) {
            sb.appendLine("// @include     $pattern")
        }
        for (pattern in meta.excludePatterns) {
            sb.appendLine("// @exclude     $pattern")
        }
        if (meta.runAt.isNotEmpty()) {
            sb.appendLine("// @run-at      ${meta.runAt}")
        }
        for (grant in meta.grantList) {
            sb.appendLine("// @grant       $grant")
        }

        sb.appendLine("// ==/UserScript==")
        sb.appendLine()
        sb.append(scriptBody)

        return sb.toString()
    }

    fun serializeFromExtension(extension: UserExtension): String {
        val existingParsed = runCatching { parse(extension.scriptContent) }.getOrNull()
        val scriptBody = existingParsed?.scriptBody ?: extension.scriptContent

        val meta = ParsedUserScriptMeta(
            name = extension.meta.name,
            namespace = "einkbro",
            version = existingParsed?.meta?.version ?: "1.0.0",
            description = existingParsed?.meta?.description ?: "",
            author = existingParsed?.meta?.author ?: "",
            matchPatterns = extension.meta.matchValue?.let {
                it.split(UserExtensionRepository.MULTI_VALUE_SEPARATOR).map { v -> v.trim() }
            }?.filter { it.isNotEmpty() } ?: emptyList(),
            includePatterns = emptyList(),
            excludePatterns = emptyList(),
            runAt = extension.meta.runAt?.toRunAtString() ?: "",
            grantList = existingParsed?.meta?.grantList ?: emptyList(),
        )
        return serialize(meta, scriptBody)
    }

    fun serializeMetaOnly(meta: ParsedUserScriptMeta): String = serialize(meta, "")

    private fun PassiveRunAt.toRunAtString(): String = when (this) {
        PassiveRunAt.EARLY -> "document-start"
        PassiveRunAt.DOM_CONTENT_LOADED -> "document-end"
    }

    fun parseToUserExtensionMeta(
        parsed: ParsedUserScript,
        id: String,
        scriptFileName: String,
        originalType: ExtensionType? = null,
        originalMatchType: PassiveMatchType? = null,
        enabled: Boolean = true,
    ): UserExtensionMeta {
        val patterns = parsed.meta.matchPatterns + parsed.meta.includePatterns
        val isActive = originalType == ExtensionType.ACTIVE ||
            (originalType == null && patterns.isEmpty())

        return UserExtensionMeta(
            id = id,
            type = if (isActive) ExtensionType.ACTIVE else ExtensionType.PASSIVE,
            name = parsed.meta.name,
            enabled = enabled,
            scriptFileName = scriptFileName,
            matchType = if (isActive) null else (originalMatchType ?: PassiveMatchType.LINK),
            matchValue = if (patterns.isEmpty()) null else patterns.joinToString(UserExtensionRepository.MULTI_VALUE_SEPARATOR),
            runAt = parsed.meta.runAt.toPassiveRunAt(),
        )
    }

    private fun String.toPassiveRunAt(): PassiveRunAt? = when (this) {
        "document-start" -> PassiveRunAt.EARLY
        "document-end", "document-idle" -> PassiveRunAt.DOM_CONTENT_LOADED
        else -> null
    }
}
