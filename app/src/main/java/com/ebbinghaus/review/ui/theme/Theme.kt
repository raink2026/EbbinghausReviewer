package com.ebbinghaus.review.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography as MaterialTypography
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(6.dp)
)

private fun scaledTypography(fontScale: Float, font: AppThemeFont): MaterialTypography = Typography.copy(
    displayLarge = Typography.displayLarge.copy(fontFamily = font.family, fontSize = Typography.displayLarge.fontSize * fontScale),
    displayMedium = Typography.displayMedium.copy(fontFamily = font.family, fontSize = Typography.displayMedium.fontSize * fontScale),
    displaySmall = Typography.displaySmall.copy(fontFamily = font.family, fontSize = Typography.displaySmall.fontSize * fontScale),
    headlineLarge = Typography.headlineLarge.copy(fontFamily = font.family, fontSize = Typography.headlineLarge.fontSize * fontScale),
    headlineMedium = Typography.headlineMedium.copy(fontFamily = font.family, fontSize = Typography.headlineMedium.fontSize * fontScale),
    headlineSmall = Typography.headlineSmall.copy(fontFamily = font.family, fontSize = Typography.headlineSmall.fontSize * fontScale),
    titleLarge = Typography.titleLarge.copy(fontFamily = font.family, fontSize = Typography.titleLarge.fontSize * fontScale),
    titleMedium = Typography.titleMedium.copy(fontFamily = font.family, fontSize = Typography.titleMedium.fontSize * fontScale),
    titleSmall = Typography.titleSmall.copy(fontFamily = font.family, fontSize = Typography.titleSmall.fontSize * fontScale),
    bodyLarge = Typography.bodyLarge.copy(fontFamily = font.family, fontSize = Typography.bodyLarge.fontSize * fontScale),
    bodyMedium = Typography.bodyMedium.copy(fontFamily = font.family, fontSize = Typography.bodyMedium.fontSize * fontScale),
    bodySmall = Typography.bodySmall.copy(fontFamily = font.family, fontSize = Typography.bodySmall.fontSize * fontScale),
    labelLarge = Typography.labelLarge.copy(fontFamily = font.family, fontSize = Typography.labelLarge.fontSize * fontScale),
    labelMedium = Typography.labelMedium.copy(fontFamily = font.family, fontSize = Typography.labelMedium.fontSize * fontScale),
    labelSmall = Typography.labelSmall.copy(fontFamily = font.family, fontSize = Typography.labelSmall.fontSize * fontScale)
)

@Composable
fun EbbinghausReviewTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1.0f,
    themeConfig: AppThemeConfig = AppThemeConfig(),
    content: @Composable () -> Unit
) {
    val resolved = resolveAppTheme(darkTheme, themeConfig)

    MaterialTheme(
        colorScheme = resolved.colorScheme,
        typography = scaledTypography(fontScale, resolved.font),
        shapes = AppShapes,
        content = content
    )
}
