package com.example.biletflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.biletflow.data.repositories.AuthRepositoryImpl
import com.example.biletflow.presentation.auth.AuthPage
import com.example.biletflow.presentation.auth.AuthViewModel
import com.example.biletflow.presentation.auth.LoginPage
import com.example.biletflow.presentation.auth.RegistrationPage

@Composable
fun App() {
    MaterialTheme {
        val authViewModel: AuthViewModel = viewModel {
            AuthViewModel(AuthRepositoryImpl())
        }
        val page by authViewModel.page.collectAsState()

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
