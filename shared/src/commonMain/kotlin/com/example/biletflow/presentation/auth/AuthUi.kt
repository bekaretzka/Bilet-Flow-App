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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

private val PageBackground = Color(0xFFF7F4EC)
private val Ink = Color(0xFF17171B)
private val Muted = Color(0xFF6F6C72)
private val Purple = Color(0xFF4035D6)
private val Error = Color(0xFFD24D45)
private val FieldBorder = Color(0xFFD9D5CC)

enum class AuthPage { Login, Registration }

@Composable
fun LoginPage(viewModel: AuthViewModel, onOpenRegistration: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val form by viewModel.form.collectAsState()

    AuthLayout {
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
        TextButton(onClick = { }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(stringResource(Res.string.auth_forgot_password), color = Purple, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        AuthFeedback(state)
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = stringResource(Res.string.auth_login_button),
            enabled = !state.isLoading,
            onClick = viewModel::login,
        )
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = onOpenRegistration, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.auth_no_account), color = Purple, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
        FormLabel(stringResource(Res.string.auth_name_label))
        FormField(form.name, viewModel::updateName, stringResource(Res.string.auth_name_placeholder))
        Spacer(Modifier.height(12.dp))
        FormLabel(stringResource(Res.string.auth_email_label))
        FormField(form.email, viewModel::updateEmail, stringResource(Res.string.auth_email_placeholder))
        Text(stringResource(Res.string.auth_email_confirmation_hint), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
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
            fontSize = 11.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onOpenLogin, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.auth_have_account), color = Purple, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
    Text(stringResource(Res.string.brand_name), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, color = Ink, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun FormLabel(text: String) {
    Text(text, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
        placeholder = { Text(placeholder, color = Muted, fontSize = 13.sp) },
        textStyle = TextStyle(color = Ink, fontSize = 14.sp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(9.dp),
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
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(9.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
    ) {
        if (enabled) {
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
    state.errorMessage?.let { message ->
        Text(
            text = message.ifBlank { stringResource(Res.string.auth_request_failed) },
            color = Error,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    validationMessage?.let { message ->
        Text(
            text = message,
            color = Error,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (state.isSuccess) {
        Text(
            text = stringResource(Res.string.auth_request_success),
            color = Purple,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private class PreviewAuthRepository : AuthRepository {
    override suspend fun login(email: String, password: String) = Unit
    override suspend fun register(email: String, password: String) = Unit
}

@Composable
@Preview(showBackground = true)
private fun LoginPreview() {
    MaterialTheme { LoginPage(viewModel = AuthViewModel(PreviewAuthRepository()), onOpenRegistration = {}) }
}

@Composable
@Preview(showBackground = true)
private fun RegistrationPreview() {
    MaterialTheme { RegistrationPage(viewModel = AuthViewModel(PreviewAuthRepository()), onOpenLogin = {}) }
}
