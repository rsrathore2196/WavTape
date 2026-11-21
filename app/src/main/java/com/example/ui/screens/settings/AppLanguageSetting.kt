package com.example.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.AppLanguage
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.bouncyClickable
import com.example.ui.theme.liquidGlassCard

@Composable
internal fun AppLanguageSetting() {
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    var selected by remember(context) { mutableStateOf(AppLanguage.selection(context)) }
    var open by remember { mutableStateOf(false) }
    val colors = LocalAppColors.current
    val systemDefault = stringResource(R.string.app_language_system)
    val title = stringResource(R.string.app_language)
    val options = listOf("" to systemDefault) + AppLanguage.choices.toList()
    Row(Modifier.fillMaxWidth().liquidGlassCard(colors, shape = RoundedCornerShape(22.dp),
        elevation = 6.dp, translucency = .82f, tintAccent = true)
        .bouncyClickable { open = true }.padding(16.dp).testTag("app_language_setting"),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Language, null, tint = colors.primaryAccent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(AppLanguage.choices[selected] ?: systemDefault, color = colors.textSecondary,
                style = MaterialTheme.typography.bodySmall)
        }
        Text("›", color = colors.textMuted, style = MaterialTheme.typography.titleLarge)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (tag, label) ->
                    val isSelected = selected == tag
                    val shape = RoundedCornerShape(14.dp)
                    Row(Modifier.fillMaxWidth().clip(shape)
                        .background(if (isSelected) colors.primaryAccent.copy(alpha = .14f) else colors.cardBackgroundElevated)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = {
                            open = false
                            activity?.let { AppLanguage.set(it, tag); selected = tag }
                        }).padding(14.dp).testTag("app_language_${tag.ifEmpty { "system" }}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                        if (isSelected) Icon(Icons.Default.Check, null, tint = colors.primaryAccent, modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.app_language_close)) } },
        containerColor = colors.cardBackground
    )
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
