package com.example.biletflow.presentation.organizer

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import biletflow.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.datetime.*

@Composable
fun CreateEventPage(vm: CreateEventViewModel, onCheckList: () -> Unit) {
    val state by vm.state.collectAsState()
    val labels = mapOf("title" to Res.string.create_title, "description" to Res.string.create_description,
        "category" to Res.string.create_category, "venue_name" to Res.string.create_venue,
        "venue_address" to Res.string.create_address, "capacity" to Res.string.create_capacity,
        "refund_policy" to Res.string.create_refund, "start_time" to Res.string.create_start,
        "end_time" to Res.string.create_end, "registration_opens_at" to Res.string.create_opens,
        "registration_closes_at" to Res.string.create_closes)
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = vm::close, enabled = !state.saving) { Text(stringResource(Res.string.create_back)) }
        Text(stringResource(if (state.editingId == null) Res.string.create_event else Res.string.event_edit), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(Res.string.event_offsets))
        (listOf("title") + eventDateFields.take(2) + eventTextFields.drop(1) + eventDateFields.drop(2)).forEach { key ->
            val error = state.errors[key]
            val label = stringResource(labels.getValue(key))
            if (key in eventDateFields) {
                EventDateInput(label, state.values[key].orEmpty(), !state.saving) { vm.update(key, it) }
            } else OutlinedTextField(
                value = state.values[key].orEmpty(), onValueChange = { vm.update(key, it) },
                label = { Text(label) }, modifier = Modifier.fillMaxWidth(), enabled = !state.saving,
                isError = error != null, singleLine = key !in listOf("description", "refund_policy"),
            )
            if (error != null) Text(stringResource(when(error) {
                "required" -> Res.string.create_required
                "date" -> Res.string.create_date_error
                "order" -> Res.string.create_order_error
                "number" -> Res.string.create_number_error
                "length" -> Res.string.create_length_error
                else -> Res.string.create_invalid
            }), color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(Res.string.create_visibility))
        listOf("public" to Res.string.my_events_public, "unlisted" to Res.string.my_events_unlisted,
            "private" to Res.string.my_events_private).forEach { (value, label) ->
            FilterChip(selected = state.values["visibility"] == value, onClick = { vm.update("visibility", value) },
                enabled = !state.saving, label = { Text(stringResource(label)) })
        }
        if (state.failed) Text(stringResource(if (state.unknown) Res.string.create_unknown else Res.string.create_failed), color = MaterialTheme.colorScheme.error)
        if (state.unknown) Button(onClick = if (state.editingId == null) onCheckList else vm::checkActual, enabled = !state.saving) { Text(stringResource(Res.string.create_check_list)) }
        else Button(onClick = vm::save, enabled = !state.saving) {
            Text(stringResource(if (state.editingId == null) Res.string.create_save else Res.string.event_save_changes))
        }
        if (state.saving) CircularProgressIndicator()
        if (state.editingId != null && !state.unknown && state.original != state.values) {
            Text(stringResource(Res.string.event_compare))
            state.original.filter { (key, value) -> value != state.values[key] }.forEach { (key, value) ->
                Text("${stringResource(labels[key] ?: Res.string.create_visibility)}: $value → ${state.values[key].orEmpty()}")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventDateInput(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var date by remember(value) { mutableStateOf(value.substringBefore('T').takeIf { it.isNotEmpty() }.orEmpty()) }
    var time by remember(value) { mutableStateOf(value.substringAfter('T', "10:00").take(5)) }
    val offset = Regex("(Z|[+-]\\d{2}:\\d{2})$").find(value)?.value ?: "+05:00"
    Text(label)
    Text("UTC${if (offset == "Z") "+00:00" else offset}")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showDate = true }, enabled = enabled) { Text(date.ifEmpty { "YYYY-MM-DD" }) }
        OutlinedButton(onClick = { showTime = true }, enabled = enabled) { Text(time) }
        TextButton(onClick = { onChange("") }, enabled = enabled) { Text("×") }
    }
    if (showDate) {
        val picker = rememberDatePickerState()
        DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let {
                    date = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date.toString()
                    onChange("${date}T${time}:00$offset")
                }; showDate = false
            }) { Text("OK") }
        }) { DatePicker(picker) }
    }
    if (showTime) {
        val picker = rememberTimePickerState(initialHour = time.substringBefore(':').toIntOrNull() ?: 10,
            initialMinute = time.substringAfter(':').toIntOrNull() ?: 0, is24Hour = true)
        AlertDialog(onDismissRequest = { showTime = false }, confirmButton = {
            TextButton(onClick = {
                time = "${picker.hour.toString().padStart(2,'0')}:${picker.minute.toString().padStart(2,'0')}"
                if (date.isNotEmpty()) onChange("${date}T${time}:00$offset")
                showTime = false
            }) { Text("OK") }
        }, text = { TimeInput(picker) })
    }
}
