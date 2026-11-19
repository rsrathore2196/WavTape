package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.LocalAppColors

internal const val WAVTAPE_WORDMARK_ASPECT_RATIO = 1231f / 270f

// Deepens bright cyan highlights on light surfaces while preserving the cyan-to-blue gradient.
private val LightSurfaceBrandColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.12f, 0f, 0f, 0f, 0f,
            0f, 0.56f, 0f, 0f, 0f,
            0f, 0f, 0.78f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

private val LightSurfaceShadowColorFilter = ColorFilter.tint(
    Color(0xFF041430).copy(alpha = 0.18f),
    BlendMode.SrcIn
)

/** The supplied WavTape mark, with its original colors and transparent negative space. */
@Composable
@Suppress("UNUSED_PARAMETER") // Retain the existing call-site API; brand artwork is never recolored.
fun AppDynamicLogo(
    modifier: Modifier = Modifier.size(42.dp),
    primaryAccent: Color = LocalAppColors.current.primaryAccent,
    secondaryAccent: Color = LocalAppColors.current.secondaryAccent,
    cutoutColor: Color = LocalAppColors.current.scaffoldBackground,
    contentDescriptionText: String = "WavTape Logo",
    showGlow: Boolean = true,
    adaptForLightSurface: Boolean = false
) {
    BoxWithConstraints(modifier.semantics { contentDescription = contentDescriptionText }) {
        // Small chrome avoids decoding the large onboarding/about asset.
        val asset = if (maxWidth <= 80.dp && maxHeight <= 80.dp) R.drawable.wavtape_logo_small else R.drawable.wavtape_logo
        val painter = painterResource(asset)
        if (adaptForLightSurface) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().offset(y = 0.75.dp),
                contentScale = ContentScale.Fit,
                colorFilter = LightSurfaceShadowColorFilter
            )
        }
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            colorFilter = if (adaptForLightSurface) LightSurfaceBrandColorFilter else null
        )
    }
}

/** The supplied WavTape wordmark, with its original gradient colors and transparent background. */
@Composable
fun AppBrandWordmark(
    modifier: Modifier = Modifier,
    contentDescriptionText: String = "WavTape",
    adaptForLightSurface: Boolean = false
) {
    val painter = painterResource(R.drawable.wavtape_wordmark)
    val sizedModifier = modifier.aspectRatio(WAVTAPE_WORDMARK_ASPECT_RATIO)
    if (!adaptForLightSurface) {
        Image(
            painter = painter,
            contentDescription = contentDescriptionText,
            modifier = sizedModifier,
            contentScale = ContentScale.Fit
        )
    } else {
        Box(sizedModifier.semantics { contentDescription = contentDescriptionText }) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().offset(y = 0.75.dp),
                contentScale = ContentScale.Fit,
                colorFilter = LightSurfaceShadowColorFilter
            )
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                colorFilter = LightSurfaceBrandColorFilter
            )
        }
    }
}
