package com.example.biletflow.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import biletflow.shared.generated.resources.*
import org.jetbrains.compose.resources.Font
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Figma «BiletFlow · дизайн-система» (style «Корешок»), variable collection «BiletFlow tokens».
 * White text only on [primary], [sidebar] and [ink]; [muted] text is 13 sp minimum (contrast AA).
 */
object BF {
    // background and surfaces
    val paper = Color(0xFFF6F3EC)
    val surface = Color(0xFFFFFFFF)
    val sidebar = Color(0xFF17171C)
    val sidebarSoft = Color(0xFF26262D)
    val line = Color(0xFFE3DED2)
    val lineSoft = Color(0xFFEFEBE2)
    val lineField = Color(0xFFCFC8B8)
    // text
    val ink = Color(0xFF17171C)
    val ink2 = Color(0xFF4A4A55)
    val muted = Color(0xFF6B6B76)
    val onDark = Color(0xFFFFFFFF)
    // brand
    val primary = Color(0xFF3B2FD0)
    val primaryPress = Color(0xFF2A2199)
    val primarySoft = Color(0xFFECEAFE)
    val stub = Color(0xFFF2B233)
    // statuses
    val okBg = Color(0xFFE3F4EA)
    val okFg = Color(0xFF14663C)
    val infoBg = Color(0xFFE4EEFB)
    val infoFg = Color(0xFF1A4E9C)
    val dangerBg = Color(0xFFFDE8E6)
    val dangerFg = Color(0xFF9E1F14)
    val neutralBg = Color(0xFFEDEDEF)
    val neutralFg = Color(0xFF45454F)
    val warnBg = Color(0xFFFFF1CC)
    val warnLine = Color(0xFFF0D48A)
    val warnFg = Color(0xFF6B4100)
    /** Event cover placeholder (no token in Figma; a darker paper tone). */
    val cover = Color(0xFFDDD7C7)

    // radii: 8 small elements, 12 buttons and fields, 16 cards, 20 ticket
    val radiusSmall = RoundedCornerShape(8.dp)
    val radiusControl = RoundedCornerShape(12.dp)
    val radiusCard = RoundedCornerShape(16.dp)
    val radiusTicket = RoundedCornerShape(20.dp)
    /** Buttons and fields 44-56 dp; 48 is the Figma component height. */
    val controlHeight = 48.dp
}

/** Headings: Unbounded (Medium, Bold); body: Golos Text (Regular, Medium, SemiBold). OFL fonts from Google Fonts in composeResources/font. */
val HeadingFont: FontFamily
    @Composable get() = FontFamily(Font(Res.font.unbounded_medium, FontWeight.Medium), Font(Res.font.unbounded_bold, FontWeight.Bold))
val BodyFont: FontFamily
    @Composable get() = FontFamily(Font(Res.font.golos_text_regular, FontWeight.Normal), Font(Res.font.golos_text_medium, FontWeight.Medium),
        Font(Res.font.golos_text_semibold, FontWeight.SemiBold))

private val colors = lightColorScheme(
    primary = BF.primary, onPrimary = BF.onDark,
    primaryContainer = BF.primarySoft, onPrimaryContainer = BF.primaryPress,
    inversePrimary = BF.primarySoft,
    secondary = BF.ink, onSecondary = BF.onDark,
    secondaryContainer = BF.primarySoft, onSecondaryContainer = BF.primaryPress,
    tertiary = BF.stub, onTertiary = BF.ink,
    tertiaryContainer = BF.warnBg, onTertiaryContainer = BF.warnFg,
    background = BF.paper, onBackground = BF.ink,
    surface = BF.paper, onSurface = BF.ink,
    surfaceVariant = BF.lineSoft, onSurfaceVariant = BF.muted,
    surfaceTint = BF.primary,
    surfaceBright = BF.surface, surfaceDim = BF.lineSoft,
    surfaceContainerLowest = BF.surface, surfaceContainerLow = BF.surface, surfaceContainer = BF.surface,
    surfaceContainerHigh = BF.surface, surfaceContainerHighest = BF.surface,
    inverseSurface = BF.sidebar, inverseOnSurface = BF.onDark,
    outline = BF.lineField, outlineVariant = BF.line,
    error = BF.dangerFg, onError = BF.onDark,
    errorContainer = BF.dangerBg, onErrorContainer = BF.dangerFg,
    scrim = Color(0x99000000),
)

private fun style(font: FontFamily, weight: FontWeight, size: Int, lineHeight: Double) =
    TextStyle(fontFamily = font, fontWeight = weight, fontSize = size.sp, lineHeight = (size * lineHeight).sp)

private fun typography(HeadingFont: FontFamily, BodyFont: FontFamily) = Typography(
    displayLarge = style(HeadingFont, FontWeight.Bold, 36, 1.2),
    displayMedium = style(HeadingFont, FontWeight.Bold, 32, 1.2),
    displaySmall = style(HeadingFont, FontWeight.Bold, 30, 1.2),
    headlineLarge = style(HeadingFont, FontWeight.Bold, 30, 1.2),   // page title
    headlineMedium = style(HeadingFont, FontWeight.Bold, 26, 1.22), // event title
    headlineSmall = style(HeadingFont, FontWeight.Bold, 22, 1.25),
    titleLarge = style(HeadingFont, FontWeight.Medium, 18, 1.3),    // card title
    titleMedium = style(BodyFont, FontWeight.SemiBold, 16, 1.4),
    titleSmall = style(BodyFont, FontWeight.SemiBold, 14, 1.4),
    bodyLarge = style(BodyFont, FontWeight.Normal, 15, 1.4),        // body and descriptions
    bodyMedium = style(BodyFont, FontWeight.Normal, 15, 1.4),
    bodySmall = style(BodyFont, FontWeight.Normal, 13, 1.4),        // secondary: remaining, hints
    labelLarge = style(BodyFont, FontWeight.Medium, 15, 1.4),       // buttons
    labelMedium = style(BodyFont, FontWeight.Medium, 14, 1.4),      // field labels, statuses
    labelSmall = style(BodyFont, FontWeight.Medium, 13, 1.4),
)

private val shapes = Shapes(
    extraSmall = BF.radiusControl, // outlined text fields
    small = BF.radiusSmall,        // chips, tags
    medium = BF.radiusCard,        // cards
    large = BF.radiusCard,
    extraLarge = BF.radiusTicket,  // dialogs, sheets
)

@Composable
fun BiletFlowTheme(content: @Composable () -> Unit) {
    val heading = HeadingFont
    val body = BodyFont
    val typography = remember(heading, body) { typography(heading, body) }
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) {
        Surface(color = BF.paper, contentColor = BF.ink) { content() }
    }
}

// Material buttons are pill-shaped and 40 dp high; the Figma components are 48 dp with radius 12.
// Screens import these explicitly, which takes precedence over androidx.compose.material3.*.

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = BF.radiusControl,
    colors: ButtonColors = ButtonDefaults.buttonColors(disabledContainerColor = BF.line, disabledContentColor = BF.muted),
    border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.Button(onClick, modifier.heightIn(min = BF.controlHeight), enabled, shape, colors, null, border, contentPadding, content = content)

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = BF.radiusControl,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = BF.ink),
    border: BorderStroke? = BorderStroke(1.dp, if (enabled) BF.ink else BF.line),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.OutlinedButton(onClick, modifier.heightIn(min = BF.controlHeight), enabled, shape, colors, null, border, contentPadding, content = content)

@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = BF.radiusControl,
    colors: ButtonColors = ButtonDefaults.textButtonColors(contentColor = BF.primary),
    border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding, content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.TextButton(onClick, modifier, enabled, shape, colors, null, border, contentPadding, content = content)

// Figma cards: white, radius 16, 1 dp line/default border, no shadow.
@Composable
fun Card(modifier: Modifier = Modifier, shape: Shape = BF.radiusCard, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    androidx.compose.material3.Card(modifier, shape, androidx.compose.material3.CardDefaults.cardColors(containerColor = BF.surface, contentColor = BF.ink),
        androidx.compose.material3.CardDefaults.cardElevation(0.dp), BorderStroke(1.dp, BF.line), content)

@Composable
fun Card(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = BF.radiusCard,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    androidx.compose.material3.Card(onClick, modifier, enabled, shape, androidx.compose.material3.CardDefaults.cardColors(containerColor = BF.surface, contentColor = BF.ink),
        androidx.compose.material3.CardDefaults.cardElevation(0.dp), BorderStroke(1.dp, BF.line), content = content)
