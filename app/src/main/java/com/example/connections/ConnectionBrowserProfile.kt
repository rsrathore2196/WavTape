package com.example.connections

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Login cookies live in a different browser profile from anonymous audio attestation. */
internal object ConnectionBrowserProfile {
    private const val NAME = "music_account_connections"
    @Volatile private var cookies: CookieManager? = null
    fun isSupported() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    fun attach(view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            WebViewCompat.setProfile(view, NAME)
            cookies = ProfileStore.getInstance().getOrCreateProfile(NAME).cookieManager
        } else throw IllegalStateException("Update Android System WebView to connect an account")
    }
    fun profile(): Profile {
        check(isSupported()) { "Update Android System WebView to connect an account" }
        return ProfileStore.getInstance().getOrCreateProfile(NAME)
    }
    fun cookies(): CookieManager = cookies ?: profile().cookieManager.also { cookies = it }
}
