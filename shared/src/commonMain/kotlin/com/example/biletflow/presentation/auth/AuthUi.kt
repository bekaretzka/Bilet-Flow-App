package com.example.biletflow.presentation.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.example.biletflow.core.designsystem.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import com.example.biletflow.core.designsystem.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import biletflow.shared.generated.resources.*
import com.example.biletflow.domain.repositories.AuthRepository
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore

private val PageBackground = com.example.biletflow.core.designsystem.BF.paper
private val Ink = com.example.biletflow.core.designsystem.BF.ink
private val Muted = com.example.biletflow.core.designsystem.BF.muted
private val Purple = com.example.biletflow.core.designsystem.BF.primary
private val Error = com.example.biletflow.core.designsystem.BF.dangerFg
private val FieldBorder = com.example.biletflow.core.designsystem.BF.lineField

enum class AuthPage { Login, Registration }

@Composable
fun LoginPage(
    viewModel: AuthViewModel,
    onClose: () -> Unit,
    onLoginSuccess: () -> Unit,
    onOpenRegistration: (() -> Unit)? = null,
    onResetLink: (() -> Unit)? = null,
) {
    val page by viewModel.page.collectAsState()
    if (page == AuthPage.Registration) {
        RegistrationPage(viewModel, viewModel::openLogin)
        return
    }
    val state by viewModel.state.collectAsState()
    val form by viewModel.form.collectAsState()
    LaunchedEffect(state.isSuccess) {
        if (state.isSuccess) onLoginSuccess()
    }

    AuthLayout {
        Box(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = onClose,
                enabled = !state.isLoading,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Text(stringResource(Res.string.auth_close), color = Purple, fontWeight = FontWeight.SemiBold)
            }
        }
        Brand()
        PageTitle(
            title = stringResource(Res.string.auth_login_title),
            subtitle = stringResource(Res.string.auth_login_subtitle),
        )
        FormLabel(stringResource(Res.string.auth_email_label))
        FormField(form.email, viewModel::updateEmail, stringResource(Res.string.auth_email_placeholder))
        Spacer(Modifier.height(12.dp))
        FormLabel(stringResource(Res.string.auth_password_label))
        FormField(form.password, viewModel::updatePassword, stringResource(Res.string.auth_password_placeholder), password = true)
        val forgot by viewModel.forgot.collectAsState()
        TextButton(onClick = viewModel::forgotPassword, enabled = !forgot.sending && !state.isLoading, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(stringResource(Res.string.auth_forgot_password), color = Purple, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        when {
            forgot.sending -> Text(stringResource(Res.string.auth_forgot_sending), fontSize = 13.sp)
            forgot.invalidEmail -> Text(stringResource(Res.string.auth_forgot_enter_email), color = com.example.biletflow.core.designsystem.BF.dangerFg, fontSize = 13.sp)
            forgot.failed -> Text(stringResource(Res.string.auth_forgot_failed), color = com.example.biletflow.core.designsystem.BF.dangerFg, fontSize = 13.sp)
            forgot.message != null -> Text(forgot.message?.ifBlank { null } ?: stringResource(Res.string.auth_forgot_sent), fontSize = 13.sp)
        }
        if (forgot.message != null && onResetLink != null) TextButton(onClick = onResetLink, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(stringResource(Res.string.reset_password_have_link), color = Purple, fontSize = 14.sp)
        }
        AuthFeedback(state)
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = stringResource(Res.string.auth_login_button),
            enabled = !state.isLoading,
            onClick = viewModel::login,
        )
        (onOpenRegistration ?: viewModel::openRegistration).let { openRegistration ->
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = openRegistration, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.auth_no_account), color = Purple, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun RegistrationPage(viewModel: AuthViewModel, onOpenLogin: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val form by viewModel.form.collectAsState()

    AuthLayout {
        Brand()
        PageTitle(
            title = stringResource(Res.string.auth_registration_title),
            subtitle = stringResource(Res.string.auth_registration_subtitle),
        )
        FormLabel(stringResource(Res.string.auth_email_label))
        FormField(form.email, viewModel::updateEmail, stringResource(Res.string.auth_email_placeholder))
        Text(stringResource(Res.string.auth_email_hint_registration), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        FormLabel(stringResource(Res.string.auth_password_label))
        FormField(form.password, viewModel::updatePassword, stringResource(Res.string.auth_password_registration_placeholder), password = true)
        Spacer(Modifier.height(12.dp))
        FormLabel(stringResource(Res.string.auth_confirm_password_label))
        FormField(form.confirmPassword, viewModel::updateConfirmPassword, stringResource(Res.string.auth_confirm_password_placeholder), password = true)
        AuthFeedback(state)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = stringResource(Res.string.auth_registration_button),
            enabled = !state.isLoading,
            onClick = viewModel::register,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(Res.string.auth_terms),
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onOpenLogin, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.auth_have_account), color = Purple, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AuthLayout(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) { content() }
    }
}

@Composable
private fun Brand() {
    Text(stringResource(Res.string.brand_name), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = com.example.biletflow.core.designsystem.HeadingFont)
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, color = Ink, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, fontFamily = com.example.biletflow.core.designsystem.HeadingFont)
    Spacer(Modifier.height(6.dp))
    Text(subtitle, color = com.example.biletflow.core.designsystem.BF.ink2, fontSize = 15.sp, lineHeight = 21.sp)
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun FormLabel(text: String) {
    Text(text, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, color = Muted, fontSize = 15.sp) },
        textStyle = TextStyle(color = Ink, fontSize = 15.sp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Purple,
            unfocusedBorderColor = FieldBorder,
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            cursorColor = Purple,
        ),
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
    ) {
        if (enabled) {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        } else {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.height(18.dp),
            )
        }
    }
}

@Composable
private fun AuthFeedback(state: AuthUiState) {
    val validationMessage = state.validationError?.let { error ->
        when (error) {
            AuthValidationError.EmptyEmail -> stringResource(Res.string.auth_error_email_required)
            AuthValidationError.InvalidEmail -> stringResource(Res.string.auth_error_email_invalid)
            AuthValidationError.EmptyPassword -> stringResource(Res.string.auth_error_password_required)
            AuthValidationError.ShortPassword -> stringResource(Res.string.auth_error_password_short)
            AuthValidationError.PasswordMismatch -> stringResource(Res.string.auth_error_password_mismatch)
        }
    }
    state.requestError?.let { error ->
        Text(
            text = when (error) {
                AuthRequestError.InvalidCredentials -> stringResource(Res.string.auth_error_invalid_credentials)
                AuthRequestError.Suspended -> stringResource(Res.string.session_suspended)
                AuthRequestError.Network -> stringResource(Res.string.auth_error_network)
                AuthRequestError.ExistingEmail -> stringResource(Res.string.auth_error_existing_email)
                AuthRequestError.Fields -> stringResource(Res.string.auth_error_fields)
                AuthRequestError.Server -> stringResource(Res.string.auth_request_failed)
            },
            color = Error,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    validationMessage?.let { message ->
        Text(
            text = message,
            color = Error,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (state.accountCreated) {
        Text(stringResource(Res.string.auth_account_created), color = Purple, modifier = Modifier.padding(top = 8.dp))
    }
    if (state.isSuccess) {
        Text(
            text = stringResource(Res.string.auth_request_success),
            color = Purple,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private class PreviewAuthRepository : AuthRepository {
    override suspend fun login(email: String, password: String) = AuthTokens("access", "refresh", "bearer")
    override suspend fun register(email: String, password: String) = Unit
}

@Composable
@Preview(showBackground = true)
private fun LoginPreview() {
    MaterialTheme {
        LoginPage(
            viewModel = AuthViewModel(PreviewAuthRepository(), SessionStore()),
            onClose = {},
            onLoginSuccess = {},
        )
    }
}

@Composable
@Preview(showBackground = true)
private fun RegistrationPreview() {
    MaterialTheme {
        RegistrationPage(
            viewModel = AuthViewModel(PreviewAuthRepository(), SessionStore()),
            onOpenLogin = {},
        )
    }
}
