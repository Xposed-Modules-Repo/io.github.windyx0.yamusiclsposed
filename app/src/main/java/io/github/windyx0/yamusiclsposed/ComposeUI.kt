package io.github.windyx0.yamusiclsposed



import android.app.Activity

import android.content.Context

import android.content.Intent

import android.graphics.Bitmap

import android.net.Uri

import android.os.Build

import android.os.Environment

import android.widget.Toast

import androidx.compose.animation.*

import androidx.compose.foundation.background

import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.*

import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.lazy.items

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*

import androidx.compose.runtime.*

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.compositeOver

import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.stateDescription

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

import org.json.JSONObject

import java.io.File

import java.net.HttpURLConnection

import java.net.URL



val Md3DarkColorScheme = darkColorScheme(

    primary = Color(0xFFFFD233),          // Yandex Yellow-Gold MD3 Accent

    onPrimary = Color(0xFF3B2F00),

    primaryContainer = Color(0xFF554400),

    onPrimaryContainer = Color(0xFFFFE082),

    secondary = Color(0xFFD7C5AF),

    onSecondary = Color(0xFF3A2F20),

    secondaryContainer = Color(0xFF524534),

    onSecondaryContainer = Color(0xFFF4E1CB),

    tertiary = Color(0xFFA5D6A7),

    onTertiary = Color(0xFF0F3818),

    tertiaryContainer = Color(0xFF274F2D),

    onTertiaryContainer = Color(0xFFC1ECC2),

    background = Color(0xFF141311),

    onBackground = Color(0xFFE7E2DA),

    surface = Color(0xFF1D1B18),

    onSurface = Color(0xFFE7E2DA),

    surfaceVariant = Color(0xFF4C4639),

    onSurfaceVariant = Color(0xFFCFC5B4),

    outline = Color(0xFF989080),

    outlineVariant = Color(0xFF4C4639)

)



// MD3 Surface Container helpers for Compose versions prior to M3 1.2.0

val ColorScheme.surfaceContainer: Color

    get() = surfaceVariant.copy(alpha = 0.35f).compositeOver(surface)



val ColorScheme.surfaceContainerHigh: Color

    get() = surfaceVariant.copy(alpha = 0.55f).compositeOver(surface)



val ColorScheme.surfaceContainerHighest: Color

    get() = surfaceVariant.copy(alpha = 0.8f).compositeOver(surface)



@Composable

fun YaMusicTheme(content: @Composable () -> Unit) {

    val context = LocalContext.current

    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

        try {

            dynamicDarkColorScheme(context)

        } catch (e: Throwable) {

            Md3DarkColorScheme

        }

    } else {

        Md3DarkColorScheme

    }



    MaterialTheme(

        colorScheme = colorScheme,

        content = content

    )

}



data class UpdateInfo(

    val hasUpdate: Boolean,

    val latestVersionName: String,

    val latestVersionCode: Int,

    val releaseTitle: String,

    val changelog: String,

    val downloadUrl: String?,

    val releaseUrl: String

)



sealed class UpdateState {

    object Idle : UpdateState()

    object Checking : UpdateState()

    data class Available(val updateInfo: UpdateInfo) : UpdateState()

    data class UpToDate(val currentVersion: String) : UpdateState()

    data class Error(val message: String) : UpdateState()

}



fun openUrl(context: Context, url: String) {

    try {

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {

            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        }

        context.startActivity(intent)

    } catch (e: Throwable) {

        Toast.makeText(context, "Не удалось открыть: ${e.message}", Toast.LENGTH_SHORT).show()

    }

}



fun fetchLatestRelease(currentVersionCode: Int = 7, currentVersionName: String = "1.4.2", channel: String = "release"): UpdateState {

    return try {

        val url = URL("https://api.github.com/repos/Xposed-Modules-Repo/io.github.windyx0.yamusiclsposed/releases")

        val conn = (url.openConnection() as HttpURLConnection).apply {

            connectTimeout = 7000

            readTimeout = 7000

            setRequestProperty("User-Agent", "YaMusicLsposed")

            setRequestProperty("Accept", "application/vnd.github.v3+json")

        }

        if (conn.responseCode in 200..299) {

            val responseText = conn.inputStream.bufferedReader().use { it.readText() }

            val releases = org.json.JSONArray(responseText)

            if (releases.length() == 0) {

                return UpdateState.UpToDate(currentVersionName)

            }



            var targetRelease: JSONObject? = null

            for (i in 0 until releases.length()) {

                val rel = releases.getJSONObject(i)

                val isDraft = rel.optBoolean("draft", false)

                if (isDraft) continue

                val isPre = rel.optBoolean("prerelease", false)

                val tag = rel.optString("tag_name", "").lowercase()

                val name = rel.optString("name", "").lowercase()

                val isDevTag = tag.contains("dev") || name.contains("dev")



                when (channel) {

                    "release" -> {

                        if (!isPre && !isDevTag && !tag.contains("pre") && !tag.contains("rc") && !tag.contains("beta")) {

                            targetRelease = rel

                            break

                        }

                    }

                    "pre" -> {

                        if (!isDevTag) {

                            targetRelease = rel

                            break

                        }

                    }

                    "dev" -> {

                        targetRelease = rel

                        break

                    }

                    else -> {

                        if (!isPre && !isDevTag) {

                            targetRelease = rel

                            break

                        }

                    }

                }

            }



            if (targetRelease == null) {

                return UpdateState.UpToDate(currentVersionName)

            }



            val tagName = targetRelease.optString("tag_name", "")

            val releaseName = targetRelease.optString("name", tagName)

            val body = targetRelease.optString("body", "")

            val htmlUrl = targetRelease.optString("html_url", "https://github.com/Xposed-Modules-Repo/io.github.windyx0.yamusiclsposed/releases")



            var remoteCode = 0

            var remoteName = tagName

            if (tagName.contains("-")) {

                val parts = tagName.split("-", limit = 2)

                remoteCode = parts[0].toIntOrNull() ?: 0

                remoteName = parts[1]

            } else {

                remoteName = tagName.removePrefix("v")

            }



            var apkUrl: String? = null

            val assets = targetRelease.optJSONArray("assets")

            if (assets != null) {

                for (i in 0 until assets.length()) {

                    val item = assets.getJSONObject(i)

                    val n = item.optString("name", "")

                    if (n.endsWith(".apk", ignoreCase = true)) {

                        apkUrl = item.optString("browser_download_url")

                        break

                    }

                }

            }



            val hasUpdate = if (remoteCode > 0) {

                remoteCode > currentVersionCode

            } else {

                remoteName != currentVersionName

            }



            if (hasUpdate) {

                UpdateState.Available(

                    UpdateInfo(

                        hasUpdate = true,

                        latestVersionName = remoteName,

                        latestVersionCode = remoteCode,

                        releaseTitle = releaseName,

                        changelog = body,

                        downloadUrl = apkUrl ?: htmlUrl,

                        releaseUrl = htmlUrl

                    )

                )

            } else {

                UpdateState.UpToDate(currentVersionName)

            }

        } else {

            UpdateState.Error("HTTP ${conn.responseCode}")

        }

    } catch (e: Throwable) {

        UpdateState.Error(e.message ?: "Ошибка сети")

    }

}



data class ChangelogItem(

    val version: String,

    val date: String,

    val isCurrent: Boolean = false,

    val points: List<String>

)



val changelogHistory = listOf(

    ChangelogItem(

        version = "v1.4.2",

        date = "Ноябрь 2026",

        isCurrent = true,

        points = listOf(

            "Поддержка автора: добавлена карта Сбера (2202 2088 6471 5629) с быстрым копированием номера по клику вместо DonationAlerts."

        )

    ),

    ChangelogItem(

        version = "v1.4.1",

        date = "Ноябрь 2026",

        isCurrent = false,

        points = listOf(

            "Перенос отладочного лога метаданных: файл YaMusicLsposed_Meta.txt перенесен во внутренний каталог /data/data/io.github.windyx0.yamusiclsposed/files/, исключено засорение папки Загрузки.",

            "Автоматическая очистка: удаление старого лог-файла из публичной папки Download на устройстве."

        )

    ),

    ChangelogItem(

        version = "v1.4",

        date = "Ноябрь 2026",

        isCurrent = false,

        points = listOf(

            "Продвинутый выбор формата и качества аудио: добавлена поддержка скачивания оригинальных аудиопотоков FLAC (Lossless) и M4A (AAC HQ) в дополнение к MP3 (320/192 kbps).",

            "Модуль YaMusicCacheDecryptor: встроенный инструмент дешифрования локального оффлайн-кэша Яндекс Музыки (AES-128-CTR) с получением ключа через официальный API-токен и автоматическим экспортом в FLAC/M4A/MP3.",

            "Исправление бага одиночной загрузки: устранена проблема случайного прекращения сохранения отдельных треков после 100% шкалы из-за устаревших ссылок в буфере обмена и ошибок тегирования.",

            "Надежный экспорт файлов: добавлено автоматическое резервное сохранение аудиопотока даже при сбоях ID3v2 тегирования и гарантированная проверка записи на диск перед завершением.",

            "Автоматическое обновление медиатеки: моментальное появление дешифрованных и скачанных треков во всех системных аудиоплеерах через системный MediaScanner."

        )

    ),

    ChangelogItem(

        version = "v1.3",

        date = "Ноябрь 2026",

        isCurrent = false,

        points = listOf(

            "Прямое высокоскоростное скачивание треков: обмен токена авторизации (x-token) на полнофункциональный токен Яндекс Музыки с Plus-правами.",

            "Скачивание полных версий без ограничений 30 секунд: гарантированное получение полноценных аудиопотоков 320/192 kbps.",

            "Выбор каналов обновления в настройках: релизная (Release), предрелизная (Pre-release) и версия разработчика (Dev).",

            "Улучшенная индикация плейлистов: динамическое отображение счетчика скачанных и оставшихся треков прямо на кнопке и в уведомлении.",

            "Изоляция прогресса: исключено дрожание кнопки одиночного скачивания во время параллельной загрузки плейлистов.",

            "Умная фильтрация плейлистов: автоматический пропуск недоступных треков и дубликатов с точным подсчетом общего количества."

        )

    ),

    ChangelogItem(

        version = "v1.1",

        date = "Ноябрь 2026",

        isCurrent = false,

        points = listOf(

            "Автоматический перехват токена: модуль сам получает OAuth-токен через системный AccountManager и сетевые перехватчики OkHttp/Cronet.",

            "Новый интерфейс Material Design 3: современный дизайн настроек с динамическими цветами Material You на Android 12+, MD3 контейнерами и скруглениями 28dp.",

            "Проверка обновлений и чейнджлог: встроенная проверка обновлений с GitHub Releases и интерактивный экран истории изменений.",

            "Исправление для планшетов: устранено падение (ResourcesNotFoundException) при включённых службах специальных возможностей на Xiaomi HyperOS/MIUI.",

            "Межпроцессная синхронизация: сохранение токена и метаданных в файловом кэше приложения для бесперебойной работы в фоне."

        )

    ),

    ChangelogItem(

        version = "v1.0",

        date = "Октябрь 2026",

        isCurrent = false,

        points = listOf(

            "Прямое скачивание MP3: скачивание треков, альбомов и плейлистов из Яндекс Музыки в оригинальном качестве.",

            "Параллельное скачивание: максимальная скорость благодаря параллельной загрузке в 4 потока.",

            "Вшивание метаданных: автоматическое добавление обложек высокого разрешения и ID3v2.4 тегов.",

            "Умная сортировка: подмена даты создания файлов для точного сохранения порядка треков в плейлисте.",

            "Оверлей загрузок: просмотр активных загрузок в реальном времени."

        )

    )

)



@OptIn(ExperimentalMaterial3Api::class)

@Composable

fun YaMusicSettingsScreen(

    config: Config,

    backgroundBitmap: Bitmap? = null,

    onClose: () -> Unit,

    onSave: (Config) -> Unit,

    activity: Activity

) {

    YaMusicTheme {

        var quality by remember { mutableStateOf(config.quality) }

        var cover by remember { mutableStateOf(config.cover) }

        val coverSize by remember { mutableStateOf(config.coverSize) }

        var folderType by remember { mutableStateOf(config.folderType) }

        var customFolder by remember { mutableStateOf(config.customFolder) }

        var manualToken by remember { mutableStateOf(config.manualToken) }

        var autoDetectedToken by remember { mutableStateOf(MainHook.cachedToken ?: findToken(activity)) }

        var updateChannel by remember { mutableStateOf(config.updateChannel) }

        var audioFormat by remember { mutableStateOf(config.audioFormat) }



        var isFolderPickerOpen by remember { mutableStateOf(false) }

        var isChangelogOpen by remember { mutableStateOf(false) }

        var isDecryptorOpen by remember { mutableStateOf(false) }

        var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

        val coroutineScope = rememberCoroutineScope()



        LaunchedEffect(Unit) {

            if (updateState is UpdateState.Idle) {

                updateState = UpdateState.Checking

                withContext(Dispatchers.IO) {

                    updateState = fetchLatestRelease(7, "1.4.2", updateChannel)

                }

            }

        }



        Box(

            modifier = Modifier

                .fillMaxSize()

                .background(Color(0x8A000000))

                .clickable(

                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },

                    indication = null

                ) {

                    if (!isFolderPickerOpen && !isChangelogOpen && !isDecryptorOpen) onClose()

                },

            contentAlignment = Alignment.Center

        ) {

            if (isFolderPickerOpen) {

                FolderPickerScreen(

                    initialPath = customFolder,

                    onPathSelected = {

                        customFolder = it

                        isFolderPickerOpen = false

                    },

                    onCancel = { isFolderPickerOpen = false }

                )

            } else if (isChangelogOpen) {

                ChangelogScreen(

                    onClose = { isChangelogOpen = false },

                    latestReleaseBody = (updateState as? UpdateState.Available)?.updateInfo?.changelog

                )

            } else if (isDecryptorOpen) {

                CacheDecryptorScreen(

                    onClose = { isDecryptorOpen = false },

                    activity = activity,

                    token = manualToken.ifBlank { autoDetectedToken ?: "" },

                    targetFolder = getDownloadPath(

                        Config(

                            quality = quality,

                            cover = cover,

                            coverSize = coverSize,

                            folderType = folderType,

                            customFolder = customFolder,

                            manualToken = manualToken,

                            updateChannel = updateChannel,

                            audioFormat = audioFormat

                        )

                    )

                )

            } else {

                Surface(

                    modifier = Modifier

                        .fillMaxWidth(0.92f)

                        .fillMaxHeight(0.88f)

                        .clickable(

                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },

                            indication = null

                        ) {},

                    shape = RoundedCornerShape(28.dp),

                    color = MaterialTheme.colorScheme.surfaceContainer,

                    tonalElevation = 6.dp,

                    shadowElevation = 10.dp,

                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                ) {

                    Column(

                        modifier = Modifier

                            .fillMaxSize()

                            .padding(20.dp)

                    ) {

                        // Header

                        Row(

                            modifier = Modifier

                                .fillMaxWidth()

                                .padding(bottom = 12.dp),

                            horizontalArrangement = Arrangement.SpaceBetween,

                            verticalAlignment = Alignment.CenterVertically

                        ) {

                            Row(

                                verticalAlignment = Alignment.CenterVertically,

                                horizontalArrangement = Arrangement.spacedBy(10.dp)

                            ) {

                                Text(

                                    text = "Настройки",

                                    style = MaterialTheme.typography.headlineSmall,

                                    fontWeight = FontWeight.Bold,

                                    color = MaterialTheme.colorScheme.onSurface

                                )

                                Surface(

                                    shape = RoundedCornerShape(8.dp),

                                    color = MaterialTheme.colorScheme.primaryContainer

                                ) {

                                    Text(

                                        text = "v1.4.2",

                                        style = MaterialTheme.typography.labelMedium,

                                        fontWeight = FontWeight.Bold,

                                        color = MaterialTheme.colorScheme.onPrimaryContainer,

                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)

                                    )

                                }

                            }



                            FilledTonalIconButton(

                                onClick = onClose,

                                modifier = Modifier.size(36.dp),

                                colors = IconButtonDefaults.filledTonalIconButtonColors(

                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,

                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant

                                )

                            ) {

                                Text("✕", fontSize = 14.sp, fontWeight = FontWeight.Bold)

                            }

                        }



                        // Scrollable content

                        LazyColumn(

                            modifier = Modifier

                                .weight(1f)

                                .fillMaxWidth(),

                            verticalArrangement = Arrangement.spacedBy(14.dp)

                        ) {

                            // Section 1: Authorization

                            item {

                                Md3SectionCard(title = "Авторизация (Токен)") {

                                    OutlinedTextField(

                                        value = manualToken,

                                        onValueChange = { manualToken = it },

                                        label = { Text("OAuth-токен") },

                                        placeholder = { Text("Оставьте пустым для автопоиска") },

                                        singleLine = true,

                                        shape = RoundedCornerShape(14.dp),

                                        modifier = Modifier.fillMaxWidth(),

                                        colors = OutlinedTextFieldDefaults.colors(

                                            focusedBorderColor = MaterialTheme.colorScheme.primary,

                                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,

                                            focusedLabelColor = MaterialTheme.colorScheme.primary

                                        ),

                                        trailingIcon = {

                                            if (manualToken.isNotEmpty()) {

                                                IconButton(onClick = { manualToken = "" }) {

                                                    Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                                }

                                            }

                                        }

                                    )



                                    Spacer(modifier = Modifier.height(10.dp))



                                    // Status badge

                                    if (manualToken.isNotBlank()) {

                                        Surface(

                                            shape = RoundedCornerShape(10.dp),

                                            color = MaterialTheme.colorScheme.secondaryContainer,

                                            modifier = Modifier.fillMaxWidth()

                                        ) {

                                            Text(

                                                text = "• Используется ручной токен",

                                                style = MaterialTheme.typography.bodySmall,

                                                color = MaterialTheme.colorScheme.onSecondaryContainer,

                                                fontWeight = FontWeight.Medium,

                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)

                                            )

                                        }

                                    } else if (!autoDetectedToken.isNullOrBlank()) {

                                        val preview = if (autoDetectedToken!!.length > 20) {

                                            "${autoDetectedToken!!.take(12)}...${autoDetectedToken!!.takeLast(6)}"

                                        } else {

                                            autoDetectedToken!!

                                        }

                                        Surface(

                                            shape = RoundedCornerShape(10.dp),

                                            color = MaterialTheme.colorScheme.tertiaryContainer,

                                            modifier = Modifier.fillMaxWidth()

                                        ) {

                                            Text(

                                                text = "Токен активен (автоматически):\n$preview",

                                                style = MaterialTheme.typography.bodySmall,

                                                color = MaterialTheme.colorScheme.onTertiaryContainer,

                                                fontWeight = FontWeight.Medium,

                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)

                                            )

                                        }

                                    } else {

                                        Surface(

                                            shape = RoundedCornerShape(10.dp),

                                            color = MaterialTheme.colorScheme.errorContainer,

                                            modifier = Modifier.fillMaxWidth()

                                        ) {

                                            Text(

                                                text = "Токен пока не перехвачен. Запустите любой трек в плеере.",

                                                style = MaterialTheme.typography.bodySmall,

                                                color = MaterialTheme.colorScheme.onErrorContainer,

                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)

                                            )

                                        }

                                    }



                                    Spacer(modifier = Modifier.height(8.dp))



                                    Row(

                                        modifier = Modifier.fillMaxWidth(),

                                        horizontalArrangement = Arrangement.spacedBy(8.dp),

                                        verticalAlignment = Alignment.CenterVertically

                                    ) {

                                        FilledTonalButton(
                                        modifier = if (manualToken.isNotBlank()) Modifier.weight(1f) else Modifier.fillMaxWidth(),


                                            onClick = {

                                                val found = findToken(activity)

                                                autoDetectedToken = found

                                                if (found != null) {

                                                    Toast.makeText(activity, "Токен найден!", Toast.LENGTH_SHORT).show()

                                                } else {

                                                    Toast.makeText(activity, "Токен не найден. Запустите трек в плеере.", Toast.LENGTH_SHORT).show()

                                                }

                                            },

                                            shape = RoundedCornerShape(12.dp),

                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

                                        ) {

                                            Text("Найти токен", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)

                                        }



                                        if (manualToken.isNotBlank()) {

                                            OutlinedButton(
                                            modifier = Modifier.weight(1f),


                                                onClick = { manualToken = "" },

                                                shape = RoundedCornerShape(12.dp),

                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

                                            ) {

                                                Text("Сбросить", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)

                                            }

                                        }

                                    }

                                }

                            }



                            // Section 2: Audio & Cover

                            item {

                                Md3SectionCard(title = "Формат и Качество аудио") {

                                    Text(

                                        text = "Формат скачивания",

                                        style = MaterialTheme.typography.bodyMedium,

                                        color = MaterialTheme.colorScheme.onSurfaceVariant

                                    )

                                    Spacer(modifier = Modifier.height(8.dp))



                                    Row(

                                        horizontalArrangement = Arrangement.spacedBy(8.dp),

                                        modifier = Modifier.fillMaxWidth()

                                    ) {

                                        listOf("MP3" to "MP3", "FLAC" to "FLAC", "M4A" to "M4A").forEach { (fmt, label) ->

                                            FilterChip(

                                                modifier = Modifier

                                                    .weight(1f)

                                                    .semantics {

                                                        stateDescription = if (audioFormat == fmt) "Выбрано" else "Не выбрано"

                                                    },

                                                selected = audioFormat == fmt,

                                                onClick = { audioFormat = fmt },

                                                label = {

                                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {

                                                        Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)

                                                    }

                                                },

                                                shape = RoundedCornerShape(12.dp),

                                                colors = FilterChipDefaults.filterChipColors(

                                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,

                                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer

                                                )

                                            )

                                        }

                                    }



                                    Spacer(modifier = Modifier.height(10.dp))



                                    if (audioFormat == "MP3") {

                                        Text(

                                            text = "Битрейт MP3",

                                            style = MaterialTheme.typography.bodyMedium,

                                            color = MaterialTheme.colorScheme.onSurfaceVariant

                                        )

                                        Spacer(modifier = Modifier.height(8.dp))



                                        Row(

                                            horizontalArrangement = Arrangement.spacedBy(8.dp),

                                            modifier = Modifier.fillMaxWidth()

                                        ) {

                                            FilterChip(

                                                modifier = Modifier

                                                    .weight(1f)

                                                    .semantics {

                                                        stateDescription = if (quality == 320) "Выбрано" else "Не выбрано"

                                                    },

                                                selected = quality == 320,

                                                onClick = { quality = 320 },

                                                label = {

                                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {

                                                        Text("320 kbps (HQ)", maxLines = 1, softWrap = false, fontSize = 12.sp, fontWeight = FontWeight.Medium)

                                                    }

                                                },

                                                shape = RoundedCornerShape(12.dp),

                                                colors = FilterChipDefaults.filterChipColors(

                                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,

                                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer

                                                )

                                            )

                                            FilterChip(

                                                modifier = Modifier

                                                    .weight(1f)

                                                    .semantics {

                                                        stateDescription = if (quality == 192) "Выбрано" else "Не выбрано"

                                                    },

                                                selected = quality == 192,

                                                onClick = { quality = 192 },

                                                label = {

                                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {

                                                        Text("192 kbps (STD)", maxLines = 1, softWrap = false, fontSize = 12.sp, fontWeight = FontWeight.Medium)

                                                    }

                                                },

                                                shape = RoundedCornerShape(12.dp),

                                                colors = FilterChipDefaults.filterChipColors(

                                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,

                                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer

                                                )

                                            )

                                        }

                                    } else if (audioFormat == "FLAC") {

                                        Surface(

                                            shape = RoundedCornerShape(10.dp),

                                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)

                                        ) {

                                            Text(

                                                text = "Прямой поток FLAC Lossless студийного качества (до 1411+ kbps, без сжатия)",

                                                style = MaterialTheme.typography.bodySmall,

                                                color = MaterialTheme.colorScheme.onSurfaceVariant,

                                                modifier = Modifier.padding(10.dp)

                                            )

                                        }

                                    } else {

                                        Surface(

                                            shape = RoundedCornerShape(10.dp),

                                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)

                                        ) {

                                            Text(

                                                text = "Прямой поток AAC/M4A HQ (256-320 kbps, кристальный звук и максимальная совместимость)",

                                                style = MaterialTheme.typography.bodySmall,

                                                color = MaterialTheme.colorScheme.onSurfaceVariant,

                                                modifier = Modifier.padding(10.dp)

                                            )

                                        }

                                    }



                                    Spacer(modifier = Modifier.height(12.dp))



                                    Surface(

                                        modifier = Modifier

                                            .fillMaxWidth()

                                            .clip(RoundedCornerShape(14.dp))

                                            .clickable { cover = !cover },

                                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),

                                        shape = RoundedCornerShape(14.dp)

                                    ) {

                                        Row(

                                            modifier = Modifier

                                                .fillMaxWidth()

                                                .padding(horizontal = 14.dp, vertical = 12.dp),

                                            horizontalArrangement = Arrangement.SpaceBetween,

                                            verticalAlignment = Alignment.CenterVertically

                                        ) {

                                            Column(modifier = Modifier.weight(1f)) {

                                                Text(

                                                    text = "Вшивать обложку в MP3",

                                                    style = MaterialTheme.typography.bodyLarge,

                                                    fontWeight = FontWeight.Medium,

                                                    color = MaterialTheme.colorScheme.onSurface

                                                )

                                                Text(

                                                    text = "Сохраняет HD арт и ID3v2.4 теги",

                                                    style = MaterialTheme.typography.bodySmall,

                                                    color = MaterialTheme.colorScheme.onSurfaceVariant

                                                )

                                            }

                                            Switch(

                                                modifier = Modifier.semantics {

                                                    stateDescription = if (cover) "Включено" else "Выключено"

                                                },

                                                checked = cover,

                                                onCheckedChange = { cover = it }

                                            )

                                        }

                                    }

                                }

                            }



                            // Section 3: Download Destination

                            item {

                                Md3SectionCard(title = "Папка для загрузок") {

                                    val options = listOf(

                                        "Music" to "Стандартная папка (Music/YaMusicDL)",

                                        "Downloads" to "Загрузки (Downloads/YaMusicDL)",

                                        "Custom" to "Пользовательская папка"

                                    )



                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {

                                        options.forEach { (type, label) ->

                                            Surface(

                                                modifier = Modifier

                                                    .fillMaxWidth()

                                                    .clip(RoundedCornerShape(12.dp))

                                                    .clickable { folderType = type },

                                                color = if (folderType == type) {

                                                    MaterialTheme.colorScheme.surfaceContainerHighest

                                                } else {

                                                    Color.Transparent

                                                },

                                                shape = RoundedCornerShape(12.dp)

                                            ) {

                                                Row(

                                                    modifier = Modifier

                                                        .fillMaxWidth()

                                                        .padding(horizontal = 8.dp, vertical = 8.dp),

                                                    verticalAlignment = Alignment.CenterVertically

                                                ) {

                                                    RadioButton(

                                                        modifier = Modifier.semantics {

                                                            stateDescription = if (folderType == type) "Выбрано" else "Не выбрано"

                                                        },

                                                        selected = folderType == type,

                                                        onClick = { folderType = type }

                                                    )

                                                    Spacer(modifier = Modifier.width(8.dp))

                                                    Text(

                                                        text = label,

                                                        style = MaterialTheme.typography.bodyMedium,

                                                        color = MaterialTheme.colorScheme.onSurface

                                                    )

                                                }

                                            }

                                        }

                                    }



                                    if (folderType == "Custom") {

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Surface(

                                            modifier = Modifier

                                                .fillMaxWidth()

                                                .clip(RoundedCornerShape(12.dp))

                                                .clickable { isFolderPickerOpen = true },

                                            color = MaterialTheme.colorScheme.surfaceContainerHighest,

                                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

                                        ) {

                                            Row(

                                                modifier = Modifier

                                                    .fillMaxWidth()

                                                    .padding(14.dp),

                                                verticalAlignment = Alignment.CenterVertically

                                            ) {

                                                Text(

                                                    text = customFolder.ifEmpty { "Нажмите для выбора папки..." },

                                                    style = MaterialTheme.typography.bodyMedium,

                                                    color = if (customFolder.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,

                                                    maxLines = 1,

                                                    overflow = TextOverflow.Ellipsis

                                                )

                                            }

                                        }

                                    }

                                }

                            }



                            // Section: YaMusicCacheDecryptor

                            item {

                                Md3SectionCard(title = "Дешифратор оффлайн-кэша (AES-128)") {

                                    Text(

                                        text = "YaMusicCacheDecryptor",

                                        style = MaterialTheme.typography.bodyLarge,

                                        fontWeight = FontWeight.Bold,

                                        color = MaterialTheme.colorScheme.primary

                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(

                                        text = "Позволяет извлечь и расшифровать сохраненные оффлайн-треки из локального кэша Яндекс Музыки в FLAC, M4A или MP3 с использованием API-токена аккаунта.",

                                        style = MaterialTheme.typography.bodySmall,

                                        color = MaterialTheme.colorScheme.onSurfaceVariant

                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    FilledTonalButton(

                                        onClick = { isDecryptorOpen = true },

                                        modifier = Modifier.fillMaxWidth(),

                                        shape = RoundedCornerShape(12.dp)

                                    ) {

                                        Text("Открыть дешифратор оффлайн-кэша", style = MaterialTheme.typography.labelLarge)

                                    }

                                }

                            }



                            // Section 4: Updates & Changelog

                            item {

                                Md3SectionCard(title = "Обновления") {

                                    Row(

                                        modifier = Modifier.fillMaxWidth(),

                                        horizontalArrangement = Arrangement.SpaceBetween,

                                        verticalAlignment = Alignment.CenterVertically

                                    ) {

                                        Column(modifier = Modifier.weight(1f)) {

                                            Text(

                                                text = "Текущая версия: v1.4.2 (код 7)",

                                                style = MaterialTheme.typography.bodyMedium,

                                                fontWeight = FontWeight.Medium,

                                                color = MaterialTheme.colorScheme.onSurface

                                            )

                                            Spacer(modifier = Modifier.height(2.dp))

                                            Text(

                                                text = when (val state = updateState) {

                                                    is UpdateState.Checking -> "Проверка обновлений на GitHub..."

                                                    is UpdateState.Available -> "Доступна версия ${state.updateInfo.latestVersionName}!"

                                                    is UpdateState.UpToDate -> "Установлена актуальная версия"

                                                    is UpdateState.Error -> "Ошибка проверки: ${state.message}"

                                                    UpdateState.Idle -> "Нажмите «Проверить» для поиска"

                                                },

                                                style = MaterialTheme.typography.bodySmall,

                                                color = when (updateState) {

                                                    is UpdateState.Available -> MaterialTheme.colorScheme.primary

                                                    is UpdateState.UpToDate -> Color(0xFF81C784)

                                                    is UpdateState.Error -> MaterialTheme.colorScheme.error

                                                    else -> MaterialTheme.colorScheme.onSurfaceVariant

                                                }

                                            )

                                        }



                                        if (updateState is UpdateState.Checking) {

                                            Text(

                                                text = "...",

                                                style = MaterialTheme.typography.titleMedium,

                                                fontWeight = FontWeight.Bold,

                                                color = MaterialTheme.colorScheme.primary

                                            )

                                        }

                                    }



                                    // If update is available:

                                    if (updateState is UpdateState.Available) {

                                        val info = (updateState as UpdateState.Available).updateInfo

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Surface(

                                            modifier = Modifier.fillMaxWidth(),

                                            shape = RoundedCornerShape(12.dp),

                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),

                                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)

                                        ) {

                                            Column(modifier = Modifier.padding(12.dp)) {

                                                Text(

                                                    text = "Доступно обновление: ${info.releaseTitle}",

                                                    style = MaterialTheme.typography.titleSmall,

                                                    fontWeight = FontWeight.Bold,

                                                    color = MaterialTheme.colorScheme.primary

                                                )

                                                Spacer(modifier = Modifier.height(8.dp))

                                                Row(

                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),

                                                    modifier = Modifier.fillMaxWidth()

                                                ) {

                                                    Button(

                                                        onClick = {

                                                            openUrl(activity, info.downloadUrl ?: info.releaseUrl)

                                                        },

                                                        shape = RoundedCornerShape(10.dp),

                                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),

                                                        colors = ButtonDefaults.buttonColors(

                                                            containerColor = MaterialTheme.colorScheme.primary,

                                                            contentColor = MaterialTheme.colorScheme.onPrimary

                                                        )

                                                    ) {

                                                        Text("Скачать APK", style = MaterialTheme.typography.labelMedium)

                                                    }

                                                    OutlinedButton(

                                                        onClick = { isChangelogOpen = true },

                                                        shape = RoundedCornerShape(10.dp),

                                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)

                                                    ) {

                                                        Text("Что нового", style = MaterialTheme.typography.labelMedium)

                                                    }

                                                }

                                            }

                                        }

                                    }



                                    Spacer(modifier = Modifier.height(10.dp))



                                    Spacer(modifier = Modifier.height(10.dp))

                                    Text(

                                        text = "Канал обновлений:",

                                        style = MaterialTheme.typography.bodySmall,

                                        fontWeight = FontWeight.Medium,

                                        color = MaterialTheme.colorScheme.onSurfaceVariant

                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(

                                        horizontalArrangement = Arrangement.spacedBy(8.dp),

                                        modifier = Modifier.fillMaxWidth()

                                    ) {

                                        listOf(

                                            "release" to "Release",

                                            "pre" to "Pre-release",

                                            "dev" to "Dev"

                                        ).forEach { (ch, label) ->

                                            val isSelected = (updateChannel == ch)

                                            FilterChip(

                                                selected = isSelected,

                                                onClick = {

                                                    updateChannel = ch

                                                    activity.getSharedPreferences("YaMusicDL_Prefs", Context.MODE_PRIVATE)

                                                        .edit().putString("update_channel", ch).apply()

                                                    coroutineScope.launch {

                                                        updateState = UpdateState.Checking

                                                        withContext(Dispatchers.IO) {

                                                            updateState = fetchLatestRelease(7, "1.4.2", ch)

                                                        }

                                                    }

                                                },

                                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },

                                                shape = RoundedCornerShape(8.dp)

                                            )

                                        }

                                    }



                                    Spacer(modifier = Modifier.height(10.dp))



                                    Row(

                                        horizontalArrangement = Arrangement.spacedBy(8.dp),

                                        modifier = Modifier.fillMaxWidth()

                                    ) {

                                        FilledTonalButton(

                                            onClick = {

                                                coroutineScope.launch {

                                                    updateState = UpdateState.Checking

                                                    withContext(Dispatchers.IO) {

                                                        updateState = fetchLatestRelease(7, "1.4.2", updateChannel)

                                                    }

                                                }

                                            },

                                            enabled = updateState !is UpdateState.Checking,

                                            shape = RoundedCornerShape(10.dp),

                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

                                        ) {

                                            Text("Проверить", style = MaterialTheme.typography.labelMedium)

                                        }



                                        AssistChip(

                                            onClick = { isChangelogOpen = true },

                                            label = { Text("Чейнджлог") },

                                            shape = RoundedCornerShape(10.dp)

                                        )

                                    }

                                }

                            }



                            // Section 5: About

                            item {

                                Md3SectionCard(title = "О модуле (YaMusicLsposed)") {

                                    Text(

                                        text = "Версия 1.4.2 (код 7) • Разработчик: Windyx0",

                                        style = MaterialTheme.typography.bodyMedium,

                                        fontWeight = FontWeight.Medium,

                                        color = MaterialTheme.colorScheme.onSurface

                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Column(

                                        verticalArrangement = Arrangement.spacedBy(6.dp),

                                        modifier = Modifier.fillMaxWidth()

                                    ) {

                                        Row(

                                            horizontalArrangement = Arrangement.spacedBy(8.dp),

                                            modifier = Modifier.fillMaxWidth()

                                        ) {

                                            AssistChip(

                                                modifier = Modifier.weight(1f),

                                                onClick = {

                                                    openUrl(activity, "https://github.com/Xposed-Modules-Repo/io.github.windyx0.yamusiclsposed")

                                                },

                                                label = {

                                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {

                                                        Text("GitHub", maxLines = 1, softWrap = false, fontSize = 12.sp)

                                                    }

                                                },

                                                shape = RoundedCornerShape(10.dp)

                                            )

                                            AssistChip(

                                                modifier = Modifier.weight(1f),

                                                onClick = {

                                                    openUrl(activity, "https://t.me/WindyxChannel")

                                                },

                                                label = {

                                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {

                                                        Text("Канал (TG)", maxLines = 1, softWrap = false, fontSize = 12.sp)

                                                    }

                                                },

                                                shape = RoundedCornerShape(10.dp)

                                            )

                                        }

                                        Row(

                                            horizontalArrangement = Arrangement.spacedBy(8.dp),

                                            modifier = Modifier.fillMaxWidth()

                                        ) {

                                            AssistChip(

                                                modifier = Modifier.weight(1f),

                                                onClick = {

                                                    openUrl(activity, "https://t.me/Windyx0")

                                                },

                                                label = {

                                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {

                                                        Text("ЛС (TG)", maxLines = 1, softWrap = false, fontSize = 12.sp)

                                                    }

                                                },

                                                shape = RoundedCornerShape(10.dp)

                                            )

                                            AssistChip(

                                                modifier = Modifier.weight(1f),

                                                onClick = {

                                                    openUrl(activity, "https://www.tiktok.com/@windyx_edits")

                                                },

                                                label = {

                                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {

                                                        Text("TikTok", maxLines = 1, softWrap = false, fontSize = 12.sp)

                                                    }

                                                },

                                                shape = RoundedCornerShape(10.dp)

                                            )

                                        }

                                        Row(

                                            horizontalArrangement = Arrangement.spacedBy(8.dp),

                                            modifier = Modifier.fillMaxWidth()

                                        ) {

                                            AssistChip(

                                                modifier = Modifier.fillMaxWidth(),

                                                onClick = {

                                                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager

                                                    val clip = android.content.ClipData.newPlainText("Sber Card", "2202208864715629")

                                                    clipboard?.setPrimaryClip(clip)

                                                    Toast.makeText(activity, "Номер карты Сбера скопирован!", Toast.LENGTH_SHORT).show()

                                                },

                                                label = {

                                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {

                                                        Text("Сбер: 2202 2088 6471 5629", maxLines = 1, softWrap = false, fontSize = 12.sp, fontWeight = FontWeight.Medium)

                                                    }

                                                },

                                                shape = RoundedCornerShape(10.dp)

                                            )

                                        }

                                    }

                                }

                            }

                        }



                        // Bottom Actions

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(

                            modifier = Modifier.fillMaxWidth(),

                            horizontalArrangement = Arrangement.SpaceBetween,

                            verticalAlignment = Alignment.CenterVertically

                        ) {

                            TextButton(

                                onClick = onClose,

                                shape = RoundedCornerShape(20.dp),

                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

                            ) {

                                Text("Отмена", style = MaterialTheme.typography.labelLarge)

                            }



                            Button(

                                onClick = {

                                    onSave(

                                        Config(

                                            quality = quality,

                                            cover = cover,

                                            coverSize = coverSize,

                                            folderType = folderType,

                                            customFolder = customFolder,

                                            manualToken = manualToken,

                                            updateChannel = updateChannel,

                                            audioFormat = audioFormat

                                        )

                                    )

                                },

                                shape = RoundedCornerShape(20.dp),

                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),

                                colors = ButtonDefaults.buttonColors(

                                    containerColor = MaterialTheme.colorScheme.primary,

                                    contentColor = MaterialTheme.colorScheme.onPrimary

                                )

                            ) {

                                Text("Сохранить", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)

                            }

                        }

                    }

                }

            }

        }

    }

}



@Composable

fun Md3SectionCard(

    title: String,

    content: @Composable ColumnScope.() -> Unit

) {

    Card(

        modifier = Modifier.fillMaxWidth(),

        shape = RoundedCornerShape(20.dp),

        colors = CardDefaults.cardColors(

            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh

        )

    ) {

        Column(

            modifier = Modifier

                .fillMaxWidth()

                .padding(16.dp)

        ) {

            Text(

                text = title,

                style = MaterialTheme.typography.titleMedium,

                fontWeight = FontWeight.SemiBold,

                color = MaterialTheme.colorScheme.primary,

                modifier = Modifier.padding(bottom = 12.dp)

            )

            content()

        }

    }

}



@Composable

fun SettingsCard(cardColor: Color = Color.Transparent, backgroundBitmap: Bitmap? = null, content: @Composable ColumnScope.() -> Unit) {

    Md3SectionCard(title = "", content = content)

}



@Composable

fun FolderPickerScreen(

    initialPath: String,

    onPathSelected: (String) -> Unit,

    onCancel: () -> Unit

) {

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



    Surface(

        modifier = Modifier

            .fillMaxWidth(0.92f)

            .fillMaxHeight(0.85f),

        shape = RoundedCornerShape(28.dp),

        color = MaterialTheme.colorScheme.surfaceContainer,

        tonalElevation = 6.dp,

        shadowElevation = 10.dp,

        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

    ) {

        Column(

            modifier = Modifier

                .fillMaxSize()

                .padding(20.dp)

        ) {

            Text(

                text = "Выбор папки",

                style = MaterialTheme.typography.headlineSmall,

                fontWeight = FontWeight.Bold,

                color = MaterialTheme.colorScheme.onSurface

            )



            Spacer(modifier = Modifier.height(8.dp))



            Surface(

                shape = RoundedCornerShape(10.dp),

                color = MaterialTheme.colorScheme.surfaceContainerHighest,

                modifier = Modifier.fillMaxWidth()

            ) {

                Text(

                    text = currentPath.absolutePath,

                    style = MaterialTheme.typography.bodySmall,

                    color = MaterialTheme.colorScheme.onSurfaceVariant,

                    maxLines = 1,

                    overflow = TextOverflow.Ellipsis,

                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)

                )

            }



            Spacer(modifier = Modifier.height(10.dp))



            LazyColumn(modifier = Modifier.weight(1f)) {

                if (!isRoot && currentPath.parentFile != null) {

                    item {

                        Surface(

                            modifier = Modifier

                                .fillMaxWidth()

                                .clip(RoundedCornerShape(12.dp))

                                .clickable { currentPath = currentPath.parentFile ?: currentPath },

                            color = Color.Transparent

                        ) {

                            Text(

                                text = "↩ .. (Вверх)",

                                style = MaterialTheme.typography.bodyLarge,

                                fontWeight = FontWeight.Medium,

                                color = MaterialTheme.colorScheme.primary,

                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)

                            )

                        }

                    }

                }

                items(files) { file ->

                    Surface(

                        modifier = Modifier

                            .fillMaxWidth()

                            .clip(RoundedCornerShape(12.dp))

                            .clickable { currentPath = file },

                        color = Color.Transparent

                    ) {

                        Row(

                            modifier = Modifier

                                .fillMaxWidth()

                                .padding(vertical = 12.dp, horizontal = 8.dp),

                            verticalAlignment = Alignment.CenterVertically

                        ) {

                            Text(

                                text = file.name,

                                style = MaterialTheme.typography.bodyMedium,

                                color = MaterialTheme.colorScheme.onSurface,

                                maxLines = 1,

                                overflow = TextOverflow.Ellipsis

                            )

                        }

                    }

                }

            }



            Spacer(modifier = Modifier.height(14.dp))



            Row(

                modifier = Modifier.fillMaxWidth(),

                horizontalArrangement = Arrangement.SpaceBetween,

                verticalAlignment = Alignment.CenterVertically

            ) {

                TextButton(

                    onClick = onCancel,

                    shape = RoundedCornerShape(20.dp)

                ) {

                    Text("Отмена", style = MaterialTheme.typography.labelLarge)

                }



                Button(

                    onClick = { onPathSelected(currentPath.absolutePath) },

                    shape = RoundedCornerShape(20.dp),

                    colors = ButtonDefaults.buttonColors(

                        containerColor = MaterialTheme.colorScheme.primary,

                        contentColor = MaterialTheme.colorScheme.onPrimary

                    )

                ) {

                    Text("Выбрать эту папку", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)

                }

            }

        }

    }

}



@Composable

fun ChangelogScreen(

    onClose: () -> Unit,

    latestReleaseBody: String? = null

) {

    Surface(

        modifier = Modifier

            .fillMaxWidth(0.92f)

            .fillMaxHeight(0.88f)

            .clickable(

                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },

                indication = null

            ) {},

        shape = RoundedCornerShape(28.dp),

        color = MaterialTheme.colorScheme.surfaceContainer,

        tonalElevation = 6.dp,

        shadowElevation = 10.dp,

        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

    ) {

        Column(

            modifier = Modifier

                .fillMaxSize()

                .padding(20.dp)

        ) {

            // Header

            Row(

                modifier = Modifier.fillMaxWidth(),

                horizontalArrangement = Arrangement.SpaceBetween,

                verticalAlignment = Alignment.CenterVertically

            ) {

                Text(

                    text = "История изменений",

                    style = MaterialTheme.typography.headlineSmall,

                    fontWeight = FontWeight.Bold,

                    color = MaterialTheme.colorScheme.onSurface

                )



                FilledTonalIconButton(

                    onClick = onClose,

                    modifier = Modifier.size(36.dp),

                    colors = IconButtonDefaults.filledTonalIconButtonColors(

                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,

                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant

                    )

                ) {

                    Text("✕", fontSize = 14.sp, fontWeight = FontWeight.Bold)

                }

            }



            Spacer(modifier = Modifier.height(14.dp))



            LazyColumn(

                modifier = Modifier

                    .weight(1f)

                    .fillMaxWidth(),

                verticalArrangement = Arrangement.spacedBy(12.dp)

            ) {

                if (!latestReleaseBody.isNullOrBlank()) {

                    item {

                        Surface(

                            modifier = Modifier.fillMaxWidth(),

                            shape = RoundedCornerShape(16.dp),

                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),

                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)

                        ) {

                            Column(modifier = Modifier.padding(14.dp)) {

                                Text(

                                    text = "Заметки к последнему релизу на GitHub",

                                    style = MaterialTheme.typography.titleSmall,

                                    fontWeight = FontWeight.Bold,

                                    color = MaterialTheme.colorScheme.primary

                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(

                                    text = latestReleaseBody.trim(),

                                    style = MaterialTheme.typography.bodySmall,

                                    color = MaterialTheme.colorScheme.onSurface

                                )

                            }

                        }

                    }

                }



                items(changelogHistory) { entry ->

                    Surface(

                        modifier = Modifier.fillMaxWidth(),

                        shape = RoundedCornerShape(16.dp),

                        color = if (entry.isCurrent) {

                            MaterialTheme.colorScheme.surfaceContainerHigh

                        } else {

                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)

                        },

                        border = if (entry.isCurrent) {

                            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))

                        } else null

                    ) {

                        Column(

                            modifier = Modifier

                                .fillMaxWidth()

                                .padding(16.dp)

                        ) {

                            Row(

                                modifier = Modifier.fillMaxWidth(),

                                horizontalArrangement = Arrangement.SpaceBetween,

                                verticalAlignment = Alignment.CenterVertically

                            ) {

                                Row(

                                    verticalAlignment = Alignment.CenterVertically,

                                    horizontalArrangement = Arrangement.spacedBy(8.dp)

                                ) {

                                    Text(

                                        text = entry.version,

                                        style = MaterialTheme.typography.titleMedium,

                                        fontWeight = FontWeight.Bold,

                                        color = MaterialTheme.colorScheme.onSurface

                                    )

                                    if (entry.isCurrent) {

                                        Surface(

                                            shape = RoundedCornerShape(6.dp),

                                            color = MaterialTheme.colorScheme.primary

                                        ) {

                                            Text(

                                                text = "ТЕКУЩАЯ",

                                                style = MaterialTheme.typography.labelSmall,

                                                fontWeight = FontWeight.Bold,

                                                color = MaterialTheme.colorScheme.onPrimary,

                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)

                                            )

                                        }

                                    }

                                }

                                Text(

                                    text = entry.date,

                                    style = MaterialTheme.typography.labelMedium,

                                    color = MaterialTheme.colorScheme.onSurfaceVariant

                                )

                            }



                            Spacer(modifier = Modifier.height(10.dp))



                            entry.points.forEach { point ->

                                Row(

                                    modifier = Modifier

                                        .fillMaxWidth()

                                        .padding(vertical = 3.dp),

                                    verticalAlignment = Alignment.Top

                                ) {

                                    Text(

                                        text = "•",

                                        style = MaterialTheme.typography.bodyMedium,

                                        color = MaterialTheme.colorScheme.primary,

                                        modifier = Modifier.padding(end = 8.dp)

                                    )

                                    Text(

                                        text = point,

                                        style = MaterialTheme.typography.bodySmall,

                                        color = MaterialTheme.colorScheme.onSurfaceVariant

                                    )

                                }

                            }

                        }

                    }

                }

            }



            Spacer(modifier = Modifier.height(14.dp))



            Button(

                onClick = onClose,

                modifier = Modifier.fillMaxWidth(),

                shape = RoundedCornerShape(20.dp),

                colors = ButtonDefaults.buttonColors(

                    containerColor = MaterialTheme.colorScheme.primary,

                    contentColor = MaterialTheme.colorScheme.onPrimary

                )

            ) {

                Text("Закрыть", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)

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

    backgroundBitmap: Bitmap? = null,

    onClose: () -> Unit

) {

    YaMusicTheme {

        Box(

            modifier = Modifier

                .fillMaxSize()

                .background(Color(0x8A000000))

                .clickable(onClick = onClose),

            contentAlignment = Alignment.Center

        ) {

            Surface(

                modifier = Modifier

                    .fillMaxWidth(0.92f)

                    .fillMaxHeight(0.75f)

                    .clickable(enabled = false) {},

                shape = RoundedCornerShape(28.dp),

                color = MaterialTheme.colorScheme.surfaceContainer,

                tonalElevation = 6.dp,

                shadowElevation = 10.dp,

                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            ) {

                Column(

                    modifier = Modifier

                        .fillMaxSize()

                        .padding(20.dp)

                ) {

                    // Header

                    Row(

                        modifier = Modifier

                            .fillMaxWidth()

                            .padding(bottom = 12.dp),

                        horizontalArrangement = Arrangement.SpaceBetween,

                        verticalAlignment = Alignment.CenterVertically

                    ) {

                        Text(

                            text = "Активные загрузки",

                            style = MaterialTheme.typography.headlineSmall,

                            fontWeight = FontWeight.Bold,

                            color = MaterialTheme.colorScheme.onSurface

                        )



                        FilledTonalIconButton(

                            onClick = onClose,

                            modifier = Modifier.size(36.dp),

                            colors = IconButtonDefaults.filledTonalIconButtonColors(

                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,

                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant

                            )

                        ) {

                            Text("✕", fontSize = 14.sp, fontWeight = FontWeight.Bold)

                        }

                    }



                    if (DownloadState.activeDownloads.isEmpty()) {

                        Box(

                            modifier = Modifier

                                .weight(1f)

                                .fillMaxWidth(),

                            contentAlignment = Alignment.Center

                        ) {

                            Text(

                                text = "Нет активных загрузок",

                                style = MaterialTheme.typography.bodyLarge,

                                color = MaterialTheme.colorScheme.onSurfaceVariant

                            )

                        }

                    } else {

                        LazyColumn(

                            modifier = Modifier.weight(1f),

                            verticalArrangement = Arrangement.spacedBy(10.dp)

                        ) {

                            items(DownloadState.activeDownloads) { download ->

                                Card(

                                    shape = RoundedCornerShape(16.dp),

                                    colors = CardDefaults.cardColors(

                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh

                                    ),

                                    modifier = Modifier.fillMaxWidth()

                                ) {

                                    Column(

                                        modifier = Modifier

                                            .fillMaxWidth()

                                            .padding(14.dp)

                                    ) {

                                        Text(

                                            text = download.title,

                                            style = MaterialTheme.typography.titleMedium,

                                            fontWeight = FontWeight.Bold,

                                            color = MaterialTheme.colorScheme.onSurface,

                                            maxLines = 1,

                                            overflow = TextOverflow.Ellipsis

                                        )

                                        Text(

                                            text = download.artist,

                                            style = MaterialTheme.typography.bodyMedium,

                                            color = MaterialTheme.colorScheme.onSurfaceVariant,

                                            maxLines = 1,

                                            overflow = TextOverflow.Ellipsis

                                        )

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(

                                            verticalAlignment = Alignment.CenterVertically,

                                            horizontalArrangement = Arrangement.spacedBy(10.dp),

                                            modifier = Modifier.fillMaxWidth()

                                        ) {

                                            LinearProgressIndicator(

                                                progress = download.progress / 100f,

                                                modifier = Modifier

                                                    .weight(1f)

                                                    .height(8.dp)

                                                    .clip(RoundedCornerShape(4.dp)),

                                                color = MaterialTheme.colorScheme.primary,

                                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

                                            )

                                            Text(

                                                text = "${download.progress.toInt()}%",

                                                style = MaterialTheme.typography.labelMedium,

                                                fontWeight = FontWeight.Bold,

                                                color = MaterialTheme.colorScheme.primary

                                            )

                                        }

                                    }

                                }

                            }

                        }

                    }



                    Spacer(modifier = Modifier.height(14.dp))



                    Row(

                        modifier = Modifier.fillMaxWidth(),

                        horizontalArrangement = Arrangement.End

                    ) {

                        Button(

                            onClick = onClose,

                            shape = RoundedCornerShape(20.dp),

                            colors = ButtonDefaults.buttonColors(

                                containerColor = MaterialTheme.colorScheme.primary,

                                contentColor = MaterialTheme.colorScheme.onPrimary

                            )

                        ) {

                            Text("Закрыть", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)

                        }

                    }

                }

            }

        }

    }

}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheDecryptorScreen(
    onClose: () -> Unit,
    activity: Activity,
    token: String,
    targetFolder: String
) {
    var allTrackGroups by remember { mutableStateOf<List<CachedTrackGroup>>(emptyList()) }
    var selectedTab by remember { mutableStateOf(0) } // 0: Offline, 1: All
    var searchQuery by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }
    var decryptProgress by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var decryptStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isProcessingAll by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val scan = {
        coroutineScope.launch {
            isScanning = true
            val items = withContext(Dispatchers.IO) {
                YaMusicCacheDecryptor.scanCacheTracks(activity)
            }
            allTrackGroups = items
            isScanning = false
        }
    }

    LaunchedEffect(Unit) {
        scan()
    }

    val offlineCount = remember(allTrackGroups) { allTrackGroups.count { it.isPermanent } }
    val totalCount = remember(allTrackGroups) { allTrackGroups.size }

    val filteredTracks = remember(allTrackGroups, selectedTab, searchQuery) {
        allTrackGroups.filter { track ->
            val matchesTab = if (selectedTab == 0) track.isPermanent else true
            val matchesSearch = if (searchQuery.isBlank()) true else {
                track.artist.contains(searchQuery, ignoreCase = true) ||
                track.title.contains(searchQuery, ignoreCase = true) ||
                track.trackId.contains(searchQuery)
            }
            matchesTab && matchesSearch
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.92f)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) {},
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Дешифратор кэша",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "AES-128",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                FilledTonalIconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("✕", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tabs row: Offline vs All
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("Оффлайн ($offlineCount)", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("Все в кэше ($totalCount)", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск трека или артиста...", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = { scan() },
                    enabled = !isScanning && !isProcessingAll,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        if (isScanning) "Поиск..." else "Обновить",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                if (filteredTracks.isNotEmpty()) {
                    Button(
                        onClick = {
                            if (token.isBlank()) {
                                Toast.makeText(activity, "Требуется токен авторизации!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            coroutineScope.launch {
                                isProcessingAll = true
                                withContext(Dispatchers.IO) {
                                    for (item in filteredTracks) {
                                        YaMusicCacheDecryptor.decryptAndExportTrackGroup(
                                            context = activity,
                                            group = item,
                                            token = token,
                                            targetDir = File(targetFolder),
                                            onProgress = { p ->
                                                decryptProgress = decryptProgress + (item.trackId to p)
                                            }
                                        ).let { res ->
                                            decryptStatus = decryptStatus + (item.trackId to (if (res.success) "Готово: ${res.exportedFile?.name}" else res.message))
                                        }
                                    }
                                }
                                isProcessingAll = false
                            }
                        },
                        enabled = !isProcessingAll && token.isNotBlank(),
                        modifier = Modifier.weight(1.8f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (isProcessingAll) "Экспорт..." else "Экспорт всех (${filteredTracks.size})",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Найдено треков: ${filteredTracks.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Track list
            if (filteredTracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isScanning) "Сканирование кэша..." else if (selectedTab == 0) "Нет сохраненных оффлайн-треков.\nСкачайте треки в приложении для оффлайн-доступа." else "Кэшированных треков не найдено.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredTracks, key = { "${it.trackId}_${it.exoId}" }) { track ->
                        val currentProgress = decryptProgress[track.trackId] ?: 0f
                        val currentStatus = decryptStatus[track.trackId]

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = track.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = track.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (track.isPermanent) Color(0xFF2E7D32).copy(alpha = 0.25f) else Color.Gray.copy(alpha = 0.2f)
                                            ) {
                                                Text(
                                                    text = if (track.isPermanent) "Оффлайн" else "Поток",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (track.isPermanent) Color(0xFF81C784) else Color.LightGray,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                            Text(
                                                text = "${track.formattedSize} • ${track.codec.uppercase()}${if (track.formattedDate.isNotEmpty()) " • ${track.formattedDate}" else ""}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Button(
                                        onClick = {
                                            if (token.isBlank()) {
                                                Toast.makeText(activity, "Требуется токен авторизации!", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            coroutineScope.launch {
                                                withContext(Dispatchers.IO) {
                                                    val res = YaMusicCacheDecryptor.decryptAndExportTrackGroup(
                                                        context = activity,
                                                        group = track,
                                                        token = token,
                                                        targetDir = File(targetFolder),
                                                        onProgress = { p ->
                                                            decryptProgress = decryptProgress + (track.trackId to p)
                                                        }
                                                    )
                                                    decryptStatus = decryptStatus + (track.trackId to (if (res.success) "Готово: ${res.exportedFile?.name}" else res.message))
                                                }
                                            }
                                        },
                                        enabled = !isProcessingAll && token.isNotBlank(),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = if (currentProgress in 1f..99f) "${currentProgress.toInt()}%" else "Экспорт",
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }

                                if (currentProgress > 0f) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = currentProgress / 100f,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                if (!currentStatus.isNullOrEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = currentStatus,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (currentStatus.startsWith("Готово")) Color(0xFF81C784) else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
