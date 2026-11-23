package com.example.biletflow.presentation.organizer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import biletflow.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Returns every decoded QR in a frame, including empty frames. Disposal must stop/release capture. */
@Composable
expect fun CameraQrPreview(modifier: Modifier, onFrame: (List<String>) -> Unit)

/** A QR must be absent for a sustained interval before the same code can be accepted again. */
class QrFrameGate(private val absenceMillis: Long = 800) {
    private val blocked = linkedMapOf<String, Long?>()
    fun resume() { blocked.keys.toList().forEach { blocked[it] = null } }
    fun accept(codes: List<String>, nowMillis: Long): String? {
        blocked.keys.toList().forEach { previous ->
            if (previous in codes) blocked[previous] = null
            else {
                val start = blocked[previous] ?: nowMillis.also { blocked[previous] = it }
                if (nowMillis - start >= absenceMillis) blocked.remove(previous)
            }
        }
        val code = codes.firstOrNull { !blocked.containsKey(it) } ?: return null
        blocked[code] = null
        return code
    }
}

// Platform status: 0 preview, 1 permission denied, 2 settings required, 3 no camera, 4 startup failure.
@Composable
fun CameraStatusPanel(status: Int, onRequest: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(when (status) {
            1 -> Res.string.camera_denied
            2 -> Res.string.camera_blocked
            3 -> Res.string.camera_missing
            else -> Res.string.camera_error
        }))
        if (status == 2) Button(onClick = onSettings) { Text(stringResource(Res.string.camera_settings)) }
        if (status == 1 || status == 4) Button(onClick = onRequest) { Text(stringResource(Res.string.events_retry)) }
    }
}

@Composable
fun QrScannerPage(vm: CheckInOverviewViewModel) {
    val state by vm.state.collectAsState()
    LifecycleResumeEffect(vm) {
        vm.scannerForeground(true)
        onPauseOrDispose { vm.scannerForeground(false) }
    }
    val paused = state.checkingIn || state.checkInResult != null || state.checkInError || state.unknownCode != null || state.verifiedAfterUnknown
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = vm::closeScanner) { Text(stringResource(Res.string.ticket_create_back)) }
        Text(state.event?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
        if (state.event?.status == "cancelled") Text(stringResource(Res.string.staff_events_cancelled))
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            if (state.scannerForeground && !paused) key(state.scannerEpoch) {
                val epoch = state.scannerEpoch
                CameraQrPreview(Modifier.fillMaxSize()) { vm.scannerFrame(epoch, it) }
                Box(Modifier.size(240.dp).border(3.dp, Color.White))
            }
            if (paused) Surface(Modifier.fillMaxWidth().padding(12.dp), tonalElevation = 4.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.checkingIn) CircularProgressIndicator()
                    state.checkInResult?.let { result ->
                        Text(if (result.result == "checked_in") "✓" else "!", style = MaterialTheme.typography.displayMedium,
                            color = if (result.result == "checked_in") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Text(stringResource(checkInResultLabel(result.result)), style = MaterialTheme.typography.titleLarge)
                        result.ticket?.let { Text("${it.attendeeName.orEmpty()} · ${it.ticketTypeName.orEmpty()} · #${it.id}") }
                    }
                    if (state.checkInError) Text(stringResource(Res.string.check_action_error))
                    if (state.unknownCode != null) {
                        Text(stringResource(Res.string.check_action_unknown))
                        Button(onClick = vm::verifyUnknown, enabled = !state.searching) { Text(stringResource(Res.string.payment_check_status)) }
                        if (state.searching) CircularProgressIndicator()
                        if (state.searchError) Text(stringResource(Res.string.check_search_error))
                        if (state.invalidQuery) Text(stringResource(Res.string.check_query_length))
                        if (state.searched && state.tickets.isEmpty()) Text(stringResource(Res.string.check_search_empty))
                    }
                    if (state.verifiedAfterUnknown) {
                        Text(stringResource(Res.string.check_verified_notice))
                        state.tickets.forEach { Text("#${it.id} · ${it.status}") }
                    }
                    if (!state.checkingIn && state.unknownCode == null) Button(onClick = vm::nextScannedTicket) { Text(stringResource(Res.string.camera_next)) }
                }
            }
        }
        Text(stringResource(Res.string.camera_aim))
    }
}
