package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.*
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OrganizerRequirementsTest {
    @Test fun savesRequirementsAcrossEventsAndIsolatesAccounts() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient(publishedFixtureEvents = true)
        var verificationRequests = 0
        var accountRequests = 0
        var holdVerification: CompletableDeferred<Unit>? = null
        client.plugin(HttpSend).intercept { request ->
            if (request.url.build().encodedPath.endsWith("/verification")) {
                verificationRequests++
                holdVerification?.await()
            }
            if (request.url.build().encodedPath.endsWith("/payout-account")) accountRequests++
            execute(request)
        }
        val session = SessionStore().apply { save(AuthTokens("mock-access-token", "mock-refresh-token", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val organizer = OrganizerViewModel(client, requests, session, "http://test")
        val create = CreateEventViewModel(client, requests, session, "http://test")
        val vm = PaidSalesViewModel(client, requests, session, "http://test")
        try {
            runCurrent()
            organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            val ids = (1..2).map { index ->
                create.open(); create.update("title", "Requirement event $index")
                create.update("start_time", "2027-02-01T10:00:00+05:00")
                create.update("end_time", "2027-02-01T12:00:00+05:00")
                create.save(); create.state.first { !it.saving }
                requireNotNull(create.state.value.created).id.also { create.reset() }
            }
            vm.open(ids[0], "First"); vm.state.first { !it.loading }
            vm.openRequirement("identity_verified"); vm.submitRequirement()
            assertTrue("legal_name" in vm.state.value.fieldErrors)
            assertEquals(0, verificationRequests)
            vm.updateField("legal_name", " Test Organizer "); vm.updateField("contact_phone", "field-error")
            vm.submitRequirement(); vm.state.first { !it.submitting }
            assertTrue("contact_phone" in vm.state.value.fieldErrors)
            assertEquals(" Test Organizer ", vm.state.value.fields["legal_name"])
            vm.updateField("contact_phone", "+7 701 123 45 67")
            session.rotateTokens(AuthTokens("expired-access", "mock-refresh-token", "bearer"))
            val before = verificationRequests
            vm.submitRequirement(); vm.submitRequirement(); vm.state.first { !it.submitting && !it.loading }
            assertEquals(before + 2, verificationRequests) // One explicit 401, one replay, no duplicate submit.
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access-"))
            assertNull(vm.state.value.requirement)
            assertTrue(vm.state.value.data!!.checklist.getValue("identity_verified"))
            vm.open(ids[1], "Second"); vm.state.first { !it.loading }
            assertTrue(vm.state.value.data!!.checklist.getValue("identity_verified"))
            assertFalse(vm.state.value.data!!.checklist.getValue("payout_account_connected"))
            vm.openRequirement("payout_account_connected")
            vm.updateField("holder_name", "Test Organizer"); vm.updateField("bank_name", "Test Bank")
            vm.updateField("iban", "field-error")
            vm.submitRequirement(); vm.state.first { !it.submitting }
            assertTrue("iban" in vm.state.value.fieldErrors)
            vm.updateField("iban", "KZ86 125K ZT50 0410 0100")
            val previousAccounts = accountRequests
            vm.submitRequirement(); vm.submitRequirement(); vm.state.first { !it.submitting && !it.loading }
            assertEquals(previousAccounts + 1, accountRequests)
            assertTrue(vm.state.value.data!!.checklist.getValue("payout_account_connected"))
            vm.open(ids[0], "First"); vm.state.first { !it.loading }
            assertTrue(vm.state.value.data!!.checklist.getValue("payout_account_connected"))
            organizer.load(); organizer.state.first { !it.loading }
            assertEquals("Test Organizer", organizer.state.value.profile?.legalName)
            assertEquals("0100", organizer.state.value.profile?.payoutAccount?.ibanLast4)

            // Simulate a response lost after the server saved the account.
            vm.openRequirement("payout_account_connected")
            vm.updateField("holder_name", "unknown-result"); vm.updateField("bank_name", "Test Bank")
            vm.updateField("iban", "KZ86 125K ZT50 0410 0100")
            vm.submitRequirement(); vm.state.first { !it.submitting }
            assertTrue(vm.state.value.unknownResult)
            val afterUnknown = accountRequests
            vm.submitRequirement(); runCurrent(); assertEquals(afterUnknown, accountRequests)
            vm.checkRequirement(); vm.state.first { !it.submitting }
            assertFalse(vm.state.value.unknownResult)
            assertEquals("unknown-result", vm.state.value.observedProfile?.payoutAccount?.holderName)
            vm.back(); vm.state.first { !it.loading }

            // A transport failure before saving preserves the form and requires a status check.
            vm.openRequirement("identity_verified")
            vm.updateField("legal_name", "network-error"); vm.updateField("contact_phone", "+7 701 123 45 67")
            vm.submitRequirement(); vm.state.first { !it.submitting }
            assertTrue(vm.state.value.unknownResult)
            assertEquals("network-error", vm.state.value.fields["legal_name"])
            vm.checkRequirement(); vm.state.first { !it.submitting }
            assertEquals("Test Organizer", vm.state.value.observedProfile?.legalName)

            holdVerification = CompletableDeferred()
            vm.updateField("legal_name", "Late old account")
            vm.submitRequirement(); runCurrent()
            assertTrue(vm.state.value.submitting)
            session.save(AuthTokens("mock-empty-access-token", "mock-empty-refresh-token", "bearer")); runCurrent()
            holdVerification!!.complete(Unit); runCurrent()
            assertNull(vm.state.value.eventId); assertTrue(vm.state.value.fields.isEmpty())
            organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            assertNull(organizer.state.value.profile?.payoutAccount)
            assertNull(organizer.state.value.profile?.identityVerifiedAt)
            vm.open(ids[0], "Foreign"); vm.state.first { !it.loading }; assertTrue(vm.state.value.denied)
        } finally {
            vm.viewModelScope.cancel(); organizer.viewModelScope.cancel(); create.viewModelScope.cancel()
            runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }
}
