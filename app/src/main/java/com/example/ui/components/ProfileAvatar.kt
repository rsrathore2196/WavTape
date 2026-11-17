package com.example.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.LocalAppColors

internal data class DefaultProfileAvatar(val id: String, val label: String, val icon: ImageVector)
internal val DefaultProfileAvatars = listOf(
    DefaultProfileAvatar("headphones", "Headphones", Icons.Default.Headphones),
    DefaultProfileAvatar("vinyl", "Vinyl", Icons.Default.Album),
    DefaultProfileAvatar("soundwave", "Soundwave", Icons.Default.GraphicEq),
    DefaultProfileAvatar("microphone", "Microphone", Icons.Default.Mic),
    DefaultProfileAvatar("radio", "Radio", Icons.Default.Radio),
    DefaultProfileAvatar("music", "Music", Icons.Default.MusicNote),
    DefaultProfileAvatar("piano", "Piano", Icons.Default.Piano),
    DefaultProfileAvatar("speaker", "Speaker", Icons.Default.Speaker),
    DefaultProfileAvatar("dj", "DJ", Icons.Default.Equalizer)
)

/** Theme-colored illustrations also serve as the fallback for an unavailable personal image. */
@Composable
internal fun ProfileAvatar(profile: UserProfile?, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val avatar = DefaultProfileAvatars.firstOrNull { it.id == profile?.avatarId } ?: DefaultProfileAvatars.first()
    val uri = profile?.avatarUri?.takeIf { it.startsWith("content://") }
    var failed by remember(uri) { mutableStateOf(false) }
    val foreground = remember(colors.primaryAccent, colors.secondaryAccent) {
        LiquidGlass.contrastColor(colors.onPrimaryAccent, listOf(colors.primaryAccent, colors.secondaryAccent), 4.5f)
    }
    Box(modifier.clip(CircleShape).background(colors.heroGradient).drawWithCache {
        val rim = Color.White.copy(alpha = .25f)
        onDrawBehind {
            drawCircle(rim, radius = size.minDimension * .44f, style = Stroke(1.dp.toPx()))
            drawCircle(Color.White.copy(alpha = .10f), radius = size.minDimension * .40f,
                center = Offset(size.width * .12f, size.height * .12f))
        }
    }, contentAlignment = Alignment.Center) {
        Icon(avatar.icon, if (uri == null || failed) avatar.label + " avatar" else null, tint = foreground, modifier = Modifier.fillMaxSize(.48f))
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
