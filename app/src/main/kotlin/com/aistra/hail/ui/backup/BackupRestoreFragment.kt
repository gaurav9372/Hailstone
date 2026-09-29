package com.aistra.hail.ui.backup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.*
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.HFiles
import com.aistra.hail.utils.HUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class BackupRestoreFragment : MainFragment() {

    private var pendingRestorePayload by mutableStateOf<BackupPayload?>(null)

    private val pendingExportFile: File
        get() = File(app.cacheDir, "pending_export_backup.json")

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val appContext = context?.applicationContext ?: app
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val file = pendingExportFile
                    if (!file.exists()) throw IllegalStateException("Pending export file not found")
                    val bytes = file.readBytes()
                    appContext.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(bytes)
                        stream.flush()
                    }
                    file.delete()
                }.onSuccess {
                    withContext(Dispatchers.Main) {
                        HUI.showToast(R.string.msg_backup_export_success)
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        HUI.showToast(R.string.msg_backup_create_failed)
                    }
                }
            }
        } else {
            pendingExportFile.delete()
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val appContext = context?.applicationContext ?: app
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val content = runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = ByteArray(8192)
                        val out = ByteArrayOutputStream()
                        var totalRead = 0
                        var read = stream.read(buffer)
                        while (read != -1) {
                            totalRead += read
                            if (totalRead > 20 * 1024 * 1024) throw IllegalStateException("File exceeds 20MB limit")
                            out.write(buffer, 0, read)
                            read = stream.read(buffer)
                        }
                        out.toString("UTF-8")
                    }
                }.getOrNull()

                withContext(Dispatchers.Main) {
                    if (content != null) {
                        val payload = BackupManager.parseBackup(content)
                        if (payload != null) {
                            pendingRestorePayload = payload
                        } else {
                            HUI.showToast(R.string.msg_backup_restore_failed)
                        }
                    } else {
                        HUI.showToast(R.string.msg_backup_restore_failed)
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    BackupRestoreScreen()
                }
            }
        }
    }

    @Composable
    private fun BackupRestoreScreen() {
        var includeLists by remember { mutableStateOf(true) }
        var includeModes by remember { mutableStateOf(true) }
        var includeSettings by remember { mutableStateOf(true) }

        var listsCount by remember { mutableIntStateOf(0) }
        var totalAppsInLists by remember { mutableIntStateOf(0) }
        var modesCount by remember { mutableIntStateOf(0) }

        fun updateCounts() {
            lifecycleScope.launch(Dispatchers.IO) {
                val lc = FreezerData.lists.size
                val tc = FreezerData.lists.sumOf { it.packages.size }
                val mc = ModeData.modes.size
                withContext(Dispatchers.Main) {
                    listsCount = lc
                    totalAppsInLists = tc
                    modesCount = mc
                }
            }
        }

        LaunchedEffect(Unit) {
            updateCounts()
        }

        fun validateSelection(): Boolean {
            if (!includeLists && !includeModes && !includeSettings) {
                HUI.showToast(R.string.msg_backup_nothing_selected)
                return false
            }
            return true
        }

        fun onExportClick() {
            if (!validateSelection()) return
            lifecycleScope.launch(Dispatchers.IO) {
                val json = BackupManager.createBackupJson(includeLists, includeModes, includeSettings)
                HFiles.writeAtomic(pendingExportFile.absolutePath, json)
                withContext(Dispatchers.Main) {
                    exportLauncher.launch(BackupManager.generateBackupFileName())
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = dimensionResource(R.dimen.padding_medium),
                end = dimensionResource(R.dimen.padding_medium),
                top = dimensionResource(R.dimen.padding_medium),
                bottom = dimensionResource(R.dimen.padding_large)
            ),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.padding_medium))
        ) {
            // Create Backup Section
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(dimensionResource(R.dimen.padding_medium)),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Backup,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.backup_section_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Text(
                            text = stringResource(R.string.backup_section_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        HorizontalDivider()

                        BackupOptionRow(
                            icon = Icons.Outlined.Folder,
                            title = stringResource(R.string.backup_option_lists),
                            summary = stringResource(R.string.backup_option_lists_summary, listsCount, totalAppsInLists),
                            checked = includeLists,
                            onCheckedChange = { includeLists = it }
                        )

                        BackupOptionRow(
                            icon = Icons.Outlined.Tune,
                            title = stringResource(R.string.backup_option_modes),
                            summary = stringResource(R.string.backup_option_modes_summary, modesCount),
                            checked = includeModes,
                            onCheckedChange = { includeModes = it }
                        )

                        BackupOptionRow(
                            icon = Icons.Outlined.Settings,
                            title = stringResource(R.string.backup_option_settings),
                            summary = stringResource(R.string.backup_option_settings_summary),
                            checked = includeSettings,
                            onCheckedChange = { includeSettings = it }
                        )

                        HorizontalDivider()

                        Button(
                            onClick = ::onExportClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.action_export_backup))
                        }
                    }
                }
            }

            // Restore from File Section
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(dimensionResource(R.dimen.padding_medium)),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Restore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.restore_section_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Text(
                            text = stringResource(R.string.restore_section_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.action_select_backup_file))
                        }
                    }
                }
            }
        }

        // Restore confirmation dialog
        pendingRestorePayload?.let { payload ->
            RestoreConfirmDialog(
                payload = payload,
                onDismiss = { pendingRestorePayload = null },
                onConfirm = { restoreLists, restoreModes, restoreSettings, overwrite ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val success = BackupManager.restoreBackup(
                            payload = payload,
                            restoreLists = restoreLists,
                            restoreModes = restoreModes,
                            restoreSettings = restoreSettings,
                            overwrite = overwrite
                        )
                        withContext(Dispatchers.Main) {
                            if (success) {
                                HUI.showToast(R.string.msg_backup_restore_success)
                                updateCounts()
                            } else {
                                HUI.showToast(R.string.msg_backup_restore_failed)
                            }
                            pendingRestorePayload = null
                        }
                    }
                }
            )
        }
    }

    @Composable
    private fun BackupOptionRow(
        icon: ImageVector,
        title: String,
        summary: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }

    @Composable
    private fun RestoreConfirmDialog(
        payload: BackupPayload,
        onDismiss: () -> Unit,
        onConfirm: (restoreLists: Boolean, restoreModes: Boolean, restoreSettings: Boolean, overwrite: Boolean) -> Unit
    ) {
        var restoreLists by remember { mutableStateOf(payload.hasLists) }
        var restoreModes by remember { mutableStateOf(payload.hasModes) }
        var restoreSettings by remember { mutableStateOf(payload.hasSettings) }
        var overwrite by remember { mutableStateOf(false) }

        val isModeActive = remember { ModeData.activeMode() != null }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = stringResource(R.string.restore_dialog_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            R.string.restore_dialog_subtitle,
                            BackupManager.formatDate(payload.timestamp),
                            payload.appVersion
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isModeActive) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.msg_disable_mode_before_restore),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.restore_select_data),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (payload.hasLists) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { restoreLists = !restoreLists },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = restoreLists, onCheckedChange = { restoreLists = it })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${stringResource(R.string.backup_option_lists)} (${payload.listsCount} lists, ${payload.totalAppsInLists} apps)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    if (payload.hasModes) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { restoreModes = !restoreModes },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = restoreModes, onCheckedChange = { restoreModes = it })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${stringResource(R.string.backup_option_modes)} (${payload.modesCount} modes)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    if (payload.hasSettings) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { restoreSettings = !restoreSettings },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = restoreSettings, onCheckedChange = { restoreSettings = it })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.backup_option_settings),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { overwrite = !overwrite },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                        Checkbox(checked = overwrite, onCheckedChange = { overwrite = it })
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.restore_overwrite_option),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.restore_overwrite_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!restoreLists && !restoreModes && !restoreSettings) {
                            HUI.showToast(R.string.msg_restore_nothing_selected)
                        } else if (isModeActive && (restoreModes || (restoreLists && overwrite))) {
                            HUI.showToast(R.string.msg_disable_mode_before_restore)
                        } else {
                            onConfirm(restoreLists, restoreModes, restoreSettings, overwrite)
                        }
                    }
                ) {
                    Text(text = stringResource(R.string.action_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
