package com.example.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.AppFontStyle
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.bouncyClickable

/**
 * Compact, modern, theme-adaptive dialog for selecting the application's [AppFontStyle].
 * Each option renders its own display name and glyph badge in its actual typeface with
 * optical scaling applied so users can preview alignment and character proportions live.
 */
@Composable
fun FontStylePickerDialog(
    selectedFont: AppFontStyle,
    onSelectFont: (AppFontStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val appColors = LocalAppColors.current
    val haptics = LocalHapticFeedback.current
    val fonts = remember { AppFontStyle.entries }
    val selectedIndex = remember(selectedFont) {
        fonts.indexOf(selectedFont).coerceAtLeast(0)
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (selectedIndex - 1).coerceAtLeast(0)
    )

    val dialogSurface = if (appColors.isAmoled) Color(0xFF0A0A0A) else appColors.cardBackground
    val previewSurface = if (appColors.isAmoled) Color(0xFF121212) else appColors.cardBackgroundElevated
    val accentColor = appColors.primaryAccent
    val centeredLineStyle = remember {
        LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = dialogSurface,
            border = BorderStroke(
                1.dp,
                LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (appColors.isDark) 0.35f else 0.50f)
            ),
            shadowElevation = 18.dp,
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .testTag("font_style_picker_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 14.dp)
            ) {
                // Compact Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accentColor.copy(alpha = if (appColors.isDark) 0.20f else 0.12f))
                            .border(
                                1.dp,
                                accentColor.copy(alpha = 0.32f),
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Aa",
                            fontFamily = selectedFont.fontFamily,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Font Style",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${fonts.size} styles • ${selectedFont.displayName}",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("font_style_dialog_close")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close font style picker",
                            tint = appColors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Compact Live Preview Strip
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = previewSurface,
                    border = BorderStroke(1.dp, accentColor.copy(alpha = if (appColors.isDark) 0.28f else 0.20f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("font_style_preview_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "WavTape • Lossless Sound",
                            fontFamily = selectedFont.fontFamily,
                            fontSize = 14.5.sp,
                            lineHeight = (19f * selectedFont.lineHeightScale).sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = androidx.compose.ui.text.TextStyle(lineHeightStyle = centeredLineStyle)
                        )
                        Text(
                            text = "Aa Bb Cc • Smooth Hi-Fi playback 0123",
                            fontFamily = selectedFont.fontFamily,
                            fontSize = 11.5.sp,
                            lineHeight = (15.5f * selectedFont.lineHeightScale).sp,
                            fontWeight = FontWeight.Normal,
                            color = appColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = androidx.compose.ui.text.TextStyle(lineHeightStyle = centeredLineStyle)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(
                    color = appColors.dividerColor,
                    thickness = 0.75.dp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                // Scrollable List of Font Options (System Font + 22 curated fonts)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 316.dp)
                        .selectableGroup()
                        .testTag("font_style_options_list"),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(items = fonts, key = { it.id }) { option ->
                        val isSelected = option == selectedFont
                        // Normalize preview size relative to the currently active theme opticalScale
                        // so each font item previews its true balanced size in the list.
                        val relativeOptical = (option.opticalScale / selectedFont.opticalScale).coerceIn(0.84f, 1.16f)
                        val rowBg by animateColorAsState(
                            targetValue = if (isSelected) {
                                accentColor.copy(alpha = if (appColors.isDark) 0.18f else 0.12f)
                            } else {
                                Color.Transparent
                            },
                            animationSpec = tween(160),
                            label = "font_row_bg"
                        )
                        val rowBorderColor by animateColorAsState(
                            targetValue = if (isSelected) accentColor.copy(alpha = 0.75f) else appColors.cardBorder.copy(alpha = 0.45f),
                            animationSpec = tween(160),
                            label = "font_row_border"
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(rowBg)
                                .border(
                                    width = if (isSelected) 1.25.dp else 0.75.dp,
                                    color = rowBorderColor,
                                    shape = RoundedCornerShape(13.dp)
                                )
                                .selectable(selected = isSelected, role = Role.RadioButton) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectFont(option)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("font_option_${option.id}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) accentColor
                                        else previewSurface
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Aa",
                                    fontFamily = option.fontFamily,
                                    fontSize = (13.5f * relativeOptical).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) appColors.onPrimaryAccent else appColors.textPrimary,
                                    maxLines = 1
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.displayName,
                                    fontFamily = option.fontFamily,
                                    fontSize = (14f * relativeOptical).sp,
                                    lineHeight = (18.5f * relativeOptical * option.lineHeightScale).sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isSelected) accentColor else appColors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = androidx.compose.ui.text.TextStyle(lineHeightStyle = centeredLineStyle)
                                )
                                Text(
                                    text = option.subtitle,
                                    fontSize = 11.sp,
                                    color = appColors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(accentColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "${option.displayName} selected",
                                        tint = appColors.onPrimaryAccent,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .border(1.25.dp, appColors.cardBorder, CircleShape)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    color = appColors.dividerColor,
                    thickness = 0.75.dp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Compact Action Footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (selectedFont != AppFontStyle.SYSTEM) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .bouncyClickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectFont(AppFontStyle.SYSTEM)
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("font_style_reset_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RestartAlt,
                                contentDescription = "Reset to System Font",
                                tint = appColors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "System Font",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = appColors.textSecondary,
                                maxLines = 1
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Box(
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accentColor)
                            .bouncyClickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onDismiss()
                            }
                            .padding(horizontal = 22.dp, vertical = 9.dp)
                            .testTag("font_style_done_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Done",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.onPrimaryAccent,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
