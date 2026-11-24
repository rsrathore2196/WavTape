package com.example.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.local.AppLanguage
import com.example.performance.AppDispatchers
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.bouncyClickable
import com.example.ui.theme.liquidGlassCard
import java.util.Locale
import kotlinx.coroutines.withContext

@Composable
internal fun AppLanguageSetting() {
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    var selected by remember(context) { mutableStateOf(AppLanguage.selection(context)) }
    var open by remember { mutableStateOf(false) }
    val colors = LocalAppColors.current
    val systemDefault = stringResource(R.string.app_language_system)
    val title = stringResource(R.string.app_language)
    val selectedName = remember(selected, systemDefault) {
        if (selected.isEmpty()) systemDefault else AppLanguage.nativeName(selected)
    }
    Row(Modifier.fillMaxWidth().liquidGlassCard(colors, shape = RoundedCornerShape(22.dp),
        elevation = 6.dp, translucency = .82f, tintAccent = true)
        .bouncyClickable { open = true }.padding(16.dp).testTag("app_language_setting"),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Language, null, tint = colors.primaryAccent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(selectedName, color = colors.textSecondary,
                style = MaterialTheme.typography.bodySmall)
        }
        Text("›", color = colors.textMuted, style = MaterialTheme.typography.titleLarge)
    }
    if (open) {
        var query by remember { mutableStateOf("") }
        val displayLocale = LocalConfiguration.current.locales[0] ?: Locale.ENGLISH
        val options by produceState(emptyList<AppLanguage.Option>(), displayLocale) {
            value = withContext(AppDispatchers.CpuBounded) { AppLanguage.options(displayLocale) }
        }
        val search = remember(query) { AppLanguage.searchKey(query) }
        val matches = remember(options, search) { options.filter { it.matches(search) } }
        val showSystem = search.isEmpty() || search in AppLanguage.searchKey(systemDefault) || search in "system default"
        fun choose(tag: String) {
            open = false
            activity?.let { AppLanguage.set(it, tag); selected = tag }
        }
        Dialog(
            onDismissRequest = { open = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { open = false }) },
                contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 380.dp).fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                    shape = RoundedCornerShape(26.dp), color = colors.cardBackground, shadowElevation = 16.dp) {
                    Column(Modifier.heightIn(max = 470.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().testTag("app_language_search"),
                            placeholder = { Text(stringResource(R.string.app_language_search)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true, shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.primaryAccent, unfocusedBorderColor = colors.cardBorder,
                                cursorColor = colors.primaryAccent
                            )
                        )
                        LazyColumn(Modifier.fillMaxWidth().weight(1f).selectableGroup().testTag("app_language_list"),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (showSystem) item("system") {
                                LanguageRow("", systemDefault, subtitle = null, selected = selected.isEmpty(), onSelect = ::choose)
                            }
                            items(matches, key = { it.tag }, contentType = { "language" }) { option ->
                                val subtitle = if (!option.hasInterfaceTranslation) stringResource(R.string.app_language_interface_fallback)
                                    else option.name.takeUnless { it.equals(option.nativeName, ignoreCase = true) }
                                LanguageRow(option.tag, option.nativeName, subtitle, selected == option.tag, ::choose)
                            }
                            if (options.isNotEmpty() && matches.isEmpty() && !showSystem) item("empty") {
                                Text(stringResource(R.string.app_language_no_results),
                                    Modifier.padding(12.dp), color = colors.textSecondary)
                            }
                        }
                        TextButton(onClick = { open = false }, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.app_language_close))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(tag: String, label: String, subtitle: String?, selected: Boolean, onSelect: (String) -> Unit) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (selected) colors.primaryAccent.copy(alpha = .14f) else colors.cardBackgroundElevated)
        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(tag) })
        .padding(14.dp).testTag("app_language_${tag.ifEmpty { "system" }}"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) Icon(Icons.Default.Check, null, tint = colors.primaryAccent, modifier = Modifier.size(20.dp))
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
