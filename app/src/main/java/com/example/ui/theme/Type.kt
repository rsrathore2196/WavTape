package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private val DefaultLineHeightStyle = LineHeightStyle(
  alignment = LineHeightStyle.Alignment.Center,
  trim = LineHeightStyle.Trim.None
)

// Default Material typography styles used when System Font is selected.
val Typography =
  Typography(
    bodyLarge =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
      )
  )

private fun TextStyle.adaptedFor(fontStyle: AppFontStyle): TextStyle {
  val scaledLineHeight = if (lineHeight != TextUnit.Unspecified && fontStyle.lineHeightScale != 1.0f) {
    (lineHeight.value * fontStyle.lineHeightScale).sp
  } else {
    lineHeight
  }
  return copy(
    fontFamily = fontStyle.fontFamily,
    lineHeight = scaledLineHeight,
    lineHeightStyle = DefaultLineHeightStyle
  )
}

/**
 * Builds a complete Material 3 [Typography] adapted to the selected [AppFontStyle].
 * Returns the baseline [Typography] untouched when [AppFontStyle.SYSTEM] is active.
 */
fun buildAppTypography(fontStyle: AppFontStyle): Typography {
  if (fontStyle == AppFontStyle.SYSTEM) return Typography
  val base = Typography
  return Typography(
    displayLarge = base.displayLarge.adaptedFor(fontStyle),
    displayMedium = base.displayMedium.adaptedFor(fontStyle),
    displaySmall = base.displaySmall.adaptedFor(fontStyle),
    headlineLarge = base.headlineLarge.adaptedFor(fontStyle),
    headlineMedium = base.headlineMedium.adaptedFor(fontStyle),
    headlineSmall = base.headlineSmall.adaptedFor(fontStyle),
    titleLarge = base.titleLarge.adaptedFor(fontStyle),
    titleMedium = base.titleMedium.adaptedFor(fontStyle),
    titleSmall = base.titleSmall.adaptedFor(fontStyle),
    bodyLarge = base.bodyLarge.adaptedFor(fontStyle),
    bodyMedium = base.bodyMedium.adaptedFor(fontStyle),
    bodySmall = base.bodySmall.adaptedFor(fontStyle),
    labelLarge = base.labelLarge.adaptedFor(fontStyle),
    labelMedium = base.labelMedium.adaptedFor(fontStyle),
    labelSmall = base.labelSmall.adaptedFor(fontStyle)
  )
}
