package com.example.biletflow.presentation.eventdetails

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import biletflow.shared.generated.resources.*

private val PageBackground = Color(0xFFF7F4EC)
private val Ink = Color(0xFF17171B)
private val Muted = Color(0xFF6F6C72)
private val Purple = Color(0xFF4035D6)
private val Error = Color(0xFFB42B24)
private val FieldBorder = Color(0xFFD9D5CC)
private val Cover = Color(0xFFDDD7C7)

@Composable
fun EventDetailsPage(onBack: () -> Unit) {
    var standardCount by remember { mutableIntStateOf(2) }
    val total = standardCount * 4_000

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .statusBarsPadding(),
            ) {
                EventHeader(onBack)
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().height(190.dp).background(Cover, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(Res.string.event_details_cover_placeholder), color = Muted, fontSize = 16.sp)
                }
                Spacer(Modifier.height(18.dp))
                Tag(stringResource(Res.string.event_details_category))
                Spacer(Modifier.height(14.dp))
                Text(stringResource(Res.string.event_details_title), color = Ink, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(22.dp))
                EventInfo(icon = "▣", title = stringResource(Res.string.event_details_date), subtitle = stringResource(Res.string.event_details_timezone))
                Spacer(Modifier.height(16.dp))
                EventInfo(icon = "⌖", title = stringResource(Res.string.event_details_venue), subtitle = stringResource(Res.string.event_details_address))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.event_add_to_calendar), color = Purple, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFE9E7FF),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(Res.string.event_promo),
                        color = Color(0xFF2F279D),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
                Spacer(Modifier.height(26.dp))
                Text(stringResource(Res.string.event_tickets), color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                TicketOption(
                    title = stringResource(Res.string.ticket_standard),
                    price = stringResource(Res.string.ticket_standard_price),
                    oldPrice = stringResource(Res.string.ticket_standard_old_price),
                    availability = stringResource(Res.string.ticket_standard_left),
                    count = standardCount,
                    selected = true,
                    onMinus = { if (standardCount > 0) standardCount-- },
                    onPlus = { standardCount++ },
                )
                Spacer(Modifier.height(12.dp))
                TicketOption(
                    title = stringResource(Res.string.ticket_vip),
                    price = stringResource(Res.string.ticket_vip_price),
                    availability = stringResource(Res.string.ticket_vip_left),
                    count = 0,
                    selected = false,
                    onMinus = {},
                    onPlus = {},
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = PageBackground,
                    border = BorderStroke(1.dp, Color(0xFFCFC8B7)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(stringResource(Res.string.ticket_free), color = Muted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(5.dp))
                            Text(stringResource(Res.string.ticket_registration_closed), color = Muted, fontSize = 13.sp)
                        }
                        Text(stringResource(Res.string.ticket_unavailable), color = Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(26.dp))
                Text(stringResource(Res.string.event_about), color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.event_description), color = Muted, fontSize = 16.sp, lineHeight = 23.sp)
                Spacer(Modifier.height(14.dp))
                Text(stringResource(Res.string.event_support), color = Purple, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(110.dp))
            }
            CheckoutBar(total = total)
        }
    }
}

@Composable
private fun EventHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text("‹", color = Ink, fontSize = 32.sp, modifier = Modifier.padding(end = 8.dp))
        }
        Text("BiletFlow", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            border = BorderStroke(1.dp, FieldBorder),
        ) {
            Text(stringResource(Res.string.language_ru), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
        }
    }
}

@Composable
private fun EventInfo(icon: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(icon, color = Purple, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
        Column {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(shape = RoundedCornerShape(9.dp), color = Color.White, border = BorderStroke(1.dp, FieldBorder)) {
        Text(text, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun TicketOption(
    title: String,
    price: String,
    oldPrice: String? = null,
    availability: String,
    count: Int,
    selected: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Purple else FieldBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(price, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    oldPrice?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(it, color = Muted, fontSize = 13.sp, textDecoration = TextDecoration.LineThrough)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(availability, color = if (availability.contains("3")) Error else Muted, fontSize = 13.sp)
            }
            CounterButton("−", enabled = count > 0, onClick = onMinus)
            Text(count.toString(), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
            CounterButton("+", enabled = true, filled = selected, onClick = onPlus)
        }
    }
}

@Composable
private fun CounterButton(text: String, enabled: Boolean, filled: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(52.dp).height(52.dp),
        shape = RoundedCornerShape(15.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) Purple else Color.White,
            contentColor = if (filled) Color.White else Purple,
            disabledContainerColor = PageBackground,
            disabledContentColor = Muted,
        ),
        border = if (!filled) BorderStroke(1.dp, if (enabled) Purple else FieldBorder) else null,
    ) { Text(text, fontSize = 24.sp) }
}

@Composable
private fun CheckoutBar(total: Int) {
    Surface(
        color = Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier.navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(stringResource(Res.string.event_selected_tickets), color = Muted, fontSize = 14.sp)
                Text("${total.toString().reversed().chunked(3).joinToString(" ").reversed()} ₸", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {},
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
                modifier = Modifier.height(54.dp),
            ) { Text(stringResource(Res.string.event_checkout), fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
@Preview(showBackground = true)
private fun EventDetailsPreview() {
    MaterialTheme { EventDetailsPage(onBack = {}) }
}
