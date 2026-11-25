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
        modifier = Modifier.fillMaxSize().background(com.example.biletflow.core.designsystem.BF.paper)
            .statusBarsPadding().padding(horizontal = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹", color = com.example.biletflow.core.designsystem.BF.ink, fontSize = 32.sp) }
            Text("BiletFlow", color = com.example.biletflow.core.designsystem.BF.ink, fontSize = 21.sp, fontWeight = FontWeight.Bold, fontFamily = com.example.biletflow.core.designsystem.HeadingFont)
        }
        Spacer(Modifier.height(20.dp))
        EventContextCaption(ticket.eventId,eventsRepository)
        Text(stringResource(Res.string.ticket_qr_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = com.example.biletflow.core.designsystem.HeadingFont)
        Spacer(Modifier.height(8.dp))
        Text(
            typeName ?: stringResource(Res.string.order_ticket_number, ticket.id),
            color = com.example.biletflow.core.designsystem.BF.muted,
            fontSize = 17.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(ticket.attendeeName, color = com.example.biletflow.core.designsystem.BF.muted, fontSize = 15.sp)
        Spacer(Modifier.height(24.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                when {
                    state.ticketId != ticket.id || state.isLoading ->
                        CircularProgressIndicator(color = com.example.biletflow.core.designsystem.BF.primary)
                    state.pngBytes != null -> {
                        val image = remember(state.pngBytes) {
                            runCatching { requireNotNull(state.pngBytes).decodeToImageBitmap() }.getOrNull()
                        }
                        if (image != null) {
                            Image(
                                bitmap = image,
                                contentDescription = stringResource(Res.string.ticket_qr_image_description),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Text(stringResource(Res.string.ticket_qr_load_error), color = com.example.biletflow.core.designsystem.BF.dangerFg)
                        }
                    }
                    state.error == TicketQrError.NotFound ->
                        Text(stringResource(Res.string.ticket_qr_unavailable), color = com.example.biletflow.core.designsystem.BF.muted)
                    else -> Text(stringResource(Res.string.ticket_qr_load_error), color = com.example.biletflow.core.designsystem.BF.dangerFg)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        when (state.error) {
            TicketQrError.Unauthorized -> TicketQrAction(stringResource(Res.string.payment_login_again), onLogin)
            TicketQrError.Network -> TicketQrAction(
                stringResource(Res.string.events_retry),
                { viewModel.load(ticket.id) },
            )
            else -> Unit
        }
        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(Res.string.ticket_qr_status_label), color = com.example.biletflow.core.designsystem.BF.muted)
            Text(ticketStatus(ticket.status), color = com.example.biletflow.core.designsystem.BF.primary, fontWeight = FontWeight.Bold)
        }
        if (ticket.status.lowercase() !in listOf("cancelled", "refunded")) {
            Spacer(Modifier.height(12.dp))
            com.example.biletflow.presentation.documents.DocumentButton(stringResource(Res.string.pdf_ticket),
                "/tickets/${ticket.id}/pdf", "ticket-${ticket.id}.pdf")
        }
        onSupport?.let { TextButton(onClick = it) { Text(stringResource(Res.string.support_from_ticket), color = com.example.biletflow.core.designsystem.BF.primary) } }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TicketQrAction(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
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
