package com.example.biletflow.presentation.organizer

import com.example.biletflow.core.designsystem.TextButton
import com.example.biletflow.core.designsystem.Button
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import biletflow.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun OrganizerContent(vm: OrganizerViewModel, onLogin: () -> Unit) {
    val state by vm.state.collectAsState()
    when {
        state.loading -> CircularProgressIndicator()
        state.error -> {
            Text(stringResource(Res.string.organizer_error))
            Button(onClick = if (state.unauthorized) onLogin else { { if (state.form) vm.apply() else vm.load() } }) {
                Text(stringResource(if (state.unauthorized) Res.string.auth_login_button else Res.string.events_retry))
            }
        }
        state.absent -> Button(onClick = vm::openForm) { Text(stringResource(Res.string.organizer_apply)) }
        state.profile != null -> {
            val profile = requireNotNull(state.profile)
            OrganizerStatus(profile.status, profile.displayName, profile.rejectionReason,
                onApplyAgain = vm::openForm, onEditName = { vm.openNameEditor(profile.displayName) })
            TextButton(onClick = vm::load) { Text(stringResource(Res.string.organizer_refresh)) }
        }
        else -> Unit
    }
}

/** Status of an organizer profile; `rejected` shows the reason and "apply again" (POST /organizers/apply moves it back to pending). */
@Composable
fun OrganizerStatus(status: String, displayName: String?, rejectionReason: String?, onApplyAgain: () -> Unit, onEditName: () -> Unit) {
    Text(stringResource(when (status) {
        "approved" -> Res.string.organizer_approved
        "rejected" -> Res.string.organizer_rejected
        else -> Res.string.organizer_pending
    }))
    if (status == "rejected") {
        rejectionReason?.takeIf { it.isNotBlank() }?.let { Text(stringResource(Res.string.organizer_rejection_reason, it)) }
        Button(onClick = onApplyAgain) { Text(stringResource(Res.string.organizer_apply_again)) }
    } else {
        Text(stringResource(Res.string.organizer_display_name_value, displayName?.takeIf { it.isNotBlank() } ?: "—"))
        TextButton(onClick = onEditName) { Text(stringResource(Res.string.organizer_display_name_edit)) }
    }
}

@Composable
fun OrganizerApplicationPage(vm: OrganizerViewModel, onLogin: () -> Unit) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TextButton(onClick = vm::closeForm, enabled = !state.loading) { Text(stringResource(Res.string.organizer_back)) }
        Text(stringResource(if (state.editName) Res.string.organizer_display_name_edit else Res.string.organizer_apply), style = MaterialTheme.typography.headlineLarge)
        if (!state.editName) Text(stringResource(Res.string.organizer_application_info))
        OutlinedTextField(state.displayName, vm::updateName, singleLine = true, enabled = !state.loading, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.organizer_display_name)) },
            supportingText = { Text(stringResource(Res.string.organizer_display_name_hint)) })
        state.nameError?.let {
            Text(when (it) {
                "length" -> stringResource(Res.string.organizer_display_name_length)
                "unknown" -> stringResource(Res.string.organizer_error)
                else -> it
            }, color = MaterialTheme.colorScheme.error)
        }
        if (state.nameSaved) Text(stringResource(Res.string.organizer_display_name_saved))
        if (state.error) OrganizerContent(vm, onLogin)
        else if (state.loading) CircularProgressIndicator()
        else if (state.editName) Button(onClick = vm::saveName) { Text(stringResource(Res.string.organizer_display_name_save)) }
        else Button(onClick = vm::apply) { Text(stringResource(Res.string.organizer_submit)) }
    }
}
