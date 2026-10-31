package com.maatv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maatv.data.I18n

// ---- Tokens ----

val Bg = Color(0xFF0B0D12)
val BgTop = Color(0xFF1A1530)
val Surface1 = Color(0xFF151821)
val Surface2 = Color(0xFF1D212C)
val Surface3 = Color(0xFF272C39)
val Outline = Color(0xFF2E3443)
val TextHi = Color(0xFFF5F6F8)
val TextMid = Color(0xFFA6ADBB)
val TextLow = Color(0xFF6E7586)
val Accent = Color(0xFFFF7A45)
val Accent2 = Color(0xFFFF4D8D)
val Good = Color(0xFF3DDC97)
val Warn = Color(0xFFFFC857)
val Bad = Color(0xFFFF6B6B)

val AccentBrush = Brush.linearGradient(listOf(Accent, Accent2))
val ScreenBrush = Brush.verticalGradient(0f to BgTop, 0.45f to Bg, 1f to Bg)

@Composable
fun MaaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondary = Accent2,
            background = Bg,
            onBackground = TextHi,
            surface = Surface1,
            onSurface = TextHi,
            surfaceVariant = Surface2,
            onSurfaceVariant = TextMid,
            surfaceContainerHigh = Surface2,
            outline = Outline,
            error = Bad,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(ScreenBrush),
        ) { content() }
    }
}

/** Shorthand for bilingual text on Mom's screen. */
fun t(en: String, hi: String) = I18n.t(en, hi)

// ---- Components ----

/** Shrinks slightly while pressed, for a tactile feel. */
@Composable
fun Modifier.pressable(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")
    return this.scale(s)
}

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressable(interaction)
            .heightIn(min = height)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) AccentBrush else Brush.linearGradient(listOf(Surface3, Surface3)))
            .clickable(interaction, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, tint = if (enabled) Color.White else TextLow, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (enabled) Color.White else TextLow, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
fun TonalButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressable(interaction)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(Surface3)
            .clickable(interaction, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (enabled) TextHi else TextLow
        icon?.let {
            Icon(it, null, tint = c, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = c, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    }
}

/** A round icon button; [accent] makes it the gradient primary. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    accent: Boolean = false,
    tint: Color = TextHi,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressable(interaction)
            .size(size)
            .clip(CircleShape)
            .background(if (accent) AccentBrush else Brush.linearGradient(listOf(Surface3, Surface3)))
            .clickable(interaction, null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (!enabled) TextLow else if (accent) Color.White else tint, modifier = Modifier.size(size * 0.46f))
    }
}

/** A rounded panel used to group content. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Surface1)
            .border(BorderStroke(1.dp, Outline.copy(alpha = 0.6f)), RoundedCornerShape(28.dp))
            .padding(padding),
    ) { content() }
}

@Composable
fun PanelTitle(title: String, subtitle: String? = null) {
    Text(title, color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    subtitle?.let {
        Spacer(Modifier.size(4.dp))
        Text(it, color = TextMid, fontSize = 14.sp, lineHeight = 20.sp)
    }
    Spacer(Modifier.size(16.dp))
}

/** Small rounded label with an optional colored dot. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, dot: Color? = null, background: Color = Surface2, fontSize: TextUnit = 13.sp) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dot?.let {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(it),
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = TextHi, fontSize = fontSize, fontWeight = FontWeight.Medium)
    }
}

/** The app logo: the launcher icon's TV-with-a-heart on its gradient tile. */
@Composable
fun AppLogo(size: Dp = 44.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(Color(0xFFFF8A3D), Color(0xFFFF5E62), Color(0xFFE8407F)))),
        contentAlignment = Alignment.Center,
    ) {
        // The foreground is drawn for a 108dp canvas with padding; scale it up to fill the tile.
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.maatv.R.drawable.ic_launcher_foreground),
            contentDescription = "Maa TV",
            modifier = Modifier.size(size * 1.55f),
        )
    }
}

/** EN | हिं switch. */
@Composable
fun LanguageToggle(hindi: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, large: Boolean = false) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Surface2)
            .padding(4.dp),
    ) {
        listOf(false to "English", true to "हिंदी").forEach { (isHi, label) ->
            val selected = hindi == isHi
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) AccentBrush else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onChange(isHi) }
                    .padding(horizontal = if (large) 22.dp else 12.dp, vertical = if (large) 10.dp else 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (large) label else if (isHi) "हिं" else "EN",
                    color = if (selected) Color.White else TextMid,
                    fontSize = if (large) 16.sp else 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
