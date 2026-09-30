package com.example.biletflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.biletflow.data.repositories.AuthRepositoryImpl
import com.example.biletflow.presentation.auth.AuthPage
import com.example.biletflow.presentation.auth.AuthViewModel
import com.example.biletflow.presentation.auth.LoginPage
import com.example.biletflow.presentation.auth.RegistrationPage
import com.example.biletflow.presentation.eventdetails.EventDetailsPage
import com.example.biletflow.presentation.events.EventCatalogPage

private const val AUTH_ENABLED = false

@Composable
fun App() {
    MaterialTheme {
        var showEventDetails by remember { mutableStateOf(false) }

        if (!AUTH_ENABLED) {
            if (showEventDetails) {
                EventDetailsPage(onBack = { showEventDetails = false })
            } else {
                EventCatalogPage(onOpenEvent = { showEventDetails = true })
            }
        } else {
            val authViewModel: AuthViewModel = viewModel {
                AuthViewModel(AuthRepositoryImpl())
            }
            val page by authViewModel.page.collectAsState()
            val authState by authViewModel.state.collectAsState()

            if (authState.isSuccess) {
                if (showEventDetails) {
                    EventDetailsPage(onBack = { showEventDetails = false })
                } else {
                    EventCatalogPage(onOpenEvent = { showEventDetails = true })
                }
            } else {
                when (page) {
                    AuthPage.Login -> LoginPage(
                        viewModel = authViewModel,
                        onOpenRegistration = authViewModel::openRegistration,
                    )
                    AuthPage.Registration -> RegistrationPage(
                        viewModel = authViewModel,
                        onOpenLogin = authViewModel::openLogin,
                    )
                }
            }
        }
    }
}
