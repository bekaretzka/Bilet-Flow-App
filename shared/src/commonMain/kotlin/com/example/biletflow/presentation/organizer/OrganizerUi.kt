package com.example.biletflow.presentation.organizer

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
            Text(stringResource(if (state.profile?.status == "approved") Res.string.organizer_approved else Res.string.organizer_pending))
            TextButton(onClick = vm::load) { Text(stringResource(Res.string.organizer_refresh)) }
        }
        else -> Unit
    }
}

@Composable
fun OrganizerApplicationPage(vm: OrganizerViewModel, onLogin: () -> Unit) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TextButton(onClick = vm::closeForm, enabled = !state.loading) { Text(stringResource(Res.string.organizer_back)) }
        Text(stringResource(Res.string.organizer_apply), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(Res.string.organizer_application_info))
        if (state.error) OrganizerContent(vm, onLogin)
        else if (state.loading) CircularProgressIndicator()
        else Button(onClick = vm::apply) { Text(stringResource(Res.string.organizer_submit)) }
    }
}
