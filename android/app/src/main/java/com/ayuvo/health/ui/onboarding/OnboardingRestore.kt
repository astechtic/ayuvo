package com.ayuvo.health.ui.onboarding

import android.accounts.Account
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.backup.DriveCloudBackupClient
import com.ayuvo.health.export.AllDataExportArchive
import com.ayuvo.health.export.AllDataExportCoordinator
import com.ayuvo.health.export.AllDataImportOutcome
import com.ayuvo.health.export.AllDataImportUi
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.settings.groups.AllDataImportDialogs
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * Welcome › "Restore from a backup": the chooser (an Export All Data zip from Files / Google Drive,
 * or the Google Drive backup), Import All Data's preview and result dialogs, the Drive sign-in and
 * the errors. A restored app backup carries the profile and settings, so the view model then jumps
 * to the steps that still need this phone (notifications, Health Connect, AI setup).
 */
@Composable
internal fun OnboardingRestoreHost(
    container: AppContainer,
    vm: OnboardingViewModel,
    ui: OnboardingState,
    showChooser: Boolean,
    onChooserDismiss: () -> Unit
) {
    val importer = container.allDataImport
    val importUi by importer.ui.collectAsState()
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()

    // The system picker lists Google Drive next to local storage, so one launcher covers both.
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importer.open(uri)
    }

    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || activity == null) {
            vm.restoreSignInFailed()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            if (container.cloudBackup.finishAuthorization(activity, result.data)) vm.restoreFromDrive()
            else vm.restoreSignInFailed()
        }
    }
    val continueDriveAuth: (Account) -> Unit = { account ->
        if (activity == null) {
            vm.restoreSignInFailed()
        } else {
            scope.launch {
                runCatching { container.cloudBackup.authorize(activity, account) }
                    .onSuccess { outcome ->
                        when (outcome) {
                            is DriveCloudBackupClient.AuthOutcome.Token -> vm.restoreFromDrive()
                            is DriveCloudBackupClient.AuthOutcome.Resolution ->
                                authLauncher.launch(IntentSenderRequest.Builder(outcome.intentSender).build())
                        }
                    }
                    .onFailure { vm.restoreSignInFailed() }
            }
        }
    }
    val accountPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val account = if (result.resultCode == Activity.RESULT_OK) {
            container.cloudBackup.accountFromPickerResult(result.data)
        } else {
            null
        }
        if (account == null) vm.restoreSignInFailed() else continueDriveAuth(account)
    }
    val startDriveRestore: () -> Unit = {
        runCatching { accountPickerLauncher.launch(container.cloudBackup.accountPickerIntent()) }
            .onFailure { vm.restoreSignInFailed() }
    }

    if (showChooser) {
        GlassDialog(onDismissRequest = onChooserDismiss, modifier = Modifier.testTag("onboarding.restore.chooser")) {
            Text(stringResource(R.string.onboarding_restore_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.onboarding_restore_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 14.sp
            )
            GlassPrimaryButton(
                text = stringResource(R.string.onboarding_restore_file),
                onClick = {
                    onChooserDismiss()
                    fileLauncher.launch(
                        arrayOf(AllDataExportArchive.MIME_TYPE, "application/octet-stream", "application/x-zip-compressed")
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag("onboarding.restore.file")
            )
            // Same Drive backup Settings › Backup & Export turns on; always offered, like there.
            GlassPrimaryButton(
                text = stringResource(R.string.onboarding_restore_drive),
                onClick = {
                    onChooserDismiss()
                    startDriveRestore()
                },
                modifier = Modifier.fillMaxWidth().testTag("onboarding.restore.drive")
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.action_cancel),
                onPrimary = onChooserDismiss
            )
        }
    }

    // Preview, progress and result of the Import All Data run. Dismissing the result is the moment
    // a restored app backup moves onboarding on; the same coordinator also serves Settings, so it
    // is put back to Idle here.
    AllDataImportDialogs(
        ui = importUi,
        onConfirm = importer::confirm,
        onDismiss = {
            val done = importUi as? AllDataImportUi.Done
            importer.dismiss()
            vm.onImportFinished(
                // The portable part carries the profile too: it is how an iPhone zip restores here. The
                // view model still refuses (NO_PROFILE) when no profile ended up on the phone.
                done?.outcomes?.any {
                    it is AllDataImportOutcome.Imported && (
                        it.section == AllDataExportCoordinator.SECTION_APP_BACKUP ||
                            it.section == AllDataExportCoordinator.SECTION_PORTABLE
                        )
                } == true
            )
        }
    )

    // Import All Data shows no progress of its own (Settings uses a row subtitle), so cover it here.
    val importing = importUi is AllDataImportUi.Reading || importUi is AllDataImportUi.Running
    if (ui.restoreBusy || importing) {
        GlassDialog(onDismissRequest = {}, modifier = Modifier.testTag("onboarding.restore.busy")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AppColors.Calorie)
                Text(stringResource(R.string.onboarding_restore_busy))
            }
        }
    }

    ui.restoreError?.let { error ->
        GlassDialog(onDismissRequest = vm::dismissRestoreError, modifier = Modifier.testTag("onboarding.restore.error")) {
            Text(stringResource(R.string.onboarding_restore_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                when (error) {
                    RestoreError.SIGN_IN_FAILED -> stringResource(R.string.cloud_backup_sign_in_failed)
                    RestoreError.NO_DRIVE_BACKUP -> stringResource(R.string.onboarding_restore_error_no_drive)
                    RestoreError.NO_PROFILE -> stringResource(R.string.onboarding_restore_error_no_profile)
                    RestoreError.FAILED -> ui.restoreErrorDetail
                        ?.let { stringResource(R.string.onboarding_restore_error_failed, it) }
                        ?: stringResource(R.string.onboarding_restore_error_failed_unknown)
                },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 14.sp
            )
            GlassDialogActions(primaryText = stringResource(R.string.action_ok), onPrimary = vm::dismissRestoreError)
        }
    }
}
