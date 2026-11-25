package com.example.biletflow.presentation.organizer

import com.example.biletflow.core.designsystem.TextButton
import com.example.biletflow.core.designsystem.Button
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import biletflow.shared.generated.resources.*
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

internal fun validateOrganizerRequirement(kind: String, fields: Map<String, String>): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    fun length(key: String, range: IntRange) {
        if (fields[key].orEmpty().trim().length !in range) errors[key] = "length"
    }
    if (kind == "identity_verified") {
        length("legal_name", 2..200)
        // The reference gives examples, but no phone/IBAN grammar. Server validates their format.
        if (fields["contact_phone"].orEmpty().isBlank()) errors["contact_phone"] = "required"
    } else {
        length("holder_name", 2..200)
        length("bank_name", 2..100)
        if (fields["iban"].orEmpty().isBlank()) errors["iban"] = "required"
    }
    return errors
}

internal fun organizerFieldErrors(body: JsonObject?): Map<String, String> {
    val result = mutableMapOf<String, String>()
    (body?.get("fields") as? JsonObject)?.forEach { (key, value) ->
        result[key] = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
    }
    (body?.get("detail") as? JsonArray)?.forEach { item ->
        val error = item as? JsonObject ?: return@forEach
        val key = (error["loc"] as? JsonArray)?.lastOrNull()?.jsonPrimitive?.contentOrNull ?: return@forEach
        result[key] = error["msg"]?.jsonPrimitive?.contentOrNull ?: "invalid"
    }
    return result
}

@Composable
fun OrganizerRequirementPage(vm: PaidSalesViewModel) {
    val state by vm.state.collectAsState()
    val verification = state.requirement == "identity_verified"
    val fields = if (verification) listOf("legal_name", "contact_phone") else listOf("holder_name", "bank_name", "iban")
    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = vm::back, enabled = !state.submitting) { Text(stringResource(Res.string.ticket_create_back)) }
        Text(stringResource(if (verification) Res.string.paid_identity else Res.string.paid_account), style = MaterialTheme.typography.headlineMedium)
        Text(state.title)
        com.example.biletflow.core.designsystem.DemoBanner(stringResource(Res.string.organizer_requirement_simulated))
        fields.forEach { key ->
            val label = when (key) {
                "legal_name" -> Res.string.organizer_legal_name
                "contact_phone" -> Res.string.organizer_contact_phone
                "holder_name" -> Res.string.organizer_holder_name
                "bank_name" -> Res.string.organizer_bank_name
                else -> Res.string.organizer_iban
            }
            OutlinedTextField(
                value = state.fields[key].orEmpty(), onValueChange = { vm.updateField(key, it) },
                label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                enabled = !state.submitting && !state.unknownResult,
                isError = key in state.fieldErrors,
                supportingText = {
                    state.fieldErrors[key]?.let { error ->
                        Text(when (error) {
                            "length" -> stringResource(if (key == "bank_name") Res.string.organizer_bank_length else Res.string.organizer_name_length)
                            "required" -> stringResource(Res.string.organizer_field_required)
                            else -> error
                        })
                    }
                },
            )
        }
        if (state.submitting) CircularProgressIndicator()
        if (state.unknownResult) Text(stringResource(Res.string.organizer_requirement_unknown))
        else if (state.formError) Text(stringResource(Res.string.organizer_requirement_error), color = MaterialTheme.colorScheme.error)
        state.observedProfile?.let { profile ->
            Text(stringResource(Res.string.organizer_requirement_checked))
            Text("${stringResource(Res.string.paid_identity)}: ${stringResource(if (profile.identityVerifiedAt != null) Res.string.paid_done else Res.string.paid_required)}")
            profile.legalName?.let { Text(it) }
            profile.contactPhone?.let { Text(it) }
            Text("${stringResource(Res.string.paid_account)}: ${stringResource(if (profile.payoutAccount != null) Res.string.paid_done else Res.string.paid_required)}")
            profile.payoutAccount?.let { account -> Text("${account.holderName} · ${account.bankName} · •••• ${account.ibanLast4}") }
        }
        if (state.unknownResult || state.observedProfile != null) {
            TextButton(onClick = vm::checkRequirement, enabled = !state.submitting) { Text(stringResource(Res.string.payment_check_status)) }
        }
        Button(onClick = vm::submitRequirement, enabled = !state.submitting && !state.unknownResult) {
            Text(stringResource(Res.string.organizer_requirement_submit))
        }
    }
}
