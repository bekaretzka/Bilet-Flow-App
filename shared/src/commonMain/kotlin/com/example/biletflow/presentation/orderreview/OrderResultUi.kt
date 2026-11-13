package com.example.biletflow.presentation.orderreview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import biletflow.shared.generated.resources.Res
import biletflow.shared.generated.resources.order_result_back_to_events
import biletflow.shared.generated.resources.order_result_confirmed
import biletflow.shared.generated.resources.order_result_expires
import biletflow.shared.generated.resources.order_result_number
import biletflow.shared.generated.resources.order_result_pending
import biletflow.shared.generated.resources.order_result_status
import biletflow.shared.generated.resources.order_result_title
import biletflow.shared.generated.resources.order_result_total
import biletflow.shared.generated.resources.ticket_price_free
import biletflow.shared.generated.resources.ticket_price_kzt
import com.example.biletflow.domain.models.Order
import org.jetbrains.compose.resources.stringResource

private val PageBackground = Color(0xFFF7F4EC)
private val Ink = Color(0xFF17171B)
private val Muted = Color(0xFF6F6C72)
private val Purple = Color(0xFF4035D6)

@Composable
fun OrderResultPage(order: Order, onDone: () -> Unit) {
    val confirmed = order.status.equals("confirmed", ignoreCase = true)
    val statusText = if (confirmed) {
        stringResource(Res.string.order_result_confirmed)
    } else if (order.status.equals("pending", ignoreCase = true)) {
        stringResource(Res.string.order_result_pending)
    } else {
        order.status
    }

    Column(
        modifier = Modifier.fillMaxSize().background(PageBackground).statusBarsPadding().padding(24.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text(stringResource(Res.string.order_result_title), color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                ResultRow(stringResource(Res.string.order_result_number), order.id.toString())
                Spacer(Modifier.height(14.dp))
                ResultRow(stringResource(Res.string.order_result_status), statusText)
                Spacer(Modifier.height(14.dp))
                ResultRow(stringResource(Res.string.order_result_total), formatOrderPrice(order.totalAmount))
                if (!confirmed && order.expiresAt != null) {
                    Spacer(Modifier.height(14.dp))
                    ResultRow(stringResource(Res.string.order_result_expires), order.expiresAt)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
        ) {
            Text(stringResource(Res.string.order_result_back_to_events), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, fontSize = 15.sp)
        Text(value, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun formatOrderPrice(value: Long): String = if (value == 0L) {
    stringResource(Res.string.ticket_price_free)
} else {
    stringResource(
        Res.string.ticket_price_kzt,
        value.toString().reversed().chunked(3).joinToString(" ").reversed(),
    )
}
