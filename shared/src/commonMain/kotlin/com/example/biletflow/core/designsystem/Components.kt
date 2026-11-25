package com.example.biletflow.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import biletflow.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Figma «Статусы» pairs: background and foreground. */
enum class Tone(val bg: Color, val fg: Color) {
    Paper(BF.paper, BF.ink2), Neutral(BF.neutralBg, BF.neutralFg), Brand(BF.primarySoft, BF.primary),
    Ok(BF.okBg, BF.okFg), Info(BF.infoBg, BF.infoFg), Danger(BF.dangerBg, BF.dangerFg), Warn(BF.warnBg, BF.warnFg),
}

/** Small tag on cards (category, «Регистрация», «Мало мест»): radius 8, Golos Medium 13. */
@Composable
fun Tag(text: String, tone: Tone = Tone.Paper, modifier: Modifier = Modifier) =
    Surface(modifier, shape = BF.radiusSmall, color = tone.bg, contentColor = tone.fg) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }

/**
 * Ticket status «Статус билета»: colour, icon and text together (WCAG 2.1 AA, SRS 7 — never colour alone).
 * valid ✓ green, checked_in ✓ blue, cancelled ✕ red, refunded ↺ grey.
 */
@Composable
fun TicketStatusChip(status: String, label: String = ticketStatusLabel(status), modifier: Modifier = Modifier) {
    val (tone, icon) = when (status) {
        "valid" -> Tone.Ok to "✓"
        "checked_in" -> Tone.Info to "✓"
        "cancelled" -> Tone.Danger to "✕"
        "refunded" -> Tone.Neutral to "↺"
        else -> Tone.Neutral to "•"
    }
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = tone.bg, contentColor = tone.fg) {
        Text("$icon $label", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
fun ticketStatusLabel(status: String): String = when (status) {
    "valid" -> stringResource(Res.string.attendees_valid)
    "checked_in" -> stringResource(Res.string.attendees_checked_in)
    "cancelled" -> stringResource(Res.string.attendees_cancelled)
    "refunded" -> stringResource(Res.string.attendees_refunded)
    else -> status
}

/** «Плашка / Демо»: simulated payments and refunds. warn-bg, warn-line border, warn-fg text. */
@Composable
fun DemoBanner(text: String, modifier: Modifier = Modifier) =
    Surface(modifier.fillMaxWidth(), shape = BF.radiusControl, color = BF.warnBg, contentColor = BF.warnFg, border = BorderStroke(1.dp, BF.warnLine)) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp))
    }

/** White card with line/default border and radius 16, padding 20 (Figma section cards). */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Surface(modifier.fillMaxWidth(), shape = BF.radiusCard, color = BF.surface, border = BorderStroke(1.dp, BF.line)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
