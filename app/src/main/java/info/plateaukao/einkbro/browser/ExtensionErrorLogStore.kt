package info.plateaukao.einkbro.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExtensionErrorLogEntry(
    val id: Long,
    val extensionName: String,
    val details: String,
    val pageUrl: String,
    val sourceId: String,
    val lineNumber: Int,
    val createdAt: Long,
)

class ExtensionErrorLogStore {
    private val _entries = MutableStateFlow<List<ExtensionErrorLogEntry>>(emptyList())
    val entries: StateFlow<List<ExtensionErrorLogEntry>> = _entries.asStateFlow()

    fun append(
        extensionName: String,
        details: String,
        pageUrl: String,
        sourceId: String,
        lineNumber: Int,
    ) {
        val now = System.currentTimeMillis()
        val nextEntry = ExtensionErrorLogEntry(
            id = now,
            extensionName = extensionName,
            details = details,
            pageUrl = pageUrl,
            sourceId = sourceId,
            lineNumber = lineNumber,
            createdAt = now,
        )
        _entries.value = (listOf(nextEntry) + _entries.value).take(MAX_ENTRIES)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    companion object {
        private const val MAX_ENTRIES = 100
        const val ERROR_PREFIX = "User extension failed: "
        const val SOURCE_PREFIX = "einkbro-extension://"
    }
}
