package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.border
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.UserProfile
import com.example.data.model.CountryData
import com.example.data.model.CountryItem
import com.example.data.model.LanguageData
import com.example.ui.screens.CountryPickerBottomSheet
import com.example.ui.theme.onAccentGradient
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.XtremeLightBlue

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditProfileSheet(
    userProfile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (UserProfile) -> Unit
) {
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != androidx.compose.material3.SheetValue.Hidden })
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val hideKeyboard = { keyboard?.hide(); Unit }
    val focusManager = LocalFocusManager.current

    var nameInput by remember { mutableStateOf(userProfile.name) }
    var selectedLanguages by remember { mutableStateOf(userProfile.languages.toSet()) }
    var selectedCountry by remember {
        mutableStateOf(
            CountryData.findCountry(userProfile.countryCode)
                ?: CountryData.findCountry(userProfile.country)
                ?: CountryData.allCountries.first()
        )
    }
    var isCountryPickerOpen by remember { mutableStateOf(false) }
    var avatarId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(userProfile.avatarId) }
    var avatarUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(userProfile.avatarUri) }
    var avatarPickerOpen by remember { mutableStateOf(false) }
    var avatarError by remember { mutableStateOf<String?>(null) }
    var avatarPreparing by remember { mutableStateOf(false) }
    var avatarRequest by remember { mutableStateOf(0L) }
    val avatarContext = androidx.compose.ui.platform.LocalContext.current
    val avatarScope = androidx.compose.runtime.rememberCoroutineScope()
    val avatarDrafts = remember(avatarContext) {
        com.example.data.local.AvatarDraftSession(avatarContext, avatarUri.takeIf { it != userProfile.avatarUri })
    }
    androidx.compose.runtime.DisposableEffect(avatarDrafts) {
        onDispose {
            val rotating = generateSequence(avatarContext) { (it as? android.content.ContextWrapper)?.baseContext }
                .filterIsInstance<android.app.Activity>().firstOrNull()?.isChangingConfigurations == true
            avatarDrafts.close(preserve = avatarUri.takeIf { rotating })
        }
    }
    val chooseImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val request = ++avatarRequest
            avatarPreparing = true
            avatarScope.launch {
                try {
                    avatarUri = avatarDrafts.select(uri)
                    avatarDrafts.discardExcept(avatarUri)
                    avatarError = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (request == avatarRequest) avatarError = "Could not use this image. Please choose another image."
                }
                finally { if (request == avatarRequest) avatarPreparing = false }
            }
        }
    }

    val bg = appColors.bottomSheetBackground
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val textPrimary = appColors.textPrimary
    val textMuted = appColors.textMuted
    val primaryAccent = appColors.primaryAccent

    val isFormValid = nameInput.trim().isNotBlank() && selectedLanguages.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = hideKeyboard,
        properties = androidx.compose.material3.ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        sheetState = sheetState,
        containerColor = bg,
        dragHandle = null
    ) {
        androidx.activity.compose.BackHandler(enabled = !isCountryPickerOpen && !avatarPickerOpen) { keyboard?.hide() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .imePadding()
                .testTag("edit_profile_sheet")
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .testTag("edit_profile_fields")
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "Edit Profile & Preferences",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        Text(
                            text = "Customize your recommendations & identity",
                            fontSize = 12.sp,
                            color = textMuted
                        )
                    }
                    IconButton(onClick = {
                        hideKeyboard()
                        focusManager.clearFocus(force = true)
                        onDismiss()
                    }, modifier = Modifier.testTag("edit_profile_close")) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Close profile editor",
                            tint = textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(Modifier.align(Alignment.CenterHorizontally).size(96.dp)) {
                    ProfileAvatar(userProfile.copy(avatarId = avatarId, avatarUri = avatarUri),
                        Modifier.size(88.dp).align(Alignment.Center).testTag("edit_profile_avatar_image"))
                    IconButton(onClick = { hideKeyboard(); avatarPickerOpen = true },
                        modifier = Modifier.align(Alignment.BottomEnd).size(36.dp).clip(CircleShape)
                            .background(appColors.heroGradient).border(BorderStroke(2.dp, bg), CircleShape)
                            .testTag("edit_profile_avatar")) {
                        Icon(androidx.compose.material.icons.Icons.Default.Edit, "Edit avatar",
                            tint = appColors.onAccentGradient, modifier = Modifier.size(18.dp))
                    }
                }
                if (avatarError != null) Text(avatarError.orEmpty(), color = textMuted, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))

                // 1. User Name
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(primaryAccent.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = primaryAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Display Name",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { if (it.length <= 40) nameInput = it },
                            placeholder = { Text("Your name", color = textMuted, fontSize = 14.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryAccent,
                                unfocusedBorderColor = cardBorder,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary,
                                focusedContainerColor = appColors.inputBackground,
                                unfocusedContainerColor = appColors.inputBackground
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_profile_name_input")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2. Music Languages (Multi-select)
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(primaryAccent.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Language,
                                        contentDescription = null,
                                        tint = primaryAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Preferred Languages",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textPrimary
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = primaryAccent.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "${selectedLanguages.size} active",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryAccent,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            LanguageData.allLanguages.forEach { lang ->
                                val isSelected = selectedLanguages.contains(lang.name)
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) {
                                        primaryAccent.copy(alpha = if (isDark) 0.22f else 0.16f)
                                    } else {
                                        appColors.chipBackground
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) primaryAccent else cardBorder
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            selectedLanguages = if (isSelected) {
                                                if (selectedLanguages.size > 1) selectedLanguages - lang.name else selectedLanguages
                                            } else {
                                                selectedLanguages + lang.name
                                            }
                                        }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = primaryAccent,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = lang.name,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) textPrimary else textMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3. Country Preference
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(primaryAccent.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Public,
                                    contentDescription = null,
                                    tint = primaryAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Country (Regional Charts)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val countryBoxBg = if (appColors.isAmoled) {
                            Color(0xFF0A0A0A)
                        } else if (isDark) {
                            appColors.inputBackground
                        } else {
                            appColors.inputBackground
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = countryBoxBg,
                            border = BorderStroke(1.dp, cardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { isCountryPickerOpen = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = selectedCountry.flag, fontSize = 20.sp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = selectedCountry.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = textPrimary
                                        )
                                        Text(
                                            text = "Code: ${selectedCountry.code}",
                                            fontSize = 11.sp,
                                            color = textMuted
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = primaryAccent.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = "CHANGE",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryAccent,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        tint = textMuted
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // Save Button
            com.example.ui.theme.AccentButton(
                onClick = {
                    if (isFormValid) {
                        onSave(
                            userProfile.copy(
                                name = nameInput.trim(),
                                languages = selectedLanguages.toList(),
                                country = selectedCountry.name,
                                countryCode = selectedCountry.code,
                                flag = selectedCountry.flag,
                                isOnboardingCompleted = true,
                                avatarId = avatarId, avatarUri = avatarUri
                            )
                        )
                        avatarDrafts.keep(avatarUri)
                        onDismiss()
                    }
                },
                enabled = isFormValid && !avatarPreparing,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryAccent,
                    contentColor = appColors.onPrimaryAccent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("save_profile_button")
            ) {
                Text(
                    text = "Save Profile Changes",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (avatarPickerOpen) AvatarPickerDialog(avatarId, avatarUri.isNotBlank(),
        onSelect = {
            avatarRequest++
            avatarId = it; avatarUri = ""; avatarDrafts.discardExcept("")
            avatarPreparing = false; avatarPickerOpen = false; avatarError = null
        },
        onChooseImage = { avatarPickerOpen = false; chooseImage.launch(arrayOf("image/*")) },
        onDismiss = { avatarPickerOpen = false })

    if (isCountryPickerOpen) {
        CountryPickerBottomSheet(
            currentCountry = selectedCountry,
            onSelect = {
                selectedCountry = it
                isCountryPickerOpen = false
            },
            onDismiss = { isCountryPickerOpen = false }
        )
    }
}
