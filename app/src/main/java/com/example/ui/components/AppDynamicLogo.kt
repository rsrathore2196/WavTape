package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.LocalAppColors

internal const val WAVTAPE_WORDMARK_ASPECT_RATIO = 1231f / 270f

/** The supplied WavTape mark, with its original colors and transparent negative space. */
@Composable
@Suppress("UNUSED_PARAMETER") // Retain the existing call-site API; brand artwork is never recolored.
fun AppDynamicLogo(
    modifier: Modifier = Modifier.size(42.dp),
    primaryAccent: Color = LocalAppColors.current.primaryAccent,
    secondaryAccent: Color = LocalAppColors.current.secondaryAccent,
    cutoutColor: Color = LocalAppColors.current.scaffoldBackground,
    contentDescriptionText: String = "WavTape Logo",
    showGlow: Boolean = true
) {
    BoxWithConstraints(modifier.semantics { contentDescription = contentDescriptionText }) {
        // Small chrome avoids decoding the large onboarding/about asset.
        val asset = if (maxWidth <= 80.dp && maxHeight <= 80.dp) R.drawable.wavtape_logo_small else R.drawable.wavtape_logo
        Image(painterResource(asset), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** The supplied WavTape wordmark, with its original gradient colors and transparent background. */
@Composable
fun AppBrandWordmark(
    modifier: Modifier = Modifier,
    contentDescriptionText: String = "WavTape"
) {
    Image(
        painter = painterResource(R.drawable.wavtape_wordmark),
        contentDescription = contentDescriptionText,
        modifier = modifier.aspectRatio(WAVTAPE_WORDMARK_ASPECT_RATIO),
        contentScale = ContentScale.Fit
    )
}
