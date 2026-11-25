package com.example.biletflow.presentation.orderreview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.biletflow.core.designsystem.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.example.biletflow.core.designsystem.TextButton
import com.example.biletflow.presentation.events.EventContextCaption
import com.example.biletflow.domain.repositories.EventsRepository
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.example.biletflow.core.designsystem.BF
import com.example.biletflow.core.designsystem.HeadingFont
import com.example.biletflow.core.designsystem.OutlinedButton
import com.example.biletflow.core.designsystem.TicketStatusChip
import biletflow.shared.generated.resources.*
import com.example.biletflow.domain.models.Ticket
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource

@Composable
fun TicketQrPage(
    ticket: Ticket,
    typeName: String?,
    viewModel: TicketQrViewModel,
    onBack: () -> Unit,
    eventsRepository: EventsRepository? = null,
    onLogin: () -> Unit,
    onSupport: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(ticket.id) { viewModel.load(ticket.id) }

    Column(
        modifier = Modifier.fillMaxSize().background(BF.paper)
            .statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", color = BF.ink, fontSize = 22.sp) }
            Text(stringResource(Res.string.ticket_qr_title), color = BF.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = HeadingFont)
        }
        Spacer(Modifier.height(12.dp))
        // «Зритель / Билет»: white card, radius 20, brand/stub strip on top, dashed tear line above the QR
        Surface(modifier = Modifier.fillMaxWidth(), shape = BF.radiusTicket, color = BF.surface, border = BorderStroke(1.dp, BF.line)) {
            Column {
                Box(Modifier.fillMaxWidth().height(8.dp).background(BF.stub))
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.weight(1f)) {
                            ProvideTextStyle(TextStyle(fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 27.sp, color = BF.ink)) {
                                EventContextCaption(ticket.eventId, eventsRepository)
                            }
                        }
                        TicketStatusChip(ticket.status.lowercase(), ticketStatus(ticket.status))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TicketField(stringResource(Res.string.ticket_card_type), typeName ?: stringResource(Res.string.order_ticket_number, ticket.id), Modifier.weight(1f))
                        TicketField(stringResource(Res.string.ticket_card_attendee), ticket.attendeeName, Modifier.weight(1f))
                    }
                    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
                        drawLine(BF.lineField, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
                    }
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(16.dp), contentAlignment = Alignment.Center) {
                        when {
                            state.ticketId != ticket.id || state.isLoading -> CircularProgressIndicator(color = BF.primary)
                            state.pngBytes != null -> {
                                val image = remember(state.pngBytes) {
                                    runCatching { requireNotNull(state.pngBytes).decodeToImageBitmap() }.getOrNull()
                                }
                                if (image != null) {
                                    Image(bitmap = image, contentDescription = stringResource(Res.string.ticket_qr_image_description),
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                } else Text(stringResource(Res.string.ticket_qr_load_error), color = BF.dangerFg)
                            }
                            state.error == TicketQrError.NotFound -> Text(stringResource(Res.string.ticket_qr_unavailable), color = BF.muted)
                            else -> Text(stringResource(Res.string.ticket_qr_load_error), color = BF.dangerFg)
                        }
                    }
                    Text(ticket.code, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = BF.ink,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                    Text(stringResource(Res.string.ticket_card_hint), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        color = BF.muted, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        when (state.error) {
            TicketQrError.Unauthorized -> TicketQrAction(stringResource(Res.string.payment_login_again), onLogin)
            TicketQrError.Network -> TicketQrAction(stringResource(Res.string.events_retry)) { viewModel.load(ticket.id) }
            else -> Unit
        }
        if (ticket.status.lowercase() !in listOf("cancelled", "refunded")) {
            com.example.biletflow.presentation.documents.DocumentButton(stringResource(Res.string.pdf_ticket),
                "/tickets/${ticket.id}/pdf", "ticket-${ticket.id}.pdf")
        }
        onSupport?.let {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, BF.line)) {
                Text(stringResource(Res.string.support_from_ticket), color = BF.primary)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TicketField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = BF.muted, fontSize = 13.sp)
        Text(value, color = BF.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TicketQrAction(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = com.example.biletflow.core.designsystem.BF.primary),
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ticketStatus(status: String): String = when (status.lowercase()) {
    "valid" -> stringResource(Res.string.ticket_status_valid)
    "checked_in" -> stringResource(Res.string.ticket_status_checked_in)
    "cancelled" -> stringResource(Res.string.ticket_status_cancelled)
    "refunded" -> stringResource(Res.string.ticket_status_refunded)
    else -> status
}
