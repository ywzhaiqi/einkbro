package info.plateaukao.einkbro.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.AlertDialog
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.plateaukao.einkbro.R
import info.plateaukao.einkbro.preference.ExtensionType
import info.plateaukao.einkbro.preference.PassiveMatchType
import info.plateaukao.einkbro.preference.PassiveRunAt
import info.plateaukao.einkbro.preference.UserExtensionMeta
import info.plateaukao.einkbro.preference.UserExtensionRepository
import info.plateaukao.einkbro.view.compose.MyTheme
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class UserExtensionsActivity : ComponentActivity(), KoinComponent {
    private val repository: UserExtensionRepository by inject()
    private val extensionsState = mutableStateOf<List<UserExtensionMeta>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reload()
        render()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun render() {
        setContent {
            MyTheme {
                UserExtensionsScreen(
                    items = extensionsState.value,
                    onBack = ::finish,
                    onAdd = { type ->
                        startActivity(UserExtensionEditActivity.createIntent(this, type = type))
                    },
                    onEdit = { meta ->
                        startActivity(UserExtensionEditActivity.createIntent(this, extensionId = meta.id))
                    },
                    onToggle = { meta ->
                        repository.toggleExtension(meta.id)
                        reload()
                    },
                    onDelete = { meta ->
                        repository.deleteExtension(meta.id)
                        reload()
                    },
                )
            }
        }
    }

    private fun reload() {
        extensionsState.value = repository.getExtensions()
    }

    companion object {
        fun createIntent(context: Context) = Intent(context, UserExtensionsActivity::class.java)
    }
}

@Composable
private fun UserExtensionsScreen(
    items: List<UserExtensionMeta>,
    onBack: () -> Unit,
    onAdd: (ExtensionType) -> Unit,
    onEdit: (UserExtensionMeta) -> Unit,
    onToggle: (UserExtensionMeta) -> Unit,
    onDelete: (UserExtensionMeta) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    val activeItems = items.filter { it.type == ExtensionType.ACTIVE }
    val passiveItems = items.filter { it.type == ExtensionType.PASSIVE }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.user_extensions),
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
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            tint = MaterialTheme.colors.onPrimary,
                            contentDescription = stringResource(R.string.add_extension)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = stringResource(R.string.user_extensions_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                item { SectionHeader(stringResource(R.string.active_extensions)) }
                items(activeItems) { meta ->
                    UserExtensionRow(meta, onEdit, onToggle, onDelete)
                }
                item {
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    SectionHeader(stringResource(R.string.passive_extensions))
                }
                items(passiveItems) { meta ->
                    UserExtensionRow(meta, onEdit, onToggle, onDelete)
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.add_extension)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.active_extensions),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAddDialog = false
                                onAdd(ExtensionType.ACTIVE)
                            }
                            .padding(vertical = 8.dp)
                    )
                    Text(
                        text = stringResource(R.string.passive_extensions),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAddDialog = false
                                onAdd(ExtensionType.PASSIVE)
                            }
                            .padding(vertical = 8.dp)
                    )
                }
            },
            buttons = {}
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.h6,
    )
}

@Composable
private fun UserExtensionRow(
    meta: UserExtensionMeta,
    onEdit: (UserExtensionMeta) -> Unit,
    onToggle: (UserExtensionMeta) -> Unit,
    onDelete: (UserExtensionMeta) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit(meta) }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meta.name.ifBlank { stringResource(R.string.extension_untitled) },
                    style = MaterialTheme.typography.subtitle1,
                )
                Text(text = meta.summary(), style = MaterialTheme.typography.body2)
            }
            Switch(checked = meta.enabled, onCheckedChange = { onToggle(meta) })
            IconButton(onClick = { onDelete(meta) }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.menu_delete)
                )
            }
        }
        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}

private fun UserExtensionMeta.summary(): String =
    if (type == ExtensionType.ACTIVE) {
        "Manual"
    } else {
        val matchTypeText = when (matchType ?: PassiveMatchType.LINK) {
            PassiveMatchType.DOMAIN -> "Domain"
            PassiveMatchType.LINK -> "Link"
        }
        val runAtText = when (runAt ?: PassiveRunAt.DOM_CONTENT_LOADED) {
            PassiveRunAt.EARLY -> "Early"
            PassiveRunAt.DOM_CONTENT_LOADED -> "DOMContentLoaded"
        }
        "$matchTypeText | ${matchValue.orEmpty()} | $runAtText"
    }
