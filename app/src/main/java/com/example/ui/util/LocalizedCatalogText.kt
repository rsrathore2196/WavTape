package com.example.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.example.data.local.AppLanguage
import com.example.data.local.CatalogDisplayText

@Composable
internal fun localizedCatalogText(value: String): String {
    val locales = LocalConfiguration.current.locales
    return remember(value, locales) { CatalogDisplayText.inLanguage(value, AppLanguage.resolve("", locales)) }
}
