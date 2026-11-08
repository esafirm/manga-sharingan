package com.esafirm.sharingan.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.esafirm.sharingan.data.ReadStatus

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6B64),
    onPrimary = Color(0xFF3B0406),
    primaryContainer = Color(0xFF6E1219),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFE6BDB8),
    onSecondary = Color(0xFF442926),
    secondaryContainer = Color(0xFF5D3F3C),
    onSecondaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFF130E10),
    onBackground = Color(0xFFEDE0DE),
    surface = Color(0xFF130E10),
    onSurface = Color(0xFFEDE0DE),
    surfaceVariant = Color(0xFF2B2124),
    onSurfaceVariant = Color(0xFFD7C1BE),
    outline = Color(0xFF9F8C89),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC62828),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410004),
    secondary = Color(0xFF775653),
    secondaryContainer = Color(0xFFFFDAD6),
    onSecondaryContainer = Color(0xFF2C1513),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    outline = Color(0xFF857370),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun SharinganTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}

val ReadingAmber = Color(0xFFFFB454)
val CompletedGreen = Color(0xFF5BC27A)

@Composable
fun ReadStatus.color(): Color = when (this) {
    ReadStatus.UNREAD -> MaterialTheme.colorScheme.outline
    ReadStatus.READING -> ReadingAmber
    ReadStatus.COMPLETED -> CompletedGreen
}
