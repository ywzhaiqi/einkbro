package info.plateaukao.einkbro.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.plateaukao.einkbro.R
import info.plateaukao.einkbro.preference.ExtensionType
import info.plateaukao.einkbro.preference.PassiveMatchType
import info.plateaukao.einkbro.preference.PassiveRunAt
import info.plateaukao.einkbro.preference.UserExtension
import info.plateaukao.einkbro.preference.UserExtensionRepository
import info.plateaukao.einkbro.view.EBToast
import info.plateaukao.einkbro.view.compose.MyTheme
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class UserExtensionEditActivity : ComponentActivity(), KoinComponent {
    private val repository: UserExtensionRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extensionId = intent.getStringExtra(KEY_EXTENSION_ID)
        val type = intent.getStringExtra(KEY_EXTENSION_TYPE)?.let { ExtensionType.valueOf(it) }
            ?: ExtensionType.ACTIVE
        val initial = extensionId?.let { repository.getExtension(it) } ?: repository.createExtension(type)

        setContent {
            MyTheme {
                UserExtensionEditScreen(
                    initial = initial,
                    onBack = ::finish,
                    onSave = { extension ->
                        if (extension.meta.name.isBlank()) {
                            EBToast.show(this, getString(R.string.extension_name_required))
                            return@UserExtensionEditScreen
                        }
                        if (extension.meta.type == ExtensionType.PASSIVE &&
                            extension.meta.matchValue.isNullOrBlank()
                        ) {
                            EBToast.show(this, getString(R.string.extension_match_required))
                            return@UserExtensionEditScreen
                        }
                        repository.saveExtension(extension)
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        private const val KEY_EXTENSION_ID = "extension_id"
        private const val KEY_EXTENSION_TYPE = "extension_type"

        fun createIntent(
            context: Context,
            extensionId: String? = null,
            type: ExtensionType? = null,
        ) = Intent(context, UserExtensionEditActivity::class.java).apply {
            extensionId?.let { putExtra(KEY_EXTENSION_ID, it) }
            type?.let { putExtra(KEY_EXTENSION_TYPE, it.name) }
        }
    }
}

@Composable
private fun UserExtensionEditScreen(
    initial: UserExtension,
    onBack: () -> Unit,
    onSave: (UserExtension) -> Unit,
) {
    var name by remember { mutableStateOf(initial.meta.name) }
    var scriptContent by remember { mutableStateOf(initial.scriptContent) }
    var matchType by remember { mutableStateOf(initial.meta.matchType ?: PassiveMatchType.LINK) }
    var matchValue by remember { mutableStateOf(initial.meta.matchValue.orEmpty()) }
    var runAt by remember { mutableStateOf(initial.meta.runAt ?: PassiveRunAt.DOM_CONTENT_LOADED) }

    Scaffold(
        backgroundColor = MaterialTheme.colors.background,
        topBar = {
            TopAppBar(
                backgroundColor = MaterialTheme.colors.primary,
                contentColor = MaterialTheme.colors.onPrimary,
                title = {
                    Text(
                        text = if (initial.meta.type == ExtensionType.ACTIVE) {
                            stringResource(R.string.active_extension_edit)
                        } else {
                            stringResource(R.string.passive_extension_edit)
                        },
                        color = MaterialTheme.colors.onPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            tint = MaterialTheme.colors.onPrimary,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            onSave(
                                UserExtension(
                                    meta = initial.meta.copy(
                                        name = name.trim(),
                                        matchType = if (initial.meta.type == ExtensionType.PASSIVE) matchType else null,
                                        matchValue = if (initial.meta.type == ExtensionType.PASSIVE) matchValue.trim() else null,
                                        runAt = if (initial.meta.type == ExtensionType.PASSIVE) runAt else null,
                                    ),
                                    scriptContent = scriptContent,
                                )
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            tint = MaterialTheme.colors.onPrimary,
                            contentDescription = stringResource(R.string.save)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.extension_name), color = MaterialTheme.colors.onSurface) },
                singleLine = true,
                textStyle = MaterialTheme.typography.body1.copy(color = MaterialTheme.colors.onSurface),
                colors = extensionTextFieldColors()
            )

            if (initial.meta.type == ExtensionType.PASSIVE) {
                SelectionField(
                    label = stringResource(R.string.extension_match_type),
                    currentLabel = matchType.toDisplayText(),
                    options = PassiveMatchType.values().map { it.toDisplayText() to it },
                    onSelected = { matchType = it }
                )
                OutlinedTextField(
                    value = matchValue,
                    onValueChange = { matchValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.extension_match_value), color = MaterialTheme.colors.onSurface) },
                    textStyle = MaterialTheme.typography.body1.copy(color = MaterialTheme.colors.onSurface),
                    colors = extensionTextFieldColors()
                )
                Text(
                    text = stringResource(R.string.extension_match_value_hint),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackground
                )
                SelectionField(
                    label = stringResource(R.string.extension_run_at),
                    currentLabel = runAt.toDisplayText(),
                    options = PassiveRunAt.values().map { it.toDisplayText() to it },
                    onSelected = { runAt = it }
                )
            }

            OutlinedTextField(
                value = scriptContent,
                onValueChange = { scriptContent = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.extension_script), color = MaterialTheme.colors.onSurface) },
                minLines = 16,
                textStyle = MaterialTheme.typography.body1.copy(color = MaterialTheme.colors.onSurface),
                colors = extensionTextFieldColors()
            )
        }
    }
}

@Composable
private fun <T> SelectionField(
    label: String,
    currentLabel: String,
    options: List<Pair<String, T>>,
    onSelected: (T) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.body2.copy(
                color = MaterialTheme.colors.onBackground,
                fontWeight = FontWeight.Medium
            )
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            options.forEach { (text, value) ->
                val selected = text == currentLabel
                TextButton(
                    onClick = { onSelected(value) }
                ) {
                    val display = if (text == currentLabel) "[$text]" else text
                    Text(
                        display,
                        color = if (selected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onBackground
                    )
                }
            }
        }
    }
}

@Composable
private fun extensionTextFieldColors() = TextFieldDefaults.outlinedTextFieldColors(
    textColor = MaterialTheme.colors.onSurface,
    backgroundColor = MaterialTheme.colors.surface,
    focusedBorderColor = MaterialTheme.colors.onBackground,
    unfocusedBorderColor = MaterialTheme.colors.onSurface,
    focusedLabelColor = MaterialTheme.colors.onBackground,
    unfocusedLabelColor = MaterialTheme.colors.onSurface,
    cursorColor = MaterialTheme.colors.onBackground,
)

private fun PassiveMatchType.toDisplayText(): String = when (this) {
    PassiveMatchType.DOMAIN -> "Domain"
    PassiveMatchType.LINK -> "Link"
}

private fun PassiveRunAt.toDisplayText(): String = when (this) {
    PassiveRunAt.EARLY -> "Early"
    PassiveRunAt.DOM_CONTENT_LOADED -> "DOMContentLoaded"
}
