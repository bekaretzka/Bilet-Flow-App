package com.example.biletflow.presentation.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import biletflow.shared.generated.resources.*

private val PageBackground = Color(0xFFF7F4EC)
private val Ink = Color(0xFF17171B)
private val Muted = Color(0xFF6F6C72)
private val Purple = Color(0xFF4035D6)
private val FieldBorder = Color(0xFFD9D5CC)
private val Cover = Color(0xFFDDD7C7)

private data class CatalogEvent(
    val category: String,
    val tag: String? = null,
    val title: String,
    val date: String,
    val venue: String,
    val price: String,
)

@Composable
fun EventCatalogPage(onOpenEvent: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(0) }
    val filters = listOf(
        stringResource(Res.string.events_filter_all),
        stringResource(Res.string.events_filter_concerts),
        stringResource(Res.string.events_filter_lectures),
        stringResource(Res.string.events_filter_sport),
    )
    val events = listOf(
        CatalogEvent(
            category = stringResource(Res.string.event_a_category),
            title = stringResource(Res.string.event_a_title),
            date = stringResource(Res.string.event_a_date),
            venue = stringResource(Res.string.event_a_venue),
            price = stringResource(Res.string.event_a_price),
        ),
        CatalogEvent(
            category = stringResource(Res.string.event_b_category),
            tag = stringResource(Res.string.event_b_tag),
            title = stringResource(Res.string.event_b_title),
            date = stringResource(Res.string.event_b_date),
            venue = stringResource(Res.string.event_a_venue),
            price = stringResource(Res.string.event_b_price),
        ),
        CatalogEvent(
            category = stringResource(Res.string.event_c_category),
            tag = stringResource(Res.string.event_c_tag),
            title = stringResource(Res.string.event_c_title),
            date = stringResource(Res.string.event_c_date),
            venue = stringResource(Res.string.event_a_venue),
            price = stringResource(Res.string.event_c_price),
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("BiletFlow", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, FieldBorder),
            ) {
                Text(
                    text = stringResource(Res.string.language_ru),
                    color = Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(Res.string.events_search_placeholder), color = Muted, fontSize = 15.sp) },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = FieldBorder,
                focusedBorderColor = Purple,
                unfocusedContainerColor = Color.White,
                focusedContainerColor = Color.White,
            ),
        )
        Spacer(Modifier.height(22.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            filters.forEachIndexed { index, filter ->
                FilterChip(filter, selected = selectedFilter == index) { selectedFilter = index }
            }
        }
        Spacer(Modifier.height(22.dp))
        events.forEach { event ->
            EventCard(event = event, onClick = onOpenEvent)
            Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.border(
            width = 1.dp,
            color = if (selected) Ink else FieldBorder,
            shape = RoundedCornerShape(28.dp),
        ),
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            containerColor = if (selected) Ink else Color.White,
            contentColor = if (selected) Color.White else Ink,
        ),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EventCard(event: CatalogEvent, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, FieldBorder),
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().height(190.dp).background(Cover),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(Res.string.event_cover_placeholder), color = Muted, fontSize = 16.sp)
            }
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(event.category)
                    event.tag?.let { Tag(it, accent = true) }
                }
                Spacer(Modifier.height(10.dp))
                Text(event.title, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(event.date, color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text(event.venue, color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(event.price, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("${stringResource(Res.string.event_more)} →", color = Purple, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, accent: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (accent) Color(0xFFE9E7FF) else PageBackground,
    ) {
        Text(
            text = text,
            color = if (accent) Purple else Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
@Preview(showBackground = true)
private fun EventCatalogPreview() {
    MaterialTheme { EventCatalogPage(onOpenEvent = {}) }
}
