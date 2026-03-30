package info.plateaukao.einkbro.view.dialog.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import info.plateaukao.einkbro.R
import info.plateaukao.einkbro.preference.UserExtensionMeta
import info.plateaukao.einkbro.view.compose.MyTheme

class ActiveExtensionsDialogFragment(
    private val extensions: List<UserExtensionMeta>,
    private val onSelected: (UserExtensionMeta) -> Unit,
) : ComposeDialogFragment() {
    init {
        shouldShowInCenter = true
    }

    override fun setupComposeView() = composeView.setContent {
        MyTheme {
            ActiveExtensionsContent(extensions) {
                dialog?.dismiss()
                onSelected(it)
            }
        }
    }
}

@Composable
private fun ActiveExtensionsContent(
    extensions: List<UserExtensionMeta>,
    onSelected: (UserExtensionMeta) -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 320.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.active_extensions),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = MaterialTheme.colors.onBackground,
            style = MaterialTheme.typography.h6,
        )
        HorizontalSeparator()
        extensions.forEachIndexed { index, extension ->
            Text(
                text = extension.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelected(extension) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.body1,
            )
            if (index != extensions.lastIndex) {
                HorizontalSeparator()
            }
        }
    }
}
