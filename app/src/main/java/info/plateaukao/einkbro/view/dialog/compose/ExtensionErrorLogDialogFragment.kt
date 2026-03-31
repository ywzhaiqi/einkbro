package info.plateaukao.einkbro.view.dialog.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.plateaukao.einkbro.R
import info.plateaukao.einkbro.browser.ExtensionErrorLogEntry
import info.plateaukao.einkbro.browser.ExtensionErrorLogStore
import info.plateaukao.einkbro.view.compose.MyTheme
import org.koin.android.ext.android.inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExtensionErrorLogDialogFragment : ComposeDialogFragment() {
    private val errorLogStore: ExtensionErrorLogStore by inject()

    init {
        shouldShowInCenter = true
    }

    override fun setupComposeView() = composeView.setContent {
        val entries by errorLogStore.entries.collectAsState()
        MyTheme {
            ExtensionErrorLogContent(
                entries = entries,
                onClear = errorLogStore::clear,
                onClose = { dialog?.dismiss() },
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

@Composable
private fun ExtensionErrorLogContent(
    entries: List<ExtensionErrorLogEntry>,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 280.dp, max = 420.dp)
            .heightIn(min = 180.dp, max = 520.dp)
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.extension_error_logs),
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.h6,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.extension_error_logs_clear),
                    modifier = Modifier.clickable { onClear() },
                    color = MaterialTheme.colors.onBackground,
                    style = MaterialTheme.typography.body2,
                )
                Text(
                    text = stringResource(android.R.string.cancel),
                    modifier = Modifier.clickable { onClose() },
                    color = MaterialTheme.colors.onBackground,
                    style = MaterialTheme.typography.body2,
                )
            }
        }
        HorizontalSeparator()
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.extension_error_logs_empty),
                    color = MaterialTheme.colors.onBackground,
                    style = MaterialTheme.typography.body1,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    ExtensionErrorLogEntryView(entry)
                }
            }
        }
    }
}

@Composable
private fun ExtensionErrorLogEntryView(entry: ExtensionErrorLogEntry) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "${formatTimestamp(entry.createdAt)}  ${entry.extensionName}",
            color = MaterialTheme.colors.onBackground,
            style = MaterialTheme.typography.subtitle2,
        )
        Text(
            text = entry.details,
            color = MaterialTheme.colors.onBackground,
            style = MaterialTheme.typography.body2,
        )
        if (entry.pageUrl.isNotBlank()) {
            Text(
                text = entry.pageUrl,
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.sourceId.isNotBlank() || entry.lineNumber > 0) {
            val sourceText = buildString {
                if (entry.sourceId.isNotBlank()) append(entry.sourceId)
                if (entry.lineNumber > 0) append(":${entry.lineNumber}")
            }
            Text(
                text = sourceText,
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.caption,
            )
        }
        HorizontalSeparator()
    }
}
