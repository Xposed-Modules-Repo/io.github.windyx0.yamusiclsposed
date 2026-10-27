package io.github.windyx0.yamusiclsposed

import android.app.Activity
import android.graphics.Bitmap
import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun YaMusicSettingsScreen(
    config: Config,
    backgroundBitmap: Bitmap?,
    onClose: () -> Unit,
    onSave: (Config) -> Unit,
    activity: Activity
) {
    val isDark = true
    val overlayColor = if (isDark) Color(0x60000000) else Color(0x40FFFFFF)
    val cardColor = if (isDark) Color(0x601C1C1E) else Color(0x80FFFFFF)
    val textColorPrimary = if (isDark) Color.White else Color.Black
    val textColorSecondary = if (isDark) Color(0xFF98989D) else Color(0xFF8E8E93)
    val gradientColors = listOf(Color(0xFFFF3B30), Color(0xFFFF9500))

    var quality by remember { mutableStateOf(config.quality) }
    var cover by remember { mutableStateOf(config.cover) }
    var coverSize by remember { mutableStateOf(config.coverSize) }
    var folderType by remember { mutableStateOf(config.folderType) }
    var customFolder by remember { mutableStateOf(config.customFolder) }
    var manualToken by remember { mutableStateOf(config.manualToken) }

    var isFolderPickerOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(overlayColor)
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                if (!isFolderPickerOpen) {
                    onClose()
                }
            }
    ) {
        if (!isFolderPickerOpen) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(24.dp))
                    .liquidGlass(backgroundBitmap, cornerRadius = 60f, refractionAmount = 1.5f, refractionHeight = 25f)
                    .background(cardColor)
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { }
                    .padding(24.dp)
            ) {
                Text(
                    text = "Настройки",
                    color = textColorPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    item {
                        SettingsCard(cardColor, backgroundBitmap) {
                            Text("Токен авторизации", color = textColorPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(8.dp))
                            BasicTextField(
                                value = manualToken,
                                onValueChange = { manualToken = it },
                                textStyle = TextStyle(color = textColorPrimary, fontSize = 14.sp),
                                cursorBrush = SolidColor(textColorPrimary),
                                decorationBox = { innerTextField ->
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7))
                                            .padding(12.dp)
                                    ) {
                                        if (manualToken.isEmpty()) {
                                            Text("Оставьте пустым для автопоиска", color = textColorSecondary, fontSize = 14.sp)
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }

                    item {
                        SettingsCard(cardColor, backgroundBitmap) {
                            Text("Аудио и Обложка", color = textColorPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(12.dp))
                            
                            Text("Качество звука", color = textColorSecondary, fontSize = 13.sp)
                            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(modifier = Modifier.clickable { quality = 320 }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = quality == 320, onClick = null)
                                    Text("320 kbps", color = textColorPrimary, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, end = 16.dp))
                                }
                                Row(modifier = Modifier.clickable { quality = 192 }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = quality == 192, onClick = null)
                                    Text("192 kbps", color = textColorPrimary, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { cover = !cover }.padding(vertical = 8.dp)) {
                                Text("Вшивать обложку в файл", color = textColorPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Switch(checked = cover, onCheckedChange = null)
                            }
                        }
                    }

                    item {
                        SettingsCard(cardColor, backgroundBitmap) {
                            Text("Папка для загрузок", color = textColorPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { folderType = "Music" }.padding(vertical = 4.dp)) {
                                RadioButton(selected = folderType == "Music", onClick = null)
                                Text("Стандартная (Music)", color = textColorPrimary, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { folderType = "Downloads" }.padding(vertical = 4.dp)) {
                                RadioButton(selected = folderType == "Downloads", onClick = null)
                                Text("Загрузки (Downloads)", color = textColorPrimary, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { folderType = "Custom" }.padding(vertical = 4.dp)) {
                                RadioButton(selected = folderType == "Custom", onClick = null)
                                Text("Своя папка", color = textColorPrimary, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                            }

                            if (folderType == "Custom") {
                                Spacer(Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7))
                                        .clickable { isFolderPickerOpen = true }
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = customFolder.ifEmpty { "Выберите папку..." },
                                        color = if (customFolder.isEmpty()) textColorSecondary else textColorPrimary,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Button(
                                onClick = onClose,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                contentPadding = PaddingValues(16.dp)
                            ) {
                                Text("Отмена", color = textColorPrimary, fontSize = 16.sp)
                            }
                            
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Brush.horizontalGradient(gradientColors))
                                    .clickable {
                                        onSave(
                                            Config(
                                                quality = quality,
                                                cover = cover,
                                                coverSize = coverSize,
                                                folderType = folderType,
                                                customFolder = customFolder,
                                                manualToken = manualToken
                                            )
                                        )
                                    }
                                    .padding(horizontal = 24.dp, vertical = 12.dp)
                            ) {
                                Text("Сохранить", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                    }
                    
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Связь со мной (Windyx0):", color = textColorSecondary, fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(
                                    text = "TikTok",
                                    color = Color(0xFF69C9D0),
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.tiktok.com/@windyx_edits"))
                                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        activity.startActivity(intent)
                                    }.padding(8.dp)
                                )
                                Text(
                                    text = "Канал (TG)",
                                    color = Color(0xFF2AABEE),
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/WindyxChannel"))
                                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        activity.startActivity(intent)
                                    }.padding(8.dp)
                                )
                                Text(
                                    text = "ЛС (TG)",
                                    color = Color(0xFF2AABEE),
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/Windyx0"))
                                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        activity.startActivity(intent)
                                    }.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else if (isFolderPickerOpen) {
            FolderPickerScreen(
                initialPath = customFolder,
                isDark = isDark,
                backgroundBitmap = backgroundBitmap,
                onPathSelected = {
                    customFolder = it
                    isFolderPickerOpen = false
                },
                onCancel = { isFolderPickerOpen = false }
            )
        }
    }
}

@Composable
fun SettingsCard(cardColor: Color, backgroundBitmap: Bitmap?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .liquidGlass(backgroundBitmap, cornerRadius = 60f, refractionAmount = 1.0f, refractionHeight = 15f)
            .background(cardColor.copy(alpha = 0.4f))
            .border(0.5.dp, Color(0x30FFFFFF), RoundedCornerShape(20.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
fun FolderPickerScreen(
    initialPath: String,
    isDark: Boolean,
    backgroundBitmap: Bitmap?,
    onPathSelected: (String) -> Unit,
    onCancel: () -> Unit
) {
    val cardColor = if (isDark) Color(0x801C1C1E) else Color(0xAAFFFFFF)
    val textColorPrimary = if (isDark) Color.White else Color.Black
    val textColorSecondary = if (isDark) Color(0xFF98989D) else Color(0xFF8E8E93)
    val gradientColors = listOf(Color(0xFFFF3B30), Color(0xFFFF9500))
    
    var currentPath by remember { 
        mutableStateOf(
            File(initialPath.takeIf { it.isNotEmpty() } ?: Environment.getExternalStorageDirectory().absolutePath).let {
                if (!it.exists() || !it.isDirectory) Environment.getExternalStorageDirectory() else it
            }
        )
    }

    val files = remember(currentPath) {
        try {
            currentPath.listFiles { file -> file.isDirectory && !file.isHidden }?.sortedBy { it.name } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    val isRoot = currentPath.absolutePath == Environment.getExternalStorageDirectory().absolutePath

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .clip(RoundedCornerShape(24.dp))
            .liquidGlass(backgroundBitmap, cornerRadius = 60f, refractionAmount = 1.5f, refractionHeight = 25f)
            .background(cardColor)
            .padding(16.dp)
    ) {
        Text(
            text = "Выберите папку",
            color = textColorPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Text(
            text = currentPath.absolutePath,
            color = textColorSecondary,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 8.dp)
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (!isRoot && currentPath.parentFile != null) {
                item {
                    Text(
                        text = ".. (Назад)",
                        color = textColorPrimary,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { currentPath = currentPath.parentFile ?: currentPath }
                            .padding(vertical = 16.dp, horizontal = 8.dp)
                    )
                }
            }
            items(files) { file ->
                Text(
                    text = "📁 ${file.name}",
                    color = textColorPrimary,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { currentPath = file }
                        .padding(vertical = 16.dp, horizontal = 8.dp)
                )
            }
        }
        
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(16.dp)
            ) {
                Text("Отмена", color = textColorSecondary, fontSize = 16.sp)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.horizontalGradient(gradientColors))
                    .clickable { onPathSelected(currentPath.absolutePath) }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text("Выбрать", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

class ActiveDownload(
    val id: String,
    val title: String,
    val artist: String
) {
    var progress by mutableStateOf(0f)
}

object DownloadState {
    val activeDownloads = androidx.compose.runtime.mutableStateListOf<ActiveDownload>()
}

@Composable
fun YaMusicDownloadsScreen(
    backgroundBitmap: Bitmap?,
    onClose: () -> Unit
) {
    val isDark = true
    val overlayColor = if (isDark) Color(0x60000000) else Color(0x40FFFFFF)
    val cardColor = if (isDark) Color(0x601C1C1E) else Color(0x80FFFFFF)
    val textColorPrimary = if (isDark) Color.White else Color.Black
    val textColorSecondary = if (isDark) Color(0xFF98989D) else Color(0xFF8E8E93)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(overlayColor)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight(0.7f)
                .clickable(enabled = false) {}
                .clip(RoundedCornerShape(20.dp))
                .liquidGlass(backgroundBitmap, cornerRadius = 60f, refractionAmount = 1.0f, refractionHeight = 15f)
                .background(cardColor.copy(alpha = 0.4f))
                .border(0.5.dp, Color(0x30FFFFFF), RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Активные загрузки",
                    color = textColorPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (DownloadState.activeDownloads.isEmpty()) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text("Нет активных загрузок", color = textColorSecondary, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(DownloadState.activeDownloads) { download ->
                            SettingsCard(cardColor, backgroundBitmap) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = download.title,
                                        color = textColorPrimary,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = download.artist,
                                        color = textColorSecondary,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = download.progress / 100f,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = Color(0xFFFF9500),
                                        trackColor = textColorSecondary.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Text("Закрыть", color = textColorPrimary, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
