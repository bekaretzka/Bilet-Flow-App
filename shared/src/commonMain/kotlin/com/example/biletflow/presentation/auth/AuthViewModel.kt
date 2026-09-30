package com.example.biletflow.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biletflow.domain.repositories.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AuthValidationError {
    EmptyEmail,
    InvalidEmail,
    EmptyPassword,
    ShortPassword,
    PasswordMismatch,
}

data class AuthFormState(
    val email: String = "",
    val password: String = "",
    val name: String = "",
    val confirmPassword: String = "",
)

data class AuthUiState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null,
    val validationError: AuthValidationError? = null,
)

class AuthViewModel(
    private val repository: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()
    private val _form = MutableStateFlow(AuthFormState())
    val form: StateFlow<AuthFormState> = _form.asStateFlow()
    private val _page = MutableStateFlow(AuthPage.Login)
    val page: StateFlow<AuthPage> = _page.asStateFlow()

    fun updateEmail(value: String) {
        _form.value = _form.value.copy(email = value)
        clearRequestState()
    }

    fun updatePassword(value: String) {
        _form.value = _form.value.copy(password = value)
        clearRequestState()
    }

    fun updateName(value: String) {
        _form.value = _form.value.copy(name = value)
        clearRequestState()
    }

    fun updateConfirmPassword(value: String) {
        _form.value = _form.value.copy(confirmPassword = value)
        clearRequestState()
    }

    fun login() {
        validateLogin()?.let { setValidationError(it); return }
        val form = _form.value
        submit { repository.login(form.email.trim(), form.password) }
    }

    fun register() {
        validateRegistration()?.let { setValidationError(it); return }
        val form = _form.value
        submit { repository.register(form.email.trim(), form.password) }
    }

    fun openRegistration() {
        _page.value = AuthPage.Registration
        clearRequestState()
    }

    fun openLogin() {
        _page.value = AuthPage.Login
        clearRequestState()
    }

    fun clearState() {
        _state.value = AuthUiState()
    }

    private fun clearRequestState() {
        if (_state.value.isLoading) return
        _state.value = AuthUiState()
    }

    private fun setValidationError(error: AuthValidationError) {
        _state.value = AuthUiState(validationError = error)
    }

    private fun validateLogin(): AuthValidationError? = validateCredentials()

    private fun validateRegistration(): AuthValidationError? {
        validateCredentials()?.let { return it }
        if (_form.value.password != _form.value.confirmPassword) return AuthValidationError.PasswordMismatch
        return null
    }

    private fun validateCredentials(): AuthValidationError? {
        val form = _form.value
        if (form.email.isBlank()) return AuthValidationError.EmptyEmail
        if (!form.email.contains("@") || !form.email.contains(".")) return AuthValidationError.InvalidEmail
        if (form.password.isBlank()) return AuthValidationError.EmptyPassword
        if (form.password.length < 8) return AuthValidationError.ShortPassword
        return null
    }

    private fun submit(request: suspend () -> Unit) {
        if (_state.value.isLoading) return

        viewModelScope.launch {
            _state.value = AuthUiState(isLoading = true)
            runCatching { request() }
                .onSuccess {
                    _state.value = AuthUiState(isSuccess = true)
                }
                .onFailure { error ->
                    _state.value = AuthUiState(errorMessage = error.message)
                }
        }
    }
}
