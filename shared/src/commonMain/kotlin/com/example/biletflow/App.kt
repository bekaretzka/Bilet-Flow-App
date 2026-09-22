package com.example.biletflow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PageBackground = Color(0xFFF7F4EC)
private val Ink = Color(0xFF17171B)
private val Muted = Color(0xFF6F6C72)
private val Purple = Color(0xFF4035D6)
private val Error = Color(0xFFD24D45)
private val FieldBorder = Color(0xFFD9D5CC)

private enum class AuthPage { Login, Registration }

@Composable
@Preview(name = "Вход", showBackground = true)
fun App() {
    MaterialTheme {
        var page by remember { mutableStateOf(AuthPage.Login) }
        Surface(modifier = Modifier.fillMaxSize(), color = PageBackground) {
            when (page) {
                AuthPage.Login -> LoginPage { page = AuthPage.Registration }
                AuthPage.Registration -> RegistrationPage { page = AuthPage.Login }
            }
        }
    }
}

@Composable
@Preview(name = "Регистрация", showBackground = true)
private fun RegistrationPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = PageBackground) {
            RegistrationPage(onOpenLogin = {})
        }
    }
}

@Composable
private fun AuthLayout(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeContentPadding()
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
    Text("BiletFlow", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(9.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun LoginPage(onOpenRegistration: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AuthLayout {
        Brand()
        PageTitle("Вход", "Билеты и заказы привязаны к вашей почте.")
        FormLabel("Email")
        FormField(email, { email = it }, "name@example.kz")
        Spacer(Modifier.height(12.dp))
        FormLabel("Пароль")
        FormField(password, { password = it }, "••••••••", password = true)
        TextButton(onClick = { }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text("Забыли пароль?", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton("Войти") { }
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = onOpenRegistration, modifier = Modifier.fillMaxWidth()) {
            Text("Нет аккаунта? Зарегистрироваться", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RegistrationPage(onOpenLogin: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AuthLayout {
        Brand()
        PageTitle("Регистрация", "После регистрации подтвердите почту — без этого билеты не приходят.")
        FormLabel("Имя и фамилия")
        FormField(name, { name = it }, "Будет указано на билете")
        Spacer(Modifier.height(12.dp))
        FormLabel("Email")
        FormField(email, { email = it }, "name@example.kz")
        Text("Сюда придёт письмо для подтверждения", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        Spacer(Modifier.height(12.dp))
        FormLabel("Пароль")
        FormField(password, { password = it }, "Минимум 8 символов", password = true)
        Text("Пароль слишком короткий", color = Error, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Создать аккаунт") { }
        Spacer(Modifier.height(12.dp))
        Text(
            "Регистрируясь, вы соглашаетесь с условиями платформы",
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onOpenLogin, modifier = Modifier.fillMaxWidth()) {
            Text("Уже есть аккаунт? Войти", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
