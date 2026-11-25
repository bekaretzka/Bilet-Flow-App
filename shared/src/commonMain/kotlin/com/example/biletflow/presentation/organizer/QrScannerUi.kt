package com.example.biletflow.presentation.organizer

import com.example.biletflow.core.designsystem.*
import com.example.biletflow.presentation.events.formatEventDate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Figma «Сканер / Камера» and the result screens: dark camera with a brand/stub frame and live stats,
 * then a full-screen verdict: green «Проходите», yellow «Уже отмечен» / «Это не билет», red «Не пропускать».
 */
@Composable
fun QrScannerPage(vm: CheckInOverviewViewModel) {
    val state by vm.state.collectAsState()
    LifecycleResumeEffect(vm) {
        vm.scannerForeground(true)
        onPauseOrDispose { vm.scannerForeground(false) }
    }
    if (state.checkInResult != null || state.campaignQr) { ScanVerdict(vm, state); return }
    val paused = state.checkingIn || state.checkInError || state.unknownCode != null || state.verifiedAfterUnknown
    Column(Modifier.fillMaxSize().background(BF.sidebar)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::closeScanner, colors = ButtonDefaults.textButtonColors(contentColor = BF.onDark)) { Text("←", fontSize = 22.sp) }
            Column(Modifier.weight(1f)) {
                Text(state.event?.title.orEmpty(), color = BF.onDark, fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (state.event?.status == "cancelled") Text(stringResource(Res.string.staff_events_cancelled), color = BF.stub, fontSize = 13.sp)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(BF.sidebarSoft), contentAlignment = Alignment.Center) {
            if (state.scannerForeground && !paused) key(state.scannerEpoch) {
                val epoch = state.scannerEpoch
                CameraQrPreview(Modifier.fillMaxSize()) { vm.scannerFrame(epoch, it) }
                Box(Modifier.size(240.dp).border(3.dp, BF.stub, BF.radiusCard))
            }
            if (paused) Surface(Modifier.fillMaxWidth().padding(16.dp), shape = BF.radiusCard, color = BF.surface, contentColor = BF.ink) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.checkingIn) CircularProgressIndicator()
                    if (state.checkInError) Text(stringResource(Res.string.check_action_error), color = BF.dangerFg)
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
                        state.tickets.forEach {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("#${it.id}"); TicketStatusChip(it.status)
                            }
                        }
                    }
                    if (!state.checkingIn && state.unknownCode == null)
                        Button(onClick = vm::nextScannedTicket, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.camera_next)) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(BF.sidebar).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.camera_aim), color = BF.onDark.copy(alpha = 0.8f), fontSize = 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            state.stats?.let { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(s.checkedIn, stringResource(Res.string.scan_stat_checked), Modifier.weight(1f))
                    StatTile(s.registered, stringResource(Res.string.scan_stat_total), Modifier.weight(1f))
                    StatTile(s.notCheckedIn, stringResource(Res.string.scan_stat_left), Modifier.weight(1f))
                }
            }
            OutlinedButton(onClick = vm::closeScanner, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, BF.onDark),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = BF.onDark)) { Text(stringResource(Res.string.scan_find_manually)) }
        }
    }
}

@Composable
private fun StatTile(value: Int, label: String, modifier: Modifier) {
    Surface(modifier, shape = BF.radiusControl, color = BF.sidebarSoft) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value.toString(), color = BF.onDark, fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(label, color = BF.onDark.copy(alpha = 0.75f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun ScanVerdict(vm: CheckInOverviewViewModel, state: CheckInOverviewState) {
    val result = state.checkInResult?.result
    val (tone, icon, title) = when {
        state.campaignQr -> Triple(Tone.Warn, "!", Res.string.scan_title_not_ticket)
        result == "checked_in" -> Triple(Tone.Ok, "✓", Res.string.scan_title_pass)
        result == "already_checked_in" -> Triple(Tone.Warn, "!", Res.string.scan_title_already)
        else -> Triple(Tone.Danger, "✕", Res.string.scan_title_stop)
    }
    val ticket = state.checkInResult?.ticket
    Column(Modifier.fillMaxSize().background(BF.paper).verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().background(tone.bg).statusBarsPadding().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(72.dp).background(tone.fg, CircleShape), contentAlignment = Alignment.Center) {
                Text(icon, color = BF.onDark, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(title), color = tone.fg, fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, textAlign = TextAlign.Center)
            Text(stringResource(if (state.campaignQr) Res.string.scan_campaign_qr else checkInResultLabel(result.orEmpty())),
                color = tone.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
        Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ticket != null) SectionCard {
                VerdictRow(stringResource(Res.string.ticket_card_attendee), ticket.attendeeName.orEmpty())
                ticket.ticketTypeName?.let { VerdictRow(stringResource(Res.string.scan_ticket_type), it) }
                VerdictRow(stringResource(Res.string.ticket_card_type), ticket.code)
                VerdictRow(stringResource(Res.string.ticket_qr_status_label), ticketStatusLabel(ticket.status))
                ticket.checkedInAt?.let { VerdictRow(stringResource(Res.string.check_checked_at), formatEventDate(it)) }
                ticket.checkedInBy?.let { VerdictRow(stringResource(Res.string.scan_checked_by), it) }
            }
            Button(onClick = vm::nextScannedTicket, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.camera_next)) }
            OutlinedButton(onClick = vm::closeScanner, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.scan_find_manually)) }
        }
    }
}

@Composable
private fun VerdictRow(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(label, color = BF.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
    Text(value, color = BF.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
}
