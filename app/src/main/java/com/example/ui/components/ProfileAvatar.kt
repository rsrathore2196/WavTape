package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.local.UserProfile
import com.example.ui.theme.LocalAppColors

internal data class DefaultProfileAvatar(val id: String, val label: String, val style: HumanAvatarStyle)
// Retain the stored IDs so existing selections and backups map to the new portraits.
internal val DefaultProfileAvatars = listOf(
    DefaultProfileAvatar("headphones", "Boy with swept hair", HumanAvatarStyle.SWEPT),
    DefaultProfileAvatar("vinyl", "Girl with long hair", HumanAvatarStyle.LONG),
    DefaultProfileAvatar("soundwave", "Boy with curly hair", HumanAvatarStyle.CURLS),
    DefaultProfileAvatar("microphone", "Girl with a bob", HumanAvatarStyle.BOB),
    DefaultProfileAvatar("radio", "Listener with glasses", HumanAvatarStyle.GLASSES),
    DefaultProfileAvatar("music", "Girl with a headscarf", HumanAvatarStyle.HEADSCARF),
    DefaultProfileAvatar("piano", "Listener with a turban", HumanAvatarStyle.TURBAN),
    DefaultProfileAvatar("speaker", "Older listener", HumanAvatarStyle.SILVER),
    DefaultProfileAvatar("dj", "Girl with curly hair", HumanAvatarStyle.AFRO)
)

/** Theme-colored illustrations also serve as the fallback for an unavailable personal image. */
@Composable
internal fun ProfileAvatar(profile: UserProfile?, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val avatar = DefaultProfileAvatars.firstOrNull { it.id == profile?.avatarId } ?: DefaultProfileAvatars.first()
    val uri = profile?.avatarUri?.takeIf { it.startsWith("content://") }
    var failed by remember(uri) { mutableStateOf(false) }
    val background = remember(colors.cardBackground, colors.primaryAccent, colors.secondaryAccent) {
        Brush.linearGradient(listOf(lerp(colors.cardBackground, colors.primaryAccent, .18f),
            lerp(colors.cardBackground, colors.secondaryAccent, .12f)))
    }
    val illustration = rememberHumanAvatar(avatar.style)
    Box(modifier.clip(CircleShape).background(background).drawWithCache {
        val rim = Color.White.copy(alpha = .25f)
        onDrawBehind {
            drawCircle(rim, radius = size.minDimension * .44f, style = Stroke(1.dp.toPx()))
            drawCircle(Color.White.copy(alpha = .10f), radius = size.minDimension * .40f,
                center = Offset(size.width * .12f, size.height * .12f))
        }
    }, contentAlignment = Alignment.Center) {
        Image(illustration, if (uri == null || failed) avatar.label + " avatar" else null, modifier = Modifier.fillMaxSize())
        if (uri != null && !failed) AsyncImage(model = uri, contentDescription = "Custom profile image",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = { failed = true })
    }
}

@Composable
internal fun AvatarPickerDialog(selectedId: String, customSelected: Boolean, onSelect: (String) -> Unit,
    onChooseImage: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = colors.bottomSheetBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.cardBorder),
            modifier = Modifier.widthIn(max = 340.dp).testTag("avatar_picker")) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Choose your avatar", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                        color = colors.textPrimary, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Close avatar picker", tint = colors.textMuted)
                    }
                }
                for (row in DefaultProfileAvatars.chunked(3)) Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (avatar in row) {
                        val chosen = !customSelected && selectedId == avatar.id
                        Box(Modifier.size(68.dp).clip(CircleShape)
                            .border(if (chosen) 3.dp else 1.dp,
                                if (chosen) colors.primaryAccent else colors.cardBorder, CircleShape)
                            .clickable(role = Role.RadioButton) { onSelect(avatar.id) }
                            .semantics { selected = chosen }.testTag("avatar_option_${avatar.id}").padding(5.dp)) {
                            ProfileAvatar(UserProfile(avatarId = avatar.id), Modifier.fillMaxSize())
                        }
                    }
                }
                OutlinedButton(onClick = onChooseImage, modifier = Modifier.fillMaxWidth().testTag("avatar_choose_image"),
                    shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.PhotoLibrary, null, tint = colors.primaryAccent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose from gallery", color = colors.textPrimary)
                }
            }
        }
    }
}
