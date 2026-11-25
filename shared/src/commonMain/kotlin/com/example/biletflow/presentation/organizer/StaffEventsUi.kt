package com.example.biletflow.presentation.organizer

import com.example.biletflow.core.designsystem.Card
import com.example.biletflow.core.designsystem.TextButton
import com.example.biletflow.core.designsystem.Button
import com.example.biletflow.core.designsystem.BF
import com.example.biletflow.core.designsystem.BfIcon
import com.example.biletflow.core.designsystem.HeadingFont
import com.example.biletflow.core.designsystem.Tag
import com.example.biletflow.core.designsystem.Tone
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biletflow.shared.generated.resources.*
import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.Event
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.events.formatEventDate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

data class StaffEventsState(
    val open: Boolean = false, val loading: Boolean = false,
    val events: List<Event> = emptyList(), val error: Boolean = false, val accessLost: Boolean = false,
)

class StaffEventsViewModel(
    private val client: HttpClient, private val requests: SessionOrdersRepository,
    private val session: SessionStore, private val baseUrl: String = apiBaseUrl,
) : ViewModel() {
    private val mutable = MutableStateFlow(StaffEventsState())
    val state = mutable.asStateFlow()
    private var sequence = 0
    private var generation = session.generation
    init {
        viewModelScope.launch {
            session.tokens.collect {
                if (generation != session.generation) { generation = session.generation; close() }
            }
        }
    }
    fun open() { sequence++; mutable.value = StaffEventsState(open = true); reload() }
    fun close() { sequence++; mutable.value = StaffEventsState() }
    fun accessLost() {
        sequence++
        mutable.value = mutable.value.copy(accessLost = true, loading = false, events = emptyList())
        reload()
    }
    fun reload() {
        if (!mutable.value.open || mutable.value.loading) return
        val expected = session.generation
        val requestId = ++sequence
        mutable.value = mutable.value.copy(loading = true, error = false, events = emptyList())
        viewModelScope.launch {
            try {
                val events = requests.request { token ->
                    val response = client.get("$baseUrl/staff/events") { bearerAuth(token) }
                    if (response.status.value == 200) response.body<List<Event>>() else {
                        val payload = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        throw OrdersApiException(response.status.value, payload?.get("error")?.jsonPrimitive?.contentOrNull, "Staff events request failed")
                    }
                }
                if (expected == session.generation && requestId == sequence)
                    mutable.value = mutable.value.copy(loading = false, events = events)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || requestId != sequence) return@launch
                mutable.value = mutable.value.copy(loading = false, error = true)
            }
        }
    }
}

/** Figma «Сканер / Выбор события»: cards with a lifecycle tag; the event that is on now is outlined and has «Сканировать». */
@Composable
fun StaffEventsPage(vm: StaffEventsViewModel, onEvent: (Event) -> Unit = {}) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::close) { Text("←", fontSize = 22.sp, color = BF.ink) }
            Text(stringResource(Res.string.staff_events_title), fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                color = BF.ink, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::reload, enabled = !state.loading) { Text(stringResource(Res.string.my_events_refresh)) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.accessLost) Text(stringResource(Res.string.check_access_lost), color = BF.dangerFg)
            when {
                state.loading -> CircularProgressIndicator()
                state.error -> {
                    Text(stringResource(Res.string.staff_events_error))
                    Button(onClick = vm::reload) { Text(stringResource(Res.string.events_retry)) }
                }
                state.events.isEmpty() -> Text(stringResource(Res.string.staff_events_empty), color = BF.muted)
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.events, key = { it.id }) { event ->
                        val live = event.status != "cancelled" && event.lifecycle == "active"
                        Surface(onClick = { onEvent(event) }, modifier = Modifier.fillMaxWidth(), shape = BF.radiusCard, color = BF.surface,
                            border = BorderStroke(if (live) 2.dp else 1.dp, if (live) BF.primary else BF.line)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(event.title, fontFamily = HeadingFont, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = BF.ink, modifier = Modifier.weight(1f))
                                    when {
                                        event.status == "cancelled" -> Tag(stringResource(Res.string.staff_events_cancelled), Tone.Danger)
                                        live -> Tag(stringResource(Res.string.staff_tag_live), Tone.Ok)
                                        event.lifecycle == "completed" -> Tag(stringResource(Res.string.staff_tag_done), Tone.Neutral)
                                        else -> Tag(stringResource(Res.string.staff_tag_later), Tone.Neutral)
                                    }
                                }
                                Text(listOfNotNull(formatEventDate(event.startTime), event.venueName?.takeIf { it.isNotBlank() }).joinToString(" · "),
                                    color = BF.ink2, fontSize = 14.sp)
                                if (live) Button(onClick = { onEvent(event) }, modifier = Modifier.fillMaxWidth()) {
                                    BfIcon(Res.drawable.ic_qr, size = 20.dp); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.camera_scan))
                                }
                            }
                        }
                    }
                    item { Text(stringResource(Res.string.staff_events_note), color = BF.muted, fontSize = 13.sp) }
                }
            }
        }
    }
}
