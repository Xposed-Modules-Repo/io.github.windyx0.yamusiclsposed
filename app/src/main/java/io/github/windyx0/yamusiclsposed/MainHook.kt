package io.github.windyx0.yamusiclsposed

import android.accounts.AccountManager
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadata
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.media.MediaScannerConnection
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.FileOutputStream
import java.nio.ByteOrder
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import android.database.sqlite.SQLiteDatabase
import android.database.Cursor
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

data class Config(
    val quality: Int,
    val cover: Boolean,
    val coverSize: String,
    val folderType: String,
    val customFolder: String,
    val manualToken: String,
    val updateChannel: String = "release"
)

fun saveConfig(activity: Activity, config: Config) {
    val prefs = activity.getSharedPreferences("YaMusicDL_Prefs", Context.MODE_PRIVATE)
    prefs.edit()
        .putInt("quality", config.quality)
        .putBoolean("download_cover", config.cover)
        .putString("cover_size", config.coverSize)
        .putString("folder_type", config.folderType)
        .putString("custom_folder", config.customFolder)
        .putString("token", config.manualToken)
        .putString("update_channel", config.updateChannel)
        .commit()

    if (config.manualToken.isNotBlank()) {
        MainHook.onTokenFound(config.manualToken, "Manual Config", activity)
    } else {
        MainHook.cachedToken = null
        try {
            File(activity.filesDir, "yamusic_lsposed_token.txt").delete()
            File(activity.applicationInfo.dataDir, "yamusic_lsposed_token.txt").delete()
        } catch (e: Throwable) {}
        thread {
            MainHook.findToken(activity)
        }
    }
}

fun loadConfig(activity: Activity): Config {
    val prefs = activity.getSharedPreferences("YaMusicDL_Prefs", Context.MODE_PRIVATE)
    return Config(
        quality = prefs.getInt("quality", 320),
        cover = prefs.getBoolean("download_cover", true),
        coverSize = prefs.getString("cover_size", "1000x1000") ?: "1000x1000",
        folderType = prefs.getString("folder_type", "Music") ?: "Music",
        customFolder = prefs.getString("custom_folder", "") ?: "",
        manualToken = prefs.getString("token", "") ?: "",
        updateChannel = prefs.getString("update_channel", "release") ?: "release"
    )
}

fun findToken(context: Context): String? = MainHook.findToken(context)

fun getDownloadPath(config: Config): String {
    return when (config.folderType) {
        "Downloads" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/YaMusicDL"
        "Custom" -> config.customFolder.ifEmpty { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath + "/YaMusicDL" }
        else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath + "/YaMusicDL"
    }
}

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        var modulePath: String? = null
        var appContext: Context? = null
        var currentTitle: String? = null
        var currentArtist: String? = null
        var currentMediaId: String? = null
        var currentAlbumArtUri: String? = null
        var currentDuration: Long = 0L
        var cachedToken: String? = null
        var cachedUserId: String? = null
        var cachedPlaylistId: String? = null
        var cachedAlbumId: String? = null

        @Volatile var isPlaylistDownloading = false
        @Volatile var cancelPlaylistDownload = false

        data class TrackStreamInfo(
            val trackId: String,
            val key: String,
            val codec: String,
            val url: String
        )
        val capturedStreamInfos = ConcurrentHashMap<String, TrackStreamInfo>()

        fun hexStringToByteArray(s: String): ByteArray {
            val len = s.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        fun parseAndSaveFileInfo(bodyString: String, context: Context?) {
            try {
                val json = org.json.JSONObject(bodyString)
                val dlInfo = json.optJSONObject("result")?.optJSONObject("downloadInfo") ?: return
                val tId = dlInfo.optString("trackId", "")
                val key = dlInfo.optString("key", "")
                val codec = dlInfo.optString("codec", "mp3")
                val streamUrl = dlInfo.optString("url", "")
                val quality = dlInfo.optString("quality", "")

                // Never accept or cache preview tracks (30 seconds)!
                if (quality == "preview" || streamUrl.contains("preview.mp3") || streamUrl.contains("/preview")) {
                    android.util.Log.w("YaMusicLsposed", "Ignoring preview stream for track $tId")
                    return
                }

                if (tId.isNotEmpty() && streamUrl.isNotEmpty()) {
                    val info = TrackStreamInfo(tId, key, codec, streamUrl)
                    capturedStreamInfos[tId] = info
                    val cleanId = tId.substringBefore(":")
                    if (cleanId != tId) capturedStreamInfos[cleanId] = info

                    android.util.Log.i("YaMusicLsposed", "Captured full stream for track $tId (codec=$codec, hasKey=${key.isNotEmpty()})")
                    val ctx = context ?: appContext
                    ctx?.let { c ->
                        try {
                            File(c.filesDir, "stream_info_$tId.json").writeText(
                                org.json.JSONObject().apply {
                                    put("trackId", tId)
                                    put("key", key)
                                    put("codec", codec)
                                    put("url", streamUrl)
                                }.toString()
                            )
                        } catch (e: Throwable) {}
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("YaMusicLsposed", "parseAndSaveFileInfo error: ${t.message}")
            }
        }

        fun findFileInfoFromCache(context: Context, trackId: String): TrackStreamInfo? {
            val cleanId = trackId.substringBefore(":")

            // 1. Check in-memory captured streams (ensure not preview)
            capturedStreamInfos[cleanId]?.let {
                if (!it.url.contains("preview.mp3") && !it.url.contains("/preview")) return it
            }
            capturedStreamInfos[trackId]?.let {
                if (!it.url.contains("preview.mp3") && !it.url.contains("/preview")) return it
            }

            // 2. Check saved file in filesDir
            for (id in listOf(cleanId, trackId)) {
                try {
                    val savedFile = File(context.filesDir, "stream_info_$id.json")
                    if (savedFile.exists()) {
                        val json = org.json.JSONObject(savedFile.readText())
                        val url = json.optString("url")
                        if (url.isNotEmpty() && !url.contains("preview.mp3") && !url.contains("/preview")) {
                            val info = TrackStreamInfo(
                                trackId = id,
                                key = json.optString("key"),
                                codec = json.optString("codec", "mp3"),
                                url = url
                            )
                            capturedStreamInfos[id] = info
                            return info
                        } else if (url.contains("preview")) {
                            savedFile.delete()
                        }
                    }
                } catch (e: Throwable) {}
            }

            // 3. Check SQLite userDb databases (tracks_cache table populated by app playback)
            try {
                val appDataDir = context.applicationInfo.dataDir
                val dbDir = File(appDataDir, "databases")
                if (dbDir.exists() && dbDir.isDirectory) {
                    val userDbs = dbDir.listFiles { _, name ->
                        name.startsWith("userDb_") && !name.endsWith("-shm") && !name.endsWith("-wal") && !name.endsWith("-journal")
                    } ?: emptyArray()

                    for (dbFile in userDbs) {
                        var db: SQLiteDatabase? = null
                        var cursor: Cursor? = null
                        try {
                            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                            for (id in listOf(cleanId, trackId)) {
                                cursor = db.rawQuery(
                                    "SELECT uri, codec FROM tracks_cache WHERE track_id = ? ORDER BY added_timestamp DESC LIMIT 1",
                                    arrayOf(id)
                                )
                                if (cursor != null && cursor.moveToFirst()) {
                                    val uriJsonStr = cursor.getString(0)
                                    val codec = cursor.getString(1) ?: "aac-mp4"
                                    if (!uriJsonStr.isNullOrEmpty()) {
                                        val arr = JSONArray(uriJsonStr)
                                        // Array format: ["VERSION_KEY", 1, streamUrl, key, expiry, urls]
                                        if (arr.length() >= 4) {
                                            val streamUrl = arr.optString(2, "")
                                            val key = arr.optString(3, "")
                                            if (streamUrl.isNotEmpty() && !streamUrl.contains("preview.mp3") && !streamUrl.contains("/preview")) {
                                                val info = TrackStreamInfo(id, key, codec, streamUrl)
                                                capturedStreamInfos[id] = info
                                                android.util.Log.i("YaMusicLsposed", "Found full stream in SQLite tracks_cache for $id ($codec)")
                                                return info
                                            }
                                        }
                                    }
                                }
                                cursor?.close()
                                cursor = null
                            }
                        } catch (t: Throwable) {
                        } finally {
                            cursor?.close()
                            db?.close()
                        }
                    }
                }
            } catch (e: Throwable) {}

            // 4. Check cache_control_http_cache directory
            try {
                val cacheDir = File(context.cacheDir, "cache_control_http_cache")
                if (cacheDir.exists() && cacheDir.isDirectory) {
                    val files = cacheDir.listFiles() ?: emptyArray()
                    for (f in files) {
                        if (f.name.endsWith(".0")) {
                            try {
                                val headerText = f.readText()
                                if (headerText.contains("get-file-info") && (headerText.contains("trackId=$cleanId") || headerText.contains("trackId=$trackId"))) {
                                    val bodyFile = File(cacheDir, f.name.removeSuffix(".0") + ".1")
                                    if (bodyFile.exists()) {
                                        val bodyBytes = try {
                                            GZIPInputStream(bodyFile.inputStream()).bufferedReader().use { it.readText() }
                                        } catch (e: Throwable) {
                                            bodyFile.readText()
                                        }
                                        val json = org.json.JSONObject(bodyBytes)
                                        val dlInfo = json.optJSONObject("result")?.optJSONObject("downloadInfo")
                                        if (dlInfo != null) {
                                            val streamUrl = dlInfo.optString("url", "")
                                            val key = dlInfo.optString("key", "")
                                            val codec = dlInfo.optString("codec", "mp3")
                                            val quality = dlInfo.optString("quality", "")
                                            if (streamUrl.isNotEmpty() && quality != "preview" && !streamUrl.contains("preview.mp3") && !streamUrl.contains("/preview")) {
                                                val info = TrackStreamInfo(cleanId, key, codec, streamUrl)
                                                capturedStreamInfos[cleanId] = info
                                                android.util.Log.i("YaMusicLsposed", "Found full stream in HTTP cache for $cleanId ($codec)")
                                                return info
                                            }
                                        }
                                    }
                                }
                            } catch (e: Throwable) {}
                        }
                    }
                }
            } catch (e: Throwable) {}

            return null
        }

        fun fetchFileInfoDirectly(trackId: String, token: String, context: Context?): TrackStreamInfo? {
            try {
                val cleanId = trackId.substringBefore(":")
                val ts = (System.currentTimeMillis() / 1000).toString()
                val codecsJoined = "mp3he-aacaacflache-aac-mp4aac-mp4flac-mp4"
                val codecsParam = "mp3,he-aac,aac,flac,he-aac-mp4,aac-mp4,flac-mp4"
                val quality = "auto"
                val signData = "${ts}${cleanId}${quality}${codecsJoined}encraw"
                
                val keyBytes = "p93jhgh689SBReK6ghtw62".toByteArray(Charsets.UTF_8)
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
                val sigBytes = mac.doFinal(signData.toByteArray(Charsets.UTF_8))
                val sign = Base64.encodeToString(sigBytes, Base64.NO_WRAP or Base64.NO_PADDING).trimEnd('=')
                
                val encodedSign = java.net.URLEncoder.encode(sign, "UTF-8")
                val encodedCodecs = java.net.URLEncoder.encode(codecsParam, "UTF-8")
                
                val urlStr = "https://api.music.yandex.net/get-file-info?trackId=$cleanId&codecs=$encodedCodecs&transports=encraw&quality=$quality&ts=$ts&sign=$encodedSign"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                if (token.isNotEmpty()) {
                    conn.setRequestProperty("Authorization", "OAuth $token")
                }
                conn.setRequestProperty("X-Yandex-Music-Client", "YandexMusicAndroid/24026531")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Accept-Encoding", "gzip")
                
                val inputStream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                    GZIPInputStream(conn.inputStream)
                } else {
                    conn.inputStream
                }
                val bodyString = inputStream.bufferedReader().use { it.readText() }
                parseAndSaveFileInfo(bodyString, context)
                val res = capturedStreamInfos[cleanId]
                if (res != null && (res.url.contains("preview.mp3") || res.url.contains("/preview"))) {
                    capturedStreamInfos.remove(cleanId)
                    return null
                }
                return res
            } catch (t: Throwable) {
                android.util.Log.e("YaMusicLsposed", "fetchFileInfoDirectly error for $trackId: ${t.message}")
                return null
            }
        }

        fun isValidToken(str: String?): Boolean {
            if (str.isNullOrBlank()) return false
            val s = str.trim()
            if (s.length < 20) return false
            if (s.contains(" ") || s.contains("\n") || s.contains("\r") || s.contains("\t")) return false
            if (s.startsWith("y0_") || s.startsWith("y1_") || s.startsWith("y0-") || 
                s.startsWith("AQAAAA") || s.startsWith("AgAAAA")) {
                return true
            }
            if (s.length >= 25 && Pattern.compile("^[a-zA-Z0-9_-]+$").matcher(s).matches()) {
                if (!s.contains(".") && (s.contains("_") || s.length >= 32)) {
                    return true
                }
            }
            return false
        }

        fun onTokenFound(token: String, source: String, context: Context? = null) {
            val clean = token.trim()
            if (!isValidToken(clean)) return
            cachedToken = clean
            android.util.Log.e("YaMusicLsposed", "Token captured via $source: ${clean.take(8)}... (len=${clean.length})")
            
            val ctx = context ?: appContext
            if (ctx != null) {
                try {
                    ctx.getSharedPreferences("YaMusicDL_Prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("token", clean)
                        .commit()
                } catch (e: Throwable) {}

                try {
                    File(ctx.filesDir, "yamusic_lsposed_token.txt").writeText(clean)
                } catch (e: Throwable) {}

                try {
                    File(ctx.applicationInfo.dataDir, "yamusic_lsposed_token.txt").writeText(clean)
                } catch (e: Throwable) {}
            }
        }

        fun inspectUrl(urlStr: String) {
            if (urlStr.isEmpty()) return
            try {
                val logFile = File("/sdcard/Download/YaMusicLsposed_Meta.txt")
                logFile.appendText("OAuth Request URL: $urlStr\n")
            } catch (e: Throwable) {}

            val mPlaylist = Pattern.compile("/users/([^/]+)/playlists/([^/?]+)").matcher(urlStr)
            if (mPlaylist.find()) {
                val extractedUserId = mPlaylist.group(1)
                val extractedPlaylistId = mPlaylist.group(2)
                if (extractedPlaylistId != "favorites" && !urlStr.contains("tracks")) {
                    cachedUserId = extractedUserId
                    cachedPlaylistId = extractedPlaylistId
                    cachedAlbumId = null
                }
            }

            val mAlbum = Pattern.compile("/albums/([^/?]+)").matcher(urlStr)
            if (mAlbum.find()) {
                val extractedAlbumId = mAlbum.group(1)
                if (!urlStr.contains("tracks")) {
                    cachedAlbumId = extractedAlbumId
                    cachedUserId = null
                    cachedPlaylistId = null
                }
            }

            if (cachedUserId != null || cachedAlbumId != null) {
                appContext?.let { ctx ->
                    try {
                        val f = File(ctx.filesDir, "yamusic_current_playlist.txt")
                        f.writeText("${cachedUserId ?: ""}\n${cachedPlaylistId ?: ""}\n${cachedAlbumId ?: ""}")
                    } catch (e: Throwable) {}
                }
            }
        }

        fun checkHeaderKeyValue(key: String?, value: String?, context: Context?) {
            if (key == null || value == null) return
            if (key.equals("Authorization", ignoreCase = true)) {
                if (value.startsWith("OAuth ", ignoreCase = true)) {
                    val token = value.substring(6).trim()
                    if (isValidToken(token)) {
                        onTokenFound(token, "Authorization: OAuth", context)
                    }
                } else if (value.startsWith("Bearer ", ignoreCase = true)) {
                    val token = value.substring(7).trim()
                    if (isValidToken(token)) {
                        onTokenFound(token, "Authorization: Bearer", context)
                    }
                }
            } else if (key.equals("X-OAuth-Token", ignoreCase = true) || key.equals("OAuth-Token", ignoreCase = true)) {
                val token = value.trim()
                if (isValidToken(token)) {
                    onTokenFound(token, key, context)
                }
            }
        }

        fun checkHeaderLine(line: String?, context: Context?) {
            if (line == null) return
            if (line.startsWith("Authorization:", ignoreCase = true)) {
                val value = line.substring(14).trim()
                if (value.startsWith("OAuth ", ignoreCase = true)) {
                    val token = value.substring(6).trim()
                    if (isValidToken(token)) {
                        onTokenFound(token, "Authorization Line (OAuth)", context)
                    }
                } else if (value.startsWith("Bearer ", ignoreCase = true)) {
                    val token = value.substring(7).trim()
                    if (isValidToken(token)) {
                        onTokenFound(token, "Authorization Line (Bearer)", context)
                    }
                }
            }
        }

        fun inspectOkHttpRequest(req: Any, context: Context?) {
            try {
                val authHeader = XposedHelpers.callMethod(req, "header", "Authorization") as? String
                if (!authHeader.isNullOrEmpty()) {
                    if (authHeader.startsWith("OAuth ", ignoreCase = true)) {
                        val token = authHeader.substring(6).trim()
                        if (isValidToken(token)) {
                            onTokenFound(token, "OkHttp Request Authorization", context)
                        }
                    } else if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
                        val token = authHeader.substring(7).trim()
                        if (isValidToken(token)) {
                            onTokenFound(token, "OkHttp Request Bearer", context)
                        }
                    }
                }
                val urlObj = XposedHelpers.callMethod(req, "url")
                val urlStr = urlObj?.toString() ?: ""
                if (urlStr.isNotEmpty()) {
                    inspectUrl(urlStr)
                }
            } catch (e: Throwable) {}
        }

        fun inspectOkHttpResponse(resp: Any, context: Context?) {
            try {
                val req = XposedHelpers.callMethod(resp, "request") ?: return
                val urlObj = XposedHelpers.callMethod(req, "url") ?: return
                val urlStr = urlObj.toString()
                if (urlStr.contains("get-file-info")) {
                    val body = XposedHelpers.callMethod(resp, "peekBody", 512L * 1024L) ?: return
                    val bodyString = XposedHelpers.callMethod(body, "string") as? String ?: return
                    parseAndSaveFileInfo(bodyString, context)
                }
            } catch (t: Throwable) {}
        }

        fun findTokenFromAccountManager(context: Context): String? {
            try {
                val am = AccountManager.get(context)
                val allAccounts = am.accounts
                for (acc in allAccounts) {
                    val type = acc.type.lowercase()
                    if (type.contains("yandex") || type.contains("passport")) {
                        val tokenTypes = arrayOf("oauth", "Passport", "music", "ru.yandex.music", "login", "web", "default", "")
                        for (tt in tokenTypes) {
                            try {
                                val tok = am.peekAuthToken(acc, tt)?.trim()
                                if (isValidToken(tok)) {
                                    return tok
                                }
                            } catch (e: Throwable) {}
                        }

                        try {
                            val pass = am.getPassword(acc)?.trim()
                            if (isValidToken(pass)) {
                                return pass
                            }
                        } catch (e: Throwable) {}

                        val userKeys = arrayOf("oauth_token", "token", "access_token", "passport_token", "auth_token", "oauth")
                        for (uk in userKeys) {
                            try {
                                val ud = am.getUserData(acc, uk)?.trim()
                                if (isValidToken(ud)) {
                                    return ud
                                }
                            } catch (e: Throwable) {}
                        }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("YaMusicLsposed", "AccountManager lookup error: ${e.message}")
            }
            return null
        }

        fun findTokenFromStorage(context: Context): String? {
            try {
                val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                if (prefsDir.exists() && prefsDir.isDirectory) {
                    for (f in prefsDir.listFiles() ?: emptyArray()) {
                        if (f.name.endsWith(".xml")) {
                            val prefName = f.name.removeSuffix(".xml")
                            if (prefName == "YaMusicDL_Prefs") continue
                            try {
                                val sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                                for ((_, value) in sp.all) {
                                    val s = value?.toString()?.trim() ?: continue
                                    if (isValidToken(s)) {
                                        return s
                                    }
                                }
                            } catch (e: Throwable) {}
                        }
                    }
                }

                val targetDirs = mutableListOf<File>()
                val dataDir = File(context.applicationInfo.dataDir)
                targetDirs.add(File(dataDir, "shared_prefs"))
                targetDirs.add(File(dataDir, "files/datastore"))
                targetDirs.add(File(dataDir, "databases"))
                targetDirs.add(File(dataDir, "files"))
                targetDirs.add(File(dataDir, "no_backup"))

                try {
                    val deContext = context.createDeviceProtectedStorageContext()
                    val deDir = File(deContext.applicationInfo.dataDir)
                    targetDirs.add(File(deDir, "shared_prefs"))
                    targetDirs.add(File(deDir, "files/datastore"))
                    targetDirs.add(File(deDir, "databases"))
                    targetDirs.add(File(deDir, "files"))
                } catch (e: Throwable) {}

                val tokenPatterns = listOf(
                    Pattern.compile("(y0_[a-zA-Z0-9_-]{20,})"),
                    Pattern.compile("(A[Qg]AAAA[a-zA-Z0-9_-]{20,})"),
                    Pattern.compile("OAuth\\s+([a-zA-Z0-9_.-]{20,})"),
                    Pattern.compile("\"(?:access_token|oauth_token|token|passport_token)\"\\s*:\\s*\"([a-zA-Z0-9_.-]{20,})\"")
                )

                for (dir in targetDirs) {
                    if (!dir.exists() || !dir.isDirectory) continue
                    val files = dir.listFiles() ?: continue
                    for (file in files) {
                        if (!file.isFile || file.length() == 0L || file.length() > 3 * 1024 * 1024) continue
                        val name = file.name.lowercase()
                        if (name.endsWith(".apk") || name.endsWith(".dex") || name.endsWith(".so") || name.endsWith(".mp3")) continue
                        if (name == "yamusicdl_prefs.xml") continue

                        try {
                            val bytes = file.readBytes()
                            val text = String(bytes, Charsets.ISO_8859_1)
                            for (pattern in tokenPatterns) {
                                val m = pattern.matcher(text)
                                if (m.find()) {
                                    val candidate = m.group(1).trim()
                                    if (isValidToken(candidate)) {
                                        return candidate
                                    }
                                }
                            }
                        } catch (e: Throwable) {}
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("YaMusicLsposed", "Storage search error: ${e.message}")
            }
            return null
        }

        fun findToken(context: Context): String? {
            try {
                val prefs = context.getSharedPreferences("YaMusicDL_Prefs", Context.MODE_PRIVATE)
                val savedToken = prefs.getString("token", "")?.trim()
                if (!savedToken.isNullOrEmpty() && isValidToken(savedToken)) {
                    cachedToken = savedToken
                    return savedToken
                }
            } catch (e: Throwable) {}

            if (!cachedToken.isNullOrEmpty() && isValidToken(cachedToken)) {
                return cachedToken
            }

            try {
                val file = File(context.filesDir, "yamusic_lsposed_token.txt")
                if (file.exists()) {
                    val t = file.readText().trim()
                    if (isValidToken(t)) {
                        cachedToken = t
                        return t
                    }
                }
            } catch (e: Throwable) {}

            try {
                val file = File(context.applicationInfo.dataDir, "yamusic_lsposed_token.txt")
                if (file.exists()) {
                    val t = file.readText().trim()
                    if (isValidToken(t)) {
                        cachedToken = t
                        return t
                    }
                }
            } catch (e: Throwable) {}

            val amToken = findTokenFromAccountManager(context)
            if (!amToken.isNullOrEmpty()) {
                onTokenFound(amToken, "AccountManager", context)
                return amToken
            }

            val storageToken = findTokenFromStorage(context)
            if (!storageToken.isNullOrEmpty()) {
                onTokenFound(storageToken, "App Storage", context)
                return storageToken
            }

            if (context is Activity) {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    if (clipboard != null && clipboard.hasPrimaryClip()) {
                        val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim() ?: ""
                        if (isValidToken(clipText)) {
                            onTokenFound(clipText, "Clipboard", context)
                            return clipText
                        }
                    }
                } catch (e: Throwable) {}
            }

            return null
        }

        fun injectModuleAssets(context: Context?) {
            if (context == null) return
            try {
                val path = modulePath ?: try {
                    context.packageManager.getPackageInfo("io.github.windyx0.yamusiclsposed", 0).applicationInfo.publicSourceDir
                } catch (e: Throwable) { null }

                if (!path.isNullOrEmpty()) {
                    val assets = context.resources.assets
                    val addAssetPathMethod = assets.javaClass.getMethod("addAssetPath", String::class.java)
                    addAssetPathMethod.isAccessible = true
                    addAssetPathMethod.invoke(assets, path)
                    android.util.Log.e("YaMusicLsposed", "Successfully injected module asset path: $path")
                }
            } catch (t: Throwable) {
                android.util.Log.e("YaMusicLsposed", "injectModuleAssets warning", t)
            }
        }

        private val lameLock = Object()
        @Volatile private var lameLoaded = false

        @Synchronized
        fun ensureLameLoaded(context: Context) {
            if (lameLoaded) return
            try {
                System.loadLibrary("androidlame")
                lameLoaded = true
                android.util.Log.i("YaMusicLsposed", "libandroidlame loaded via System.loadLibrary")
                return
            } catch (t: Throwable) {
                android.util.Log.d("YaMusicLsposed", "System.loadLibrary failed: ${t.message}")
            }

            var soFile: File? = null

            // 1. Check nativeLibraryDir
            try {
                val pm = context.packageManager
                val pkgInfo = pm.getPackageInfo("io.github.windyx0.yamusiclsposed", 0)
                val nativeDir = pkgInfo.applicationInfo?.nativeLibraryDir
                if (!nativeDir.isNullOrEmpty()) {
                    val f = File(nativeDir, "libandroidlame.so")
                    if (f.exists()) soFile = f
                }
            } catch (t: Throwable) {}

            // 2. Extract from APK if needed
            if (soFile == null || !soFile.exists()) {
                var apkPath = modulePath
                if (apkPath == null) {
                    try {
                        val pm = context.packageManager
                        val pkgInfo = pm.getPackageInfo("io.github.windyx0.yamusiclsposed", 0)
                        apkPath = pkgInfo.applicationInfo?.sourceDir
                    } catch (t: Throwable) {}
                }

                if (apkPath != null) {
                    try {
                        val apkFile = File(apkPath)
                        if (apkFile.exists()) {
                            val zip = java.util.zip.ZipFile(apkFile)
                            var entry: java.util.zip.ZipEntry? = null
                            for (abi in android.os.Build.SUPPORTED_ABIS) {
                                entry = zip.getEntry("lib/$abi/libandroidlame.so")
                                if (entry != null) break
                            }
                            if (entry != null) {
                                val destFile = File(context.cacheDir, "libandroidlame.so")
                                if (!destFile.exists() || destFile.length() != entry.size) {
                                    zip.getInputStream(entry).use { input ->
                                        destFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                                destFile.setReadable(true, false)
                                destFile.setExecutable(true, false)
                                soFile = destFile
                            }
                        }
                    } catch (t: Throwable) {}
                }
            }

            if (soFile != null && soFile.exists()) {
                try {
                    System.load(soFile.absolutePath)
                    lameLoaded = true
                    android.util.Log.i("YaMusicLsposed", "libandroidlame loaded via System.load: ${soFile.absolutePath}")
                } catch (t: Throwable) {
                    android.util.Log.e("YaMusicLsposed", "System.load failed: ${t.message}")
                }

                // Inject directory into DexPathList so AndroidLame.<clinit>'s System.loadLibrary finds it
                try {
                    val cl = AndroidLame::class.java.classLoader
                    if (cl != null) {
                        val pathListField = cl.javaClass.superclass?.getDeclaredField("pathList")
                            ?: cl.javaClass.getDeclaredField("pathList")
                        pathListField.isAccessible = true
                        val pathList = pathListField.get(cl)
                        val nativeDirsField = pathList.javaClass.getDeclaredField("nativeLibraryDirectories")
                        nativeDirsField.isAccessible = true
                        val dirs = nativeDirsField.get(pathList)
                        if (dirs is MutableList<*>) {
                            @Suppress("UNCHECKED_CAST")
                            (dirs as MutableList<File>).add(0, soFile.parentFile)
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.d("YaMusicLsposed", "DexPathList injection note: ${t.message}")
                }
            }
        }

        fun convertM4aToMp3(
            inputFile: File,
            outputFile: File,
            targetBitrateKbps: Int,
            context: Context,
            cancelCheck: (() -> Boolean)? = null,
            onProgress: ((Float) -> Unit)? = null
        ): Boolean {
            synchronized(lameLock) {
                ensureLameLoaded(context)
                val extractor = MediaExtractor()
                var decoder: MediaCodec? = null
                var lame: AndroidLame? = null

                try {
                    extractor.setDataSource(inputFile.absolutePath)
                    var audioTrackIndex = -1
                    var format: MediaFormat? = null

                    for (i in 0 until extractor.trackCount) {
                        val trackFormat = extractor.getTrackFormat(i)
                        val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("audio/")) {
                            audioTrackIndex = i
                            format = trackFormat
                            break
                        }
                    }

                    if (audioTrackIndex < 0 || format == null) {
                        android.util.Log.e("YaMusicLsposed", "No audio track found in ${inputFile.name}")
                        return false
                    }

                    extractor.selectTrack(audioTrackIndex)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
                    val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else 44100
                    val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else 2
                    val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION)
                    } else 0L

                    decoder = MediaCodec.createDecoderByType(mime)
                    decoder.configure(format, null, null, 0)
                    decoder.start()

                    var actualSampleRate = sampleRate
                    var actualChannels = channelCount

                    val bufferInfo = MediaCodec.BufferInfo()
                    val timeoutUs = 10000L
                    var inputEOS = false
                    var outputEOS = false
                    val mp3Buffer = ByteArray(65536)

                    val mp3Out = FileOutputStream(outputFile)
                    try {
                        while (!outputEOS) {
                            if (cancelCheck?.invoke() == true) {
                                throw RuntimeException("Cancelled")
                            }

                            if (!inputEOS) {
                                val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                                if (inIndex >= 0) {
                                    val inBuffer = decoder.getInputBuffer(inIndex)
                                    if (inBuffer != null) {
                                        inBuffer.clear()
                                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                                        if (sampleSize < 0) {
                                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                            inputEOS = true
                                        } else {
                                            val pts = extractor.sampleTime
                                            decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                                            extractor.advance()
                                        }
                                    }
                                }
                            }

                            var outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                            while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                    val newFormat = decoder.outputFormat
                                    actualSampleRate = if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                        newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                    } else actualSampleRate
                                    actualChannels = if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                        newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                    } else actualChannels
                                    outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                                    continue
                                }

                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    outputEOS = true
                                }

                                if (lame == null) {
                                    val builder = LameBuilder()
                                        .setInSampleRate(actualSampleRate)
                                        .setOutSampleRate(actualSampleRate)
                                        .setOutChannels(actualChannels)
                                        .setOutBitrate(targetBitrateKbps)
                                        .setQuality(2)
                                        .setMode(if (actualChannels == 1) LameBuilder.Mode.MONO else LameBuilder.Mode.STEREO)
                                    lame = builder.build()
                                }

                                val outBuffer = decoder.getOutputBuffer(outIndex)
                                if (outBuffer != null && bufferInfo.size > 0) {
                                    outBuffer.position(bufferInfo.offset)
                                    outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                    outBuffer.order(ByteOrder.LITTLE_ENDIAN)

                                    val shortBuffer = outBuffer.asShortBuffer()
                                    val numShorts = shortBuffer.remaining()
                                    if (numShorts > 0) {
                                        val pcmShorts = ShortArray(numShorts)
                                        shortBuffer.get(pcmShorts)

                                        val samplesPerChannel = numShorts / actualChannels
                                        if (samplesPerChannel > 0) {
                                            val l = lame!!
                                            val encoded = if (actualChannels == 1) {
                                                l.encode(pcmShorts, pcmShorts, numShorts, mp3Buffer)
                                            } else {
                                                l.encodeBufferInterLeaved(pcmShorts, samplesPerChannel, mp3Buffer)
                                            }
                                            if (encoded > 0) {
                                                mp3Out.write(mp3Buffer, 0, encoded)
                                            }
                                        }
                                    }

                                    if (durationUs > 0L) {
                                        val progress = (bufferInfo.presentationTimeUs.toFloat() / durationUs.toFloat()) * 100f
                                        onProgress?.invoke(progress.coerceIn(0f, 99f))
                                    }
                                }

                                decoder.releaseOutputBuffer(outIndex, false)
                                if (outputEOS) break
                                outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                            }
                        }

                        if (lame != null) {
                            val flushBytes = lame.flush(mp3Buffer)
                            if (flushBytes > 0) {
                                mp3Out.write(mp3Buffer, 0, flushBytes)
                            }
                        }
                        mp3Out.flush()
                    } finally {
                        mp3Out.close()
                    }

                    return outputFile.exists() && outputFile.length() > 0
                } catch (t: Throwable) {
                    android.util.Log.e("YaMusicLsposed", "convertM4aToMp3 error: ${t.stackTraceToString()}")
                    return false
                } finally {
                    try { lame?.close() } catch (e: Throwable) {}
                    try { decoder?.stop(); decoder?.release() } catch (e: Throwable) {}
                    try { extractor.release() } catch (e: Throwable) {}
                }
            }
        }
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
        android.util.Log.e("YaMusicLsposed", "initZygote: modulePath = $modulePath")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "ru.yandex.music") return

        android.util.Log.e("YaMusicLsposed", "Successfully hooked into Yandex Music! Process: ${lpparam.processName}")
        hookResourcesForMissingComposeStrings(lpparam)
        hookApplicationContext(lpparam)
        hookMediaSession(lpparam)
        hookNetworkForToken(lpparam)
        hookUI(lpparam)
    }

    private fun hookResourcesForMissingComposeStrings(lpparam: XC_LoadPackage.LoadPackageParam) {
        val composeStringFallback = mapOf(
            0x7f08003d to "Selected",
            0x7f080037 to "Not selected",
            0x7f080039 to "On",
            0x7f080038 to "Off",
            0x7f080033 to "In progress",
            0x7f080034 to "Indeterminate",
            0x7f080041 to "Switch",
            0x7f080042 to "Tab",
            0x7f080030 to "Dialog",
            0x7f08000c to "Close",
            0x7f08000d to "Close",
            0x7f08000e to "Collapsed",
            0x7f080032 to "Expanded",
            0x7f08002e to "Error",
            0x7f08002f to "Popup",
            0x7f080031 to "Menu",
            0x7f080036 to "Navigation",
            0x7f08003e to "Dismiss",
            0x7f080040 to "Suggestions",
            0x7f080043 to "%d%%",
            0x7f080050 to "Tooltip",
            0x7f080051 to "Tooltip",
            0x7f080052 to "Tooltip",
            0x7f080053 to "Tooltip"
        )

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable()) {
                    val id = param.args.getOrNull(0) as? Int ?: return
                    val fallback = composeStringFallback[id]
                    if (fallback != null) {
                        param.result = fallback
                    } else if ((id ushr 16) == 0x7f08 || (id in 0x7f080000..0x7f0800ff)) {
                        param.result = ""
                    }
                }
            }
        }

        try {
            XposedBridge.hookAllMethods(android.content.res.Resources::class.java, "getText", hook)
            XposedBridge.hookAllMethods(android.content.res.Resources::class.java, "getString", hook)
        } catch (t: Throwable) {
            android.util.Log.e("YaMusicLsposed", "Failed to hook Resources.getText/getString", t)
        }

        for (clsName in arrayOf("android.content.res.MiuiResources", "android.content.res.MiuiResourcesImpl")) {
            try {
                val cls = XposedHelpers.findClassIfExists(clsName, lpparam.classLoader)
                    ?: XposedHelpers.findClassIfExists(clsName, null)
                if (cls != null) {
                    XposedBridge.hookAllMethods(cls, "getText", hook)
                    XposedBridge.hookAllMethods(cls, "getString", hook)
                    android.util.Log.e("YaMusicLsposed", "Hooked $clsName successfully")
                }
            } catch (t: Throwable) {}
        }
    }

    private fun hookApplicationContext(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        appContext = app
                        injectModuleAssets(app)
                        thread {
                            try {
                                findToken(app)
                            } catch (e: Throwable) {}
                        }
                    }
                }
            )
        } catch (t: Throwable) {}
    }

    private fun hookMediaSession(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.media.session.MediaSession",
                lpparam.classLoader,
                "setMetadata",
                MediaMetadata::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val metadata = param.args[0] as? MediaMetadata ?: return
                        val newTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                        val newArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        val newId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                        val newArtUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                        val newDuration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                        
                        if (newId != null && newTitle != null) {
                            currentTitle = newTitle
                            currentArtist = newArtist
                            currentMediaId = newId
                            currentAlbumArtUri = newArtUri
                            currentDuration = newDuration

                            appContext?.let { ctx ->
                                try {
                                    val f = File(ctx.filesDir, "yamusic_current_media.txt")
                                    f.writeText("$newId\n$newTitle\n${newArtist ?: ""}\n${newArtUri ?: ""}\n$newDuration")
                                } catch (e: Throwable) {}
                            }
                            try {
                                val keys = metadata.keySet()
                                  for (key in keys) {
                                      val value = metadata.getString(key) ?: metadata.getLong(key).toString()
                                      android.util.Log.e("YaMusicLsposed", "Metadata $key = $value")
                                      try {
                                          val logFile = java.io.File("/sdcard/Download/YaMusicLsposed_Meta.txt")
                                          logFile.appendText("Metadata $key = $value\n")
                                      } catch (e: Throwable) {}
                                  }
                                val desc = metadata.description
                                android.util.Log.e("YaMusicLsposed", "Description mediaId = ${desc.mediaId}, mediaUri = ${desc.mediaUri}")
                                  try {
                                      val logFile = java.io.File("/sdcard/Download/YaMusicLsposed_Meta.txt")
                                      logFile.appendText("Description mediaId = ${desc.mediaId}, mediaUri = ${desc.mediaUri}\n")
                                  } catch (e: Throwable) {}
                            } catch (e: Throwable) {}
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            android.util.Log.e("YaMusicLsposed", "Error hooking MediaSession: ${t.message}")
        }
    }

    private fun hookUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java.name,
                lpparam.classLoader,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        if (activity.packageName != "ru.yandex.music") return
                        appContext = activity.applicationContext

                        if (cachedToken == null) {
                            thread {
                                try {
                                    findToken(activity)
                                } catch (e: Throwable) {}
                            }
                        }
                        
                        // Parse playlist/album ID from Intent extras
                        try {
                            val intent = activity.intent
                            if (intent != null && intent.extras != null) {
                                val extras = intent.extras!!
                                
                                // Try playlist ID
                                val playlistArg = extras.get("extra.playlist.id.arg")?.toString() ?: ""
                                if (playlistArg.startsWith("UserIdAndKind")) {
                                    val m = java.util.regex.Pattern.compile("userId=([^,]+), kind=([^)]+)").matcher(playlistArg)
                                    if (m.find()) {
                                        cachedUserId = m.group(1).trim()
                                        cachedPlaylistId = m.group(2).trim()
                                        cachedAlbumId = null
                                    }
                                }
                                
                                // Try album ID from AlbumActivityParams in extra.activityParams
                                val activityParams = extras.get("extra.activityParams")?.toString() ?: ""
                                if (activityParams.contains("AlbumActivityParams") || activityParams.contains("albumId=")) {
                                    val albumMatch = java.util.regex.Pattern.compile("albumId=(\\d+)").matcher(activityParams)
                                    if (albumMatch.find()) {
                                        cachedAlbumId = albumMatch.group(1).trim()
                                        cachedUserId = null
                                        cachedPlaylistId = null
                                    }
                                }

                                if (cachedUserId != null || cachedAlbumId != null) {
                                    try {
                                        val f = File(activity.filesDir, "yamusic_current_playlist.txt")
                                        f.writeText("${cachedUserId ?: ""}\n${cachedPlaylistId ?: ""}\n${cachedAlbumId ?: ""}")
                                    } catch (e: Throwable) {}
                                }
                            }
                        } catch (e: Throwable) {}

                        val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
                        if (rootView.findViewWithTag<ImageButton>("YaMusicDownloadButton") != null) return
                        
                    
                        val density = activity.resources.displayMetrics.density
                        val sizePx = (40 * density).toInt()

                        val downloadBtn = LinearLayout(activity).apply {
                            tag = "YaMusicDownloadButton"
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            alpha = 0f
                            translationX = (60 * density)
                            
                            val normalBg = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                cornerRadius = (sizePx / 2f)
                                setColor(android.graphics.Color.parseColor("#ffe600"))
                                setStroke((3 * density).toInt(), android.graphics.Color.parseColor("#ffe600"))
                            }
                            background = normalBg

                            val btnDrawable = object : android.graphics.drawable.Drawable() {
                                var animState: Int = 0 // 0=Arrow, 1=Loading, 2=Done
                                var downloadProgress: Float = 0f
                                
                                private val paintFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = android.graphics.Color.BLACK; style = android.graphics.Paint.Style.FILL
                                }
                                private val paintStroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = android.graphics.Color.BLACK; style = android.graphics.Paint.Style.STROKE
                                    strokeWidth = 2f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
                                }
                                private val paintTrack = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = android.graphics.Color.argb(40, 0, 0, 0)
                                    style = android.graphics.Paint.Style.STROKE
                                    strokeWidth = 2f; strokeCap = android.graphics.Paint.Cap.ROUND
                                }
                                
                                private val arrowPath = android.graphics.Path()
                                private val checkPath = android.graphics.Path()
                                
                                init {
                                    try {
                                        val pathClass = try { Class.forName("androidx.core.graphics.PathParser") } catch (e: Throwable) { Class.forName("android.util.PathParser") }
                                        val createPathMethod = pathClass.getMethod("createPathFromPathData", String::class.java)
                                        arrowPath.set(createPathMethod.invoke(null, "M10.8333 12.7791C10.9784 12.5958 11.1318 12.409 11.2927 12.22C12.5317 10.7655 14.2728 9.12663 16.2619 8.02154L17.0714 9.47847C15.3105 10.4567 13.7183 11.9428 12.5614 13.3008C11.9853 13.9771 11.5301 14.6065 11.2243 15.1042C11.0708 15.3541 10.9624 15.5587 10.8953 15.711C10.8712 15.7658 10.8559 15.806 10.8464 15.8333H18.3333V17.5H9.99997H1.66666V15.8333H9.15355C9.14404 15.806 9.12879 15.7658 9.10466 15.711C9.03759 15.5587 8.92917 15.3541 8.77565 15.1042C8.46983 14.6065 8.01465 13.9771 7.43853 13.3008C6.2817 11.9428 4.68945 10.4567 2.92861 9.47847L3.73802 8.02154C5.72719 9.12663 7.46827 10.7655 8.70727 12.22C8.8682 12.409 9.02154 12.5958 9.16664 12.7791V2.5H10.8333V12.7791Z") as android.graphics.Path)
                                        checkPath.set(createPathMethod.invoke(null, "M20 6L9 17L4 12") as android.graphics.Path)
                                    } catch (e: Throwable) {
                                        arrowPath.moveTo(10f, 17f); arrowPath.lineTo(4f, 11f); arrowPath.lineTo(16f, 11f); arrowPath.close()
                                        checkPath.moveTo(4f, 12f); checkPath.lineTo(9f, 17f); checkPath.lineTo(20f, 6f)
                                    }
                                }
                                override fun draw(canvas: android.graphics.Canvas) {
                                    val b = bounds
                                    val scale = Math.min(b.width() / 24f, b.height() / 24f)
                                    canvas.save()
                                    canvas.translate(b.left.toFloat(), b.top.toFloat())
                                    canvas.scale(scale, scale)
                                    if (animState == 0) {
                                        canvas.save()
                                        canvas.translate(2f, 2f)
                                        canvas.drawPath(arrowPath, paintFill)
                                        canvas.restore()
                                    } else if (animState == 1) {
                                        canvas.drawArc(android.graphics.RectF(3f, 3f, 21f, 21f), 0f, 360f, false, paintTrack)
                                        val sweep = if (downloadProgress > 0f) 360f * (downloadProgress / 100f) else 0f
                                        canvas.drawArc(android.graphics.RectF(3f, 3f, 21f, 21f), -90f, sweep, false, paintStroke)
                                    } else if (animState == 2) {
                                        canvas.drawPath(checkPath, paintStroke)
                                    }
                                    canvas.restore()
                                }
                                override fun setAlpha(alpha: Int) {}
                                override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                                override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
                            }
                            
                            val iconView = android.widget.ImageView(activity).apply {
                                setImageDrawable(btnDrawable)
                                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
                                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                            }
                            addView(iconView)
                            
                            val progressText = TextView(activity).apply {
                                text = ""
                                setTextColor(android.graphics.Color.BLACK)
                                textSize = 14f
                                visibility = android.view.View.GONE
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                    marginEnd = (16 * density).toInt()
                                    marginStart = (4 * density).toInt()
                                }
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            }
                            addView(progressText)

                            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, sizePx).apply {
                                gravity = Gravity.TOP or Gravity.END
                                topMargin = (110 * density).toInt()
                                marginEnd = (16 * density).toInt()
                            }
                            
                            setOnTouchListener { v, event ->
                                when (event.action) {
                                    android.view.MotionEvent.ACTION_DOWN -> {
                                        normalBg.setColor(android.graphics.Color.parseColor("#ebd300"))
                                        normalBg.setStroke((3 * density).toInt(), android.graphics.Color.parseColor("#ffe600"))
                                        v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                                    }
                                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                        normalBg.setColor(android.graphics.Color.parseColor("#ffe600"))
                                        normalBg.setStroke((3 * density).toInt(), android.graphics.Color.parseColor("#ffe600"))
                                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                                    }
                                }
                                false
                            }

                            setOnLongClickListener {
                                showSettingsOverlay(activity)
                                true
                            }
                            
                            setOnClickListener {
                                if (btnDrawable.animState == 1) {
                                    return@setOnClickListener
                                }
                                
                                startDownloadProcess(activity) { state, progress ->
                                    activity.runOnUiThread {
                                        if (state == 1 && btnDrawable.animState != 1) {
                                            android.transition.TransitionManager.beginDelayedTransition(rootView, android.transition.AutoTransition().apply { duration = 200 })
                                            progressText.visibility = android.view.View.VISIBLE
                                        } else if (state != 1 && btnDrawable.animState == 1) {
                                            android.transition.TransitionManager.beginDelayedTransition(rootView, android.transition.AutoTransition().apply { duration = 200 })
                                            progressText.visibility = android.view.View.GONE
                                        }
                                        
                                        btnDrawable.animState = state
                                        btnDrawable.downloadProgress = progress
                                        if (state == 1) {
                                            progressText.text = "Загрузка ${progress.toInt()}%"
                                        }
                                        btnDrawable.invalidateSelf()
                                    }
                                }
                            }
                        }

                        rootView.addView(downloadBtn)
                        // Slide-in + fade entrance animation for download button
                        downloadBtn.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .setDuration(400)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                            .start()

                        val playlistBtn = LinearLayout(activity).apply {
                            tag = "YaMusicDownloadPlaylistButton"
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            alpha = 0f
                            translationX = (60 * density)
                            
                            val normalBg = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                cornerRadius = (sizePx / 2f)
                                setColor(android.graphics.Color.parseColor("#ffe600"))
                                setStroke((3 * density).toInt(), android.graphics.Color.parseColor("#ffe600"))
                            }
                            background = normalBg
                            
                            val btnDrawable = object : android.graphics.drawable.Drawable() {
                                var animState: Int = 0 // 0=Icon, 1=Loading, 2=Done
                                var downloadProgress: Float = 0f
                                
                                private val paintStroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = android.graphics.Color.BLACK; style = android.graphics.Paint.Style.STROKE
                                    strokeWidth = 2f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
                                }
                                private val paintTrack = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = android.graphics.Color.argb(40, 0, 0, 0)
                                    style = android.graphics.Paint.Style.STROKE
                                    strokeWidth = 2f; strokeCap = android.graphics.Paint.Cap.ROUND
                                }
                                
                                private val iconPath = android.graphics.Path()
                                private val checkPath = android.graphics.Path()
                                
                                init {
                                    try {
                                        val pathClass = try { Class.forName("androidx.core.graphics.PathParser") } catch (e: Throwable) { Class.forName("android.util.PathParser") }
                                        val createPathMethod = pathClass.getMethod("createPathFromPathData", String::class.java)
                                        // Playlist download icon: 3 lines and a small down arrow
                                        iconPath.set(createPathMethod.invoke(null, "M4 6H20M4 10H20M4 14H14M16 14V20M13 17L16 20L19 17") as android.graphics.Path)
                                        checkPath.set(createPathMethod.invoke(null, "M20 6L9 17L4 12") as android.graphics.Path)
                                    } catch (e: Throwable) {
                                        iconPath.moveTo(4f, 6f); iconPath.lineTo(20f, 6f)
                                        iconPath.moveTo(4f, 10f); iconPath.lineTo(20f, 10f)
                                        iconPath.moveTo(4f, 14f); iconPath.lineTo(14f, 14f)
                                        iconPath.moveTo(16f, 14f); iconPath.lineTo(16f, 20f)
                                        iconPath.moveTo(13f, 17f); iconPath.lineTo(16f, 20f); iconPath.lineTo(19f, 17f)
                                        checkPath.moveTo(4f, 12f); checkPath.lineTo(9f, 17f); checkPath.lineTo(20f, 6f)
                                    }
                                }
                                override fun draw(canvas: android.graphics.Canvas) {
                                    val b = bounds
                                    val scale = Math.min(b.width() / 24f, b.height() / 24f)
                                    canvas.save()
                                    canvas.translate(b.left.toFloat(), b.top.toFloat())
                                    canvas.scale(scale, scale)
                                    if (animState == 0) {
                                        canvas.save()
                                        canvas.translate(0f, 0f)
                                        canvas.drawPath(iconPath, paintStroke)
                                        canvas.restore()
                                    } else if (animState == 1) {
                                        canvas.drawArc(android.graphics.RectF(3f, 3f, 21f, 21f), 0f, 360f, false, paintTrack)
                                        val sweep = if (downloadProgress > 0f) 360f * (downloadProgress / 100f) else 0f
                                        canvas.drawArc(android.graphics.RectF(3f, 3f, 21f, 21f), -90f, sweep, false, paintStroke)
                                    } else if (animState == 2) {
                                        canvas.drawPath(checkPath, paintStroke)
                                    }
                                    canvas.restore()
                                }
                                override fun setAlpha(alpha: Int) {}
                                override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                                override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
                            }
                            
                            val iconView = android.widget.ImageView(activity).apply {
                                setImageDrawable(btnDrawable)
                                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
                                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                            }
                            addView(iconView)
                            
                            val progressText = TextView(activity).apply {
                                text = ""
                                setTextColor(android.graphics.Color.BLACK)
                                textSize = 14f
                                visibility = android.view.View.GONE
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                    marginEnd = (16 * density).toInt()
                                    marginStart = (4 * density).toInt()
                                }
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                maxWidth = (200 * density).toInt()
                            }
                            addView(progressText)

                            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, sizePx).apply {
                                gravity = Gravity.TOP or Gravity.END
                                topMargin = (160 * density).toInt()
                                marginEnd = (16 * density).toInt()
                            }
                            
                            setOnTouchListener { v, event ->
                                when (event.action) {
                                    android.view.MotionEvent.ACTION_DOWN -> {
                                        normalBg.setColor(android.graphics.Color.parseColor("#ebd300"))
                                        normalBg.setStroke((3 * density).toInt(), android.graphics.Color.parseColor("#ffe600"))
                                        v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                                    }
                                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                        normalBg.setColor(android.graphics.Color.parseColor("#ffe600"))
                                        normalBg.setStroke((3 * density).toInt(), android.graphics.Color.parseColor("#ffe600"))
                                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                                    }
                                }
                                false
                            }

                            setOnLongClickListener {
                                showDownloadsOverlay(activity)
                                true
                            }

                            setOnClickListener {
                                if (isPlaylistDownloading) {
                                    cancelPlaylistDownload = true
                                    progressText.text = "Остановка..."
                                    return@setOnClickListener
                                }

                                if (cachedUserId == null && cachedPlaylistId == null && cachedAlbumId == null) {
                                    try {
                                        val f = File(activity.filesDir, "yamusic_current_playlist.txt")
                                        if (f.exists()) {
                                            val lines = f.readLines()
                                            val u = lines.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() }
                                            val p = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                                            val a = lines.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
                                            if (a != null) {
                                                cachedAlbumId = a
                                            } else if (u != null && p != null) {
                                                cachedUserId = u
                                                cachedPlaylistId = p
                                            }
                                        }
                                    } catch (e: Throwable) {}
                                }

                                val uId = cachedUserId
                                val pId = cachedPlaylistId
                                val aId = cachedAlbumId
                                if ((uId == null || pId == null) && aId == null) {
                                    Toast.makeText(activity, "Сначала откройте страницу плейлиста или альбома!", Toast.LENGTH_SHORT).show()
                                    return@setOnClickListener
                                }
                                
                                val token = findToken(activity)
                                if (token.isNullOrEmpty()) {
                                    Toast.makeText(activity, "Токен не найден! Пожалуйста, прослушайте любой трек или войдите в аккаунт.", Toast.LENGTH_LONG).show()
                                    return@setOnClickListener
                                }

                                val config = loadConfig(activity)
                                val baseDownloadFolder = getDownloadPath(config)
                                val coverSize = config.coverSize

                                isPlaylistDownloading = true
                                cancelPlaylistDownload = false
                                android.transition.TransitionManager.beginDelayedTransition(rootView, android.transition.AutoTransition().apply { duration = 200 })
                                progressText.visibility = android.view.View.VISIBLE
                                progressText.text = "Запуск..."
                                btnDrawable.animState = 1
                                btnDrawable.invalidateSelf()

                                val updateProgress: (Int, Float, String) -> Unit = { state, progress, text ->
                                    activity.runOnUiThread {
                                        if (state != 1 && btnDrawable.animState == 1) {
                                            android.transition.TransitionManager.beginDelayedTransition(rootView, android.transition.AutoTransition().apply { duration = 200 })
                                            progressText.visibility = android.view.View.GONE
                                        }
                                        if (text.isNotEmpty() && progressText.text != text) {
                                            android.transition.TransitionManager.beginDelayedTransition(rootView, android.transition.AutoTransition().apply { duration = 200 })
                                            progressText.text = text
                                        }
                                        btnDrawable.animState = state
                                        btnDrawable.downloadProgress = progress
                                        btnDrawable.invalidateSelf()
                                        
                                        if (state == 0 || state == 2) {
                                            isPlaylistDownloading = false
                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                if (!isPlaylistDownloading) progressText.visibility = android.view.View.GONE
                                            }, 2000)
                                        }
                                    }
                                }

                                downloadPlaylistOrAlbum(
                                    activity, uId, pId, aId, config.quality, baseDownloadFolder, token, coverSize, updateProgress
                                )
                            }
                        }
                        rootView.addView(playlistBtn)
                        // Slide-in + fade entrance animation for playlist button (delayed)
                        playlistBtn.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .setStartDelay(150)
                            .setDuration(400)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                            .start()
                    }
                }
            )
        } catch (t: Throwable) {
            android.util.Log.e("YaMusicLsposed", "Error hooking UI: ${t.message}")
        }
    }

    private fun showSettingsOverlay(activity: Activity) {
        injectModuleAssets(activity)
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        
        val decorView = activity.window.decorView
        val bitmap = try {
            val bmp = android.graphics.Bitmap.createBitmap(decorView.width, decorView.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            decorView.draw(canvas)
            bmp
        } catch (e: Exception) { null }
        
        val dialog = object : android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {
            private var isClosing = false
            override fun onBackPressed() {
                if (isClosing) return
                isClosing = true
                val view = findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
                if (view != null) {
                    view.animate().alpha(0f).setDuration(250).withEndAction { dismiss() }.start()
                } else {
                    dismiss()
                }
            }
        }

        val composeView = androidx.compose.ui.platform.ComposeView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setupComposeEnvironment(activity)
        }

        fun closeOverlay() {
            dialog.onBackPressed()
        }
        
        composeView.setContent {
            YaMusicSettingsScreen(
                config = loadConfig(activity),
                backgroundBitmap = bitmap,
                onClose = { closeOverlay() },
                onSave = { newConfig ->
                    saveConfig(activity, newConfig)
                    Toast.makeText(activity, "Настройки сохранены!", Toast.LENGTH_SHORT).show()
                    closeOverlay()
                },
                activity = activity
            )
        }
        
        dialog.setContentView(composeView)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        composeView.alpha = 0f
        dialog.show()
        composeView.animate().alpha(1f).setDuration(250).start()
    }

    private fun showDownloadsOverlay(activity: Activity) {
        injectModuleAssets(activity)
        val decorView = activity.window.decorView
        val bitmap = try {
            val bmp = android.graphics.Bitmap.createBitmap(decorView.width, decorView.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            decorView.draw(canvas)
            bmp
        } catch (e: Exception) { null }

        val dialog = object : android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {
            private var isClosing = false
            override fun onBackPressed() {
                if (isClosing) return
                isClosing = true
                val view = findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
                if (view != null) {
                    view.animate().alpha(0f).setDuration(250).withEndAction { dismiss() }.start()
                } else {
                    dismiss()
                }
            }
        }

        val composeView = androidx.compose.ui.platform.ComposeView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setupComposeEnvironment(activity)
        }

        fun closeOverlay() {
            dialog.onBackPressed()
        }
        
        composeView.setContent {
            YaMusicDownloadsScreen(
                backgroundBitmap = bitmap,
                onClose = { closeOverlay() }
            )
        }
        
        dialog.setContentView(composeView)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        composeView.alpha = 0f
        dialog.show()
        composeView.animate().alpha(1f).setDuration(250).start()
    }
    private fun hookNetworkForToken(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. OkHttp Request$Builder.build()
        try {
            val reqBuilderClass = XposedHelpers.findClass("okhttp3.Request\$Builder", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(reqBuilderClass, "build", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val req = param.result ?: return
                    inspectOkHttpRequest(req, appContext)
                }
            })
        } catch (t: Throwable) {}

        // 2. OkHttp RealInterceptorChain.proceed(Request) and Interceptor$Chain.proceed(Request)
        try {
            val chainClass = XposedHelpers.findClass("okhttp3.internal.http.RealInterceptorChain", lpparam.classLoader)
            XposedBridge.hookAllMethods(chainClass, "proceed", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args.firstOrNull() ?: return
                    inspectOkHttpRequest(req, appContext)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val resp = param.result ?: return
                    inspectOkHttpResponse(resp, appContext)
                }
            })
        } catch (t: Throwable) {}

        try {
            val chainInterface = XposedHelpers.findClass("okhttp3.Interceptor\$Chain", lpparam.classLoader)
            XposedBridge.hookAllMethods(chainInterface, "proceed", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args.firstOrNull() ?: return
                    inspectOkHttpRequest(req, appContext)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val resp = param.result ?: return
                    inspectOkHttpResponse(resp, appContext)
                }
            })
        } catch (t: Throwable) {}

        // 3. OkHttp Request$Builder header / addHeader
        try {
            val reqBuilderClass = XposedHelpers.findClass("okhttp3.Request\$Builder", lpparam.classLoader)
            val builderHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val k = param.args.getOrNull(0) as? String
                    val v = param.args.getOrNull(1) as? String
                    checkHeaderKeyValue(k, v, appContext)
                }
            }
            XposedBridge.hookAllMethods(reqBuilderClass, "header", builderHook)
            XposedBridge.hookAllMethods(reqBuilderClass, "addHeader", builderHook)
        } catch (t: Throwable) {}

        // 4. OkHttp Headers$Builder add / set
        try {
            val headersBuilderClass = XposedHelpers.findClass("okhttp3.Headers\$Builder", lpparam.classLoader)
            val hbHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val arg0 = param.args.getOrNull(0) as? String ?: return
                    if (param.args.size > 1) {
                        val arg1 = param.args.getOrNull(1) as? String
                        checkHeaderKeyValue(arg0, arg1, appContext)
                    } else {
                        checkHeaderLine(arg0, appContext)
                    }
                }
            }
            XposedBridge.hookAllMethods(headersBuilderClass, "add", hbHook)
            XposedBridge.hookAllMethods(headersBuilderClass, "set", hbHook)
        } catch (t: Throwable) {}

        // 5. HttpURLConnection setRequestProperty / addRequestProperty
        try {
            val httpConnHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val k = param.args.getOrNull(0) as? String
                    val v = param.args.getOrNull(1) as? String
                    checkHeaderKeyValue(k, v, appContext)
                    try {
                        val url = (param.thisObject as? HttpURLConnection)?.url?.toString() ?: ""
                        if (url.isNotEmpty()) inspectUrl(url)
                    } catch (e: Throwable) {}
                }
            }
            XposedHelpers.findAndHookMethod(HttpURLConnection::class.java, "setRequestProperty", String::class.java, String::class.java, httpConnHook)
            XposedHelpers.findAndHookMethod(HttpURLConnection::class.java, "addRequestProperty", String::class.java, String::class.java, httpConnHook)
        } catch (t: Throwable) {}

        // 6. Cronet UrlRequest$Builder / ExperimentalUrlRequest$Builder
        for (cronetClassName in arrayOf("org.chromium.net.UrlRequest\$Builder", "org.chromium.net.ExperimentalUrlRequest\$Builder")) {
            try {
                val cronetClass = XposedHelpers.findClass(cronetClassName, lpparam.classLoader)
                XposedBridge.hookAllMethods(cronetClass, "addHeader", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val k = param.args.getOrNull(0) as? String
                        val v = param.args.getOrNull(1) as? String
                        checkHeaderKeyValue(k, v, appContext)
                    }
                })
            } catch (t: Throwable) {}
        }

        // 7. Common Yandex Auth / Passport SDK constructors
        val authClasses = listOf(
            "com.yandex.authsdk.YandexAuthToken",
            "com.yandex.passport.api.PassportToken",
            "com.yandex.passport.internal.entities.Token",
            "ru.yandex.music.data.auth.PassportToken"
        )
        for (clsName in authClasses) {
            try {
                val cls = XposedHelpers.findClass(clsName, lpparam.classLoader)
                XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        for (arg in param.args) {
                            val s = arg?.toString()?.trim() ?: continue
                            if (isValidToken(s)) {
                                onTokenFound(s, "Constructor $clsName", appContext)
                            }
                        }
                    }
                })
            } catch (t: Throwable) {}
        }
    }

    private fun startDownloadProcess(activity: Activity, onStateChange: (Int, Float) -> Unit) {
        if (currentMediaId == null) {
            try {
                val f = File(activity.filesDir, "yamusic_current_media.txt")
                if (f.exists()) {
                    val lines = f.readLines()
                    if (lines.isNotEmpty() && lines[0].isNotBlank()) {
                        currentMediaId = lines[0]
                        if (lines.size > 1 && currentTitle == null) currentTitle = lines[1]
                        if (lines.size > 2 && currentArtist == null) currentArtist = lines[2]
                        if (lines.size > 3 && currentAlbumArtUri == null) currentAlbumArtUri = lines[3]
                        if (lines.size > 4 && currentDuration == 0L) currentDuration = lines[4].toLongOrNull() ?: 0L
                    }
                }
            } catch (e: Throwable) {}
        }

        val title = currentTitle ?: "Unknown Title"
        val artist = currentArtist ?: "Unknown Artist"
        val rawMediaId = currentMediaId

        if (rawMediaId == null) {
            Toast.makeText(activity, "Не найден ID! Включите трек.", Toast.LENGTH_SHORT).show()
            return
        }

        val token = findToken(activity)

        if (token.isNullOrEmpty()) {
            Toast.makeText(activity, "Необходим токен авторизации! Включите любой трек или введите его в настройках.", Toast.LENGTH_LONG).show()
            return
        }

        val config = loadConfig(activity)
        val downloadFolder = getDownloadPath(config)
        val targetQuality = config.quality
        val shouldDownloadCover = config.cover
        val coverSize = config.coverSize

        thread {
            onStateChange(1, 0f) // Loading State
            try {
                var resolvedMediaId: String? = null
                var downloadInfoUrlStr: String? = null
                var coverUrl: String? = null
                if (shouldDownloadCover && currentAlbumArtUri != null) {
                    coverUrl = currentAlbumArtUri?.replace(Regex("\\d+x\\d+"), coverSize)
                }
                
                // Проверяем буфер обмена на наличие ссылки от кнопки "Поделиться"
                try {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    if (clipboard.hasPrimaryClip()) {
                        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        if (text.contains("music.yandex.ru/album/") && text.contains("/track/")) {
                            val matcher = java.util.regex.Pattern.compile("track/(\\d+)").matcher(text)
                            if (matcher.find()) {
                                resolvedMediaId = matcher.group(1)
                                activity.runOnUiThread { Toast.makeText(activity, "Загрузка по ссылке из буфера!", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("YaMusicLsposed", "Clipboard read error - ${e.message}")
                }
                if (resolvedMediaId == null) {
                    // Extract Album ID from Cover Art URI and find the exact track!
                    if (currentAlbumArtUri != null) {
                        try {
                            val matcher = java.util.regex.Pattern.compile("\\.a\\.(\\d+)-").matcher(currentAlbumArtUri!!)
                            if (matcher.find()) {
                                val albumId = matcher.group(1)
                                val albumUrl = java.net.URL("https://api.music.yandex.net/albums/$albumId/with-tracks")
                                val albumConn = albumUrl.openConnection() as java.net.HttpURLConnection
                                albumConn.setRequestProperty("Authorization", "OAuth $token")
                                
                                val albumResponse = albumConn.inputStream.bufferedReader().use(java.io.BufferedReader::readText)
                                val root = org.json.JSONObject(albumResponse)
                                val result = root.optJSONObject("result")
                                if (result != null) {
                                    val volumes = result.optJSONArray("volumes")
                                    if (volumes != null) {
                                        var bestDiff = Long.MAX_VALUE
                                        for (i in 0 until volumes.length()) {
                                            val volume = volumes.optJSONArray(i) ?: continue
                                            for (j in 0 until volume.length()) {
                                                val trackObj = volume.optJSONObject(j) ?: continue
                                                val tId = trackObj.optString("id")
                                                val tDuration = trackObj.optLong("durationMs", 0L)
                                                
                                                val diff = Math.abs(tDuration - currentDuration)
                                                if (diff < 3000 && diff < bestDiff) {
                                                    bestDiff = diff
                                                    resolvedMediaId = tId
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            android.util.Log.e("YaMusicLsposed", "Album API error: ${e.message}")
                        }
                    }
                }
                
                if (resolvedMediaId == null) {
                    val searchQuery = java.net.URLEncoder.encode("$artist $title", "UTF-8")
                    val searchUrl = java.net.URL("https://api.music.yandex.net/search?text=$searchQuery&type=track&page=0")
                    val searchConn = searchUrl.openConnection() as java.net.HttpURLConnection
                    searchConn.setRequestProperty("Authorization", "OAuth $token")
                    
                    try {
                        val searchResponse = searchConn.inputStream.bufferedReader().use(java.io.BufferedReader::readText)
                        val json = org.json.JSONObject(searchResponse)
                        if (json.has("result") && json.getJSONObject("result").has("tracks")) {
                            val tracks = json.getJSONObject("result").getJSONObject("tracks").getJSONArray("results")
                            
                            var bestMatchTrack: org.json.JSONObject? = null
                            var bestMatchScore = -100
                            
                            for (i in 0 until tracks.length()) {
                                val track = tracks.getJSONObject(i)
                                val tTitle = track.optString("title", "")
                                var tArtist = ""
                                if (track.has("artists")) {
                                    val arr = track.getJSONArray("artists")
                                    val list = mutableListOf<String>()
                                    for (j in 0 until arr.length()) list.add(arr.getJSONObject(j).optString("name", ""))
                                    tArtist = list.joinToString(", ")
                                }
                                
                                var score = 0
                                
                                if (tTitle.equals(title, ignoreCase = true)) score += 20
                                else if (tTitle.contains(title, ignoreCase = true) || title.contains(tTitle, ignoreCase = true)) score += 10
                                
                                if (tArtist.equals(artist, ignoreCase = true)) score += 20
                                else if (tArtist.contains(artist, ignoreCase = true) || artist.contains(tArtist, ignoreCase = true)) score += 10
                                
                                if (tTitle.equals(artist, ignoreCase = true)) score += 20
                                else if (tTitle.contains(artist, ignoreCase = true) || artist.contains(tTitle, ignoreCase = true)) score += 10
                                
                                if (tArtist.equals(title, ignoreCase = true)) score += 20
                                else if (tArtist.contains(title, ignoreCase = true) || title.contains(tArtist, ignoreCase = true)) score += 10
                                
                                if (!title.contains("Speed Up", true) && tTitle.contains("Speed Up", true)) score -= 30
                                if (!artist.contains("Speed Up", true) && tArtist.contains("Speed Up", true)) score -= 30
                                if (!title.contains("Remix", true) && tTitle.contains("Remix", true)) score -= 30
                                
                                val trackDur = track.optLong("durationMs", 0L)
                                if (currentDuration > 0L && Math.abs(trackDur - currentDuration) < 2500L) {
                                    score += 50
                                }
                                
                                if (score > bestMatchScore) {
                                    bestMatchScore = score
                                    bestMatchTrack = track
                                }
                            }
                            
                            if (bestMatchTrack != null) {
                                resolvedMediaId = bestMatchTrack.getString("id")
                                if (coverUrl == null && shouldDownloadCover && bestMatchTrack.has("coverUri")) {
                                    coverUrl = "https://" + bestMatchTrack.getString("coverUri").replace("%%", coverSize)
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        android.util.Log.e("YaMusicLsposed", "JSON parse error - ${e.message}")
                    }
                }
                
                if (resolvedMediaId == null) {
                    var directTrackId = rawMediaId.substringBefore(":")
                    if (directTrackId.length >= 7) {
                        resolvedMediaId = directTrackId
                    }
                }

                if (resolvedMediaId == null) {
                    onStateChange(0, 0f)
                    return@thread
                }
                
                // 1. Fetch track info (title, artist, cover)
                var finalTitle = title
                var finalArtist = artist
                var finalCoverUrl = coverUrl
                try {
                    val trackUrl = URL("https://api.music.yandex.net/tracks/$resolvedMediaId")
                    val trackConn = trackUrl.openConnection() as HttpURLConnection
                    if (token.isNotEmpty()) {
                        trackConn.setRequestProperty("Authorization", "OAuth $token")
                    }
                    val trackResponse = trackConn.inputStream.bufferedReader().use(BufferedReader::readText)
                    val trackJson = org.json.JSONObject(trackResponse)
                    if (trackJson.has("result")) {
                        val trackArray = trackJson.getJSONArray("result")
                        if (trackArray.length() > 0) {
                            val track = trackArray.getJSONObject(0)
                            val tTitle = track.optString("title", title)
                            val tVersion = track.optString("version", "")
                            finalTitle = if (tVersion.isNotEmpty()) "$tTitle ($tVersion)" else tTitle
                            
                            if (track.has("artists")) {
                                val arr = track.getJSONArray("artists")
                                val list = mutableListOf<String>()
                                for (j in 0 until arr.length()) list.add(arr.getJSONObject(j).optString("name", ""))
                                finalArtist = list.joinToString(", ")
                            }
                            
                            val albums = track.optJSONArray("albums")
                            if (albums != null && albums.length() > 0 && shouldDownloadCover) {
                                val coverUri = albums.getJSONObject(0).optString("coverUri", "")
                                if (coverUri.isNotEmpty()) {
                                    finalCoverUrl = "https://" + coverUri.replace("%%", coverSize)
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("YaMusicLsposed", "Failed to fetch track info: ${e.message}")
                }

                // 2. Check download-info first for 320 kbps (if Plus user)
                var directDownloadUrl: String? = null
                if (targetQuality == 320) {
                    try {
                        val infoUrl = URL("https://api.music.yandex.net/tracks/$resolvedMediaId/download-info")
                        val infoConn = infoUrl.openConnection() as HttpURLConnection
                        if (token.isNotEmpty()) {
                            infoConn.setRequestProperty("Authorization", "OAuth $token")
                        }
                        val infoResponse = infoConn.inputStream.bufferedReader().use(BufferedReader::readText)
                        val isPreview = infoResponse.contains("\"preview\":true") || !infoResponse.contains("\"preview\":false")
                        if (!isPreview) {
                            val pattern320 = Pattern.compile("\"bitrateInKbps\":320.*?\"downloadInfoUrl\":\"(https?://.*?)\"")
                            val matcher320 = pattern320.matcher(infoResponse)
                            if (matcher320.find()) {
                                val dlInfoUrlStr = matcher320.group(1)
                                val dlInfoConn = URL(dlInfoUrlStr).openConnection() as HttpURLConnection
                                if (token.isNotEmpty()) dlInfoConn.setRequestProperty("Authorization", "OAuth $token")
                                val dlInfoResponse = dlInfoConn.inputStream.bufferedReader().use(BufferedReader::readText)
                                val hostMatcher = Pattern.compile("<host>(.*?)</host>").matcher(dlInfoResponse)
                                val pathMatcher = Pattern.compile("<path>(.*?)</path>").matcher(dlInfoResponse)
                                val tsMatcher = Pattern.compile("<ts>(.*?)</ts>").matcher(dlInfoResponse)
                                val sMatcher = Pattern.compile("<s>(.*?)</s>").matcher(dlInfoResponse)
                                if (hostMatcher.find() && pathMatcher.find() && tsMatcher.find() && sMatcher.find()) {
                                    val host = hostMatcher.group(1)
                                    val path = pathMatcher.group(1)
                                    val ts = tsMatcher.group(1)
                                    val s = sMatcher.group(1)
                                    val signStr = "XGRlBW9FXlekgbPrRHuSiA${path.substring(1)}${s}"
                                    val md = MessageDigest.getInstance("MD5")
                                    val digest = md.digest(signStr.toByteArray())
                                    val sign = digest.joinToString("") { "%02x".format(it) }
                                    directDownloadUrl = "https://$host/get-mp3/$sign/$ts$path"
                                }
                            }
                        }
                    } catch (e: Throwable) {}
                }

                if (directDownloadUrl != null && !directDownloadUrl.contains("preview.mp3") && !directDownloadUrl.contains("/preview")) {
                    initNotification(activity)
                    downloadAndTagFile(
                        activity = activity,
                        mp3Url = directDownloadUrl,
                        coverUrl = finalCoverUrl,
                        artist = finalArtist,
                        title = finalTitle,
                        downloadFolder = downloadFolder,
                        decryptionKey = null,
                        codec = "mp3",
                        targetQuality = targetQuality
                    ) { progress ->
                        onStateChange(1, progress)
                        updateNotification("Скачивание трека", "$finalArtist - $finalTitle", 100, progress.toInt(), false)
                    }
                    cancelNotification()
                    onStateChange(2, 0f)
                    Thread.sleep(1000)
                    onStateChange(0, 0f)
                    return@thread
                }

                // 3. Fallback/Default: Get full stream directly via get-file-info (works for free & plus!)
                var streamInfo = findFileInfoFromCache(activity, resolvedMediaId)
                if (streamInfo == null) {
                    // Retry up to 8 times (2 seconds) in case player playback cache is finishing write
                    for (retry in 0 until 8) {
                        Thread.sleep(250)
                        streamInfo = findFileInfoFromCache(activity, resolvedMediaId)
                        if (streamInfo != null) break
                    }
                }
                if (streamInfo == null) {
                    streamInfo = fetchFileInfoDirectly(resolvedMediaId, token, activity)
                }

                if (streamInfo != null && streamInfo.url.isNotEmpty() && !streamInfo.url.contains("preview.mp3") && !streamInfo.url.contains("/preview")) {
                    android.util.Log.i("YaMusicLsposed", "Downloading full track via get-file-info for $resolvedMediaId")
                    initNotification(activity)
                    downloadAndTagFile(
                        activity = activity,
                        mp3Url = streamInfo.url,
                        coverUrl = finalCoverUrl,
                        artist = finalArtist,
                        title = finalTitle,
                        downloadFolder = downloadFolder,
                        decryptionKey = streamInfo.key.ifEmpty { null },
                        codec = streamInfo.codec,
                        targetQuality = targetQuality
                    ) { progress ->
                        onStateChange(1, progress)
                        updateNotification("Скачивание трека", "$finalArtist - $finalTitle", 100, progress.toInt(), false)
                    }
                    cancelNotification()
                    onStateChange(2, 0f)
                    Thread.sleep(1000)
                    onStateChange(0, 0f)
                    return@thread
                }

                activity.runOnUiThread {
                    Toast.makeText(activity, "Не удалось получить полный поток трека. Запустите воспроизведение трека в плеере.", Toast.LENGTH_LONG).show()
                }
                onStateChange(0, 0f)
            } catch (e: Throwable) {
                cancelNotification()
                android.util.Log.e("YaMusicLsposed", "Download error - ${e.stackTraceToString()}")
            }
        }
    }

    private fun downloadAndTagFile(
        activity: Activity,
        mp3Url: String,
        coverUrl: String?,
        artist: String,
        title: String,
        downloadFolder: String,
        trackIndex: Int = 0,
        totalTracks: Int = 1,
        cancelCheck: (() -> Boolean)? = null,
        decryptionKey: String? = null,
        codec: String = "mp3",
        targetQuality: Int = 320,
        onProgress: (Float) -> Unit
    ) {
        if (mp3Url.contains("preview.mp3") || mp3Url.contains("/preview")) {
            android.util.Log.e("YaMusicLsposed", "Aborted download: refusing to download 30s preview file ($mp3Url)")
            return
        }
        val isM4a = codec.contains("aac") || codec.contains("mp4")
        val cleanArtist = artist.replace("/", "_").replace("\\", "_").replace(":", "_")
        val cleanTitle = title.replace("/", "_").replace("\\", "_").replace(":", "_")
        val fileName = "$cleanArtist - $cleanTitle.mp3"
        val outDir = File(downloadFolder)
        if (!outDir.exists()) outDir.mkdirs()
        val finalOutFile = File(outDir, fileName)
        val tempRawFile = File(activity.cacheDir, "raw_${System.currentTimeMillis()}_$cleanTitle.tmp")
        
        try {
            val mp3Conn = URL(mp3Url).openConnection() as HttpURLConnection
            mp3Conn.connectTimeout = 15000
            mp3Conn.readTimeout = 15000
            val contentLength = mp3Conn.contentLength
            
            val cipher = if (!decryptionKey.isNullOrEmpty()) {
                val keyBytes = hexStringToByteArray(decryptionKey)
                val secretKey = SecretKeySpec(keyBytes, "AES")
                val ivSpec = IvParameterSpec(ByteArray(16))
                Cipher.getInstance("AES/CTR/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
                }
            } else null
            
            mp3Conn.inputStream.use { input ->
                tempRawFile.outputStream().use { output ->
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastReportTime = System.currentTimeMillis()
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (cancelCheck?.invoke() == true) {
                            throw RuntimeException("Cancelled")
                        }
                        if (cipher != null) {
                            val decrypted = cipher.update(buffer, 0, bytesRead)
                            if (decrypted != null && decrypted.isNotEmpty()) {
                                output.write(decrypted)
                            }
                        } else {
                            output.write(buffer, 0, bytesRead)
                        }
                        totalRead += bytesRead
                        val now = System.currentTimeMillis()
                        if (contentLength > 0 && (now - lastReportTime > 200 || totalRead == contentLength.toLong())) {
                            lastReportTime = now
                            val p = (totalRead.toFloat() / contentLength) * (if (isM4a) 50f else 99f)
                            onProgress(p.coerceIn(0f, 99f))
                        }
                    }
                    if (cipher != null) {
                        val finalBytes = cipher.doFinal()
                        if (finalBytes != null && finalBytes.isNotEmpty()) {
                            output.write(finalBytes)
                        }
                    }
                }
            }

            var isMp4Format = isM4a
            if (tempRawFile.length() > 8) {
                val header = ByteArray(8)
                tempRawFile.inputStream().use { it.read(header) }
                if (header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()) {
                    isMp4Format = true
                }
            }

            val pcmOrMp3File: File
            if (isMp4Format) {
                val convertedMp3 = File(activity.cacheDir, "conv_${System.currentTimeMillis()}_$cleanTitle.mp3")
                val bitrate = if (targetQuality >= 320) 320 else 192
                val converted = convertM4aToMp3(
                    inputFile = tempRawFile,
                    outputFile = convertedMp3,
                    targetBitrateKbps = bitrate,
                    context = activity,
                    cancelCheck = cancelCheck
                ) { convProgress ->
                    val p = 50f + (convProgress * 0.49f)
                    onProgress(p.coerceIn(50f, 99f))
                }
                if (converted && convertedMp3.exists() && convertedMp3.length() > 0) {
                    pcmOrMp3File = convertedMp3
                    tempRawFile.delete()
                } else {
                    android.util.Log.e("YaMusicLsposed", "Conversion to MP3 failed, using raw audio")
                    pcmOrMp3File = tempRawFile
                }
            } else {
                pcmOrMp3File = tempRawFile
            }

            var tagged = false
            try {
                val mp3file = Mp3File(pcmOrMp3File.absolutePath)
                val id3v2Tag = if (mp3file.hasId3v2Tag()) mp3file.id3v2Tag else ID3v24Tag()
                id3v2Tag.artist = artist
                id3v2Tag.title = title
                
                if (coverUrl != null) {
                    try {
                        val coverConn = URL(coverUrl).openConnection() as HttpURLConnection
                        coverConn.connectTimeout = 10000
                        coverConn.readTimeout = 10000
                        val coverBytes = coverConn.inputStream.readBytes()
                        id3v2Tag.setAlbumImage(coverBytes, "image/jpeg")
                    } catch (e: Throwable) {
                        android.util.Log.e("YaMusicLsposed", "Failed to download cover - ${e.message}")
                    }
                }
                
                mp3file.id3v2Tag = id3v2Tag
                val taggedTempFile = File(activity.cacheDir, "tagged_${System.currentTimeMillis()}_$cleanTitle.mp3")
                mp3file.save(taggedTempFile.absolutePath)
                
                taggedTempFile.copyTo(finalOutFile, overwrite = true)
                pcmOrMp3File.delete()
                taggedTempFile.delete()
                tagged = true
            } catch (t: Throwable) {
                android.util.Log.e("YaMusicLsposed", "Mp3 tagging error: ${t.message}, fallback to raw copy")
            }
            if (!tagged) {
                pcmOrMp3File.copyTo(finalOutFile, overwrite = true)
                pcmOrMp3File.delete()
            }
            
            val baseTime = System.currentTimeMillis()
            val timeOffset = (totalTracks - trackIndex) * 1000L
            finalOutFile.setLastModified(baseTime + timeOffset)

            try {
                MediaScannerConnection.scanFile(
                    activity,
                    arrayOf(finalOutFile.absolutePath),
                    arrayOf("audio/mpeg"),
                    null
                )
            } catch (e: Throwable) {}
        } catch (e: Throwable) {
            android.util.Log.e("YaMusicLsposed", "Download error - ${e.stackTraceToString()}")
        } finally {
            try { tempRawFile.delete() } catch (t: Throwable) {}
        }
    }

    private fun downloadTrackById(
        activity: Activity,
        trackId: String,
        title: String,
        artist: String,
        coverUrl: String?,
        targetQuality: Int,
        downloadFolder: String,
        token: String,
        trackIndex: Int = 0,
        totalTracks: Int = 1,
        cancelCheck: (() -> Boolean)? = null,
        onStateChange: (Int, Float) -> Unit,
        onSuccess: () -> Unit,
        onError: (() -> Unit)? = null
    ) {
        kotlin.concurrent.thread {
            try {
                if (cancelCheck?.invoke() == true) { onError?.invoke(); return@thread }

                // 1. Try download-info for 320kbps full track (if user has Plus)
                var directDownloadUrl: String? = null
                if (targetQuality == 320) {
                    try {
                        val infoUrl = URL("https://api.music.yandex.net/tracks/$trackId/download-info")
                        val infoConn = infoUrl.openConnection() as HttpURLConnection
                        infoConn.connectTimeout = 10000
                        infoConn.readTimeout = 10000
                        if (token.isNotEmpty()) {
                            infoConn.setRequestProperty("Authorization", "OAuth $token")
                        }
                        val infoResponse = infoConn.inputStream.bufferedReader().use(BufferedReader::readText)
                        val isPreview = infoResponse.contains("\"preview\":true") || !infoResponse.contains("\"preview\":false")
                        if (!isPreview) {
                            val pattern320 = Pattern.compile("\"bitrateInKbps\":320.*?\"downloadInfoUrl\":\"(https?://.*?)\"")
                            val matcher320 = pattern320.matcher(infoResponse)
                            if (matcher320.find()) {
                                val dlInfoUrlStr = matcher320.group(1)
                                val dlInfoConn = URL(dlInfoUrlStr).openConnection() as HttpURLConnection
                                dlInfoConn.connectTimeout = 10000
                                dlInfoConn.readTimeout = 10000
                                if (token.isNotEmpty()) dlInfoConn.setRequestProperty("Authorization", "OAuth $token")
                                val dlInfoResponse = dlInfoConn.inputStream.bufferedReader().use(BufferedReader::readText)
                                val hostMatcher = Pattern.compile("<host>(.*?)</host>").matcher(dlInfoResponse)
                                val pathMatcher = Pattern.compile("<path>(.*?)</path>").matcher(dlInfoResponse)
                                val tsMatcher = Pattern.compile("<ts>(.*?)</ts>").matcher(dlInfoResponse)
                                val sMatcher = Pattern.compile("<s>(.*?)</s>").matcher(dlInfoResponse)
                                if (hostMatcher.find() && pathMatcher.find() && tsMatcher.find() && sMatcher.find()) {
                                    val host = hostMatcher.group(1)
                                    val path = pathMatcher.group(1)
                                    val ts = tsMatcher.group(1)
                                    val s = sMatcher.group(1)
                                    val signStr = "XGRlBW9FXlekgbPrRHuSiA${path.substring(1)}${s}"
                                    val md = MessageDigest.getInstance("MD5")
                                    val digest = md.digest(signStr.toByteArray())
                                    val sign = digest.joinToString("") { "%02x".format(it) }
                                    directDownloadUrl = "https://$host/get-mp3/$sign/$ts$path"
                                }
                            }
                        }
                    } catch (e: Throwable) {}
                }

                if (directDownloadUrl != null && !directDownloadUrl.contains("preview.mp3") && !directDownloadUrl.contains("/preview")) {
                    downloadAndTagFile(
                        activity = activity,
                        mp3Url = directDownloadUrl,
                        coverUrl = coverUrl,
                        artist = artist,
                        title = title,
                        downloadFolder = downloadFolder,
                        trackIndex = trackIndex,
                        totalTracks = totalTracks,
                        cancelCheck = cancelCheck,
                        decryptionKey = null,
                        codec = "mp3",
                        targetQuality = targetQuality
                    ) { progress ->
                        onStateChange(1, progress)
                    }
                    onStateChange(2, 100f)
                    onSuccess()
                    return@thread
                }

                // 2. Fallback/Default: Get full stream directly via get-file-info
                var streamInfo = findFileInfoFromCache(activity, trackId)
                if (streamInfo == null) {
                    streamInfo = fetchFileInfoDirectly(trackId, token, activity)
                }

                if (streamInfo != null && streamInfo.url.isNotEmpty() && !streamInfo.url.contains("preview.mp3") && !streamInfo.url.contains("/preview")) {
                    downloadAndTagFile(
                        activity = activity,
                        mp3Url = streamInfo.url,
                        coverUrl = coverUrl,
                        artist = artist,
                        title = title,
                        downloadFolder = downloadFolder,
                        trackIndex = trackIndex,
                        totalTracks = totalTracks,
                        cancelCheck = cancelCheck,
                        decryptionKey = streamInfo.key.ifEmpty { null },
                        codec = streamInfo.codec,
                        targetQuality = targetQuality
                    ) { progress ->
                        onStateChange(1, progress)
                    }
                    onStateChange(2, 100f)
                    onSuccess()
                    return@thread
                }

                android.util.Log.w("YaMusicLsposed", "Full stream not available for track $trackId ($title), skipping preview")
                onError?.invoke() ?: onStateChange(0, 0f)
            } catch (e: Throwable) {
                if (e.message != "Cancelled") {
                    android.util.Log.e("YaMusicLsposed", "Download error track $trackId: ${e.message}")
                }
                onError?.invoke() ?: onStateChange(0, 0f)
            }
        }
    }

    private fun downloadPlaylistOrAlbum(
        activity: Activity,
        userId: String?,
        playlistId: String?,
        albumId: String?,
        targetQuality: Int,
        downloadFolder: String,
        token: String,
        coverSize: String,
        onStateChange: (Int, Float, String) -> Unit
    ) {
        kotlin.concurrent.thread {
            try {
                val isAlbum = albumId != null
                onStateChange(1, 0f, if (isAlbum) "Получение альбома..." else "Получение плейлиста...")
                
                val urlStr = if (isAlbum) {
                    "https://api.music.yandex.net/albums/$albumId/with-tracks"
                } else {
                    "https://api.music.yandex.net/users/$userId/playlists/$playlistId"
                }
                
                val url = java.net.URL(urlStr)
                val conn = url.openConnection() as java.net.HttpURLConnection
                if (token.isNotEmpty()) {
                    conn.setRequestProperty("Authorization", "OAuth $token")
                }
                
                val response = conn.inputStream.bufferedReader().use(java.io.BufferedReader::readText)
                val json = org.json.JSONObject(response)
                
                if (!json.has("result")) {
                    onStateChange(0, 0f, if (isAlbum) "Альбом не найден!" else "Плейлист не найден!")
                    return@thread
                }
                
                val result = json.getJSONObject("result")
                val collectionTitle = result.optString("title", if (isAlbum) "Unknown Album" else "Unknown Playlist")
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                
                val targetFolder = "$downloadFolder/${if (isAlbum) "Albums" else "Playlists"}/$collectionTitle"
                
                var trackArray: org.json.JSONArray? = null
                
                if (isAlbum) {
                    if (result.has("volumes")) {
                        val volumes = result.getJSONArray("volumes")
                        trackArray = org.json.JSONArray()
                        for (i in 0 until volumes.length()) {
                            val volume = volumes.getJSONArray(i)
                            for (j in 0 until volume.length()) trackArray.put(volume.getJSONObject(j))
                        }
                    }
                } else {
                    if (result.has("tracks")) {
                         val t = result.get("tracks")
                         if (t is org.json.JSONArray) trackArray = t
                         else if (t is org.json.JSONObject && t.has("results")) trackArray = t.getJSONArray("results")
                    }
                }

                if (trackArray == null || trackArray.length() == 0) {
                    onStateChange(0, 0f, if (isAlbum) "Альбом пуст!" else "Плейлист пуст!")
                    return@thread
                }
                
                val tracksToDownload = mutableListOf<org.json.JSONObject>()
                for (i in 0 until trackArray.length()) {
                    val item = trackArray.getJSONObject(i)
                    val track = if (item.has("track")) item.getJSONObject("track") else item
                    tracksToDownload.add(track)
                }

                var index = 0
                val total = tracksToDownload.size
                var completedCount = 0
                var successCount = 0
                var skippedCount = 0
                var activeCount = 0
                val maxConcurrent = 4
                val lock = Object()
                
                initNotification(activity)

                fun downloadNext() {
                    synchronized(lock) {
                        if (cancelPlaylistDownload) {
                            if (activeCount == 0) {
                                DownloadState.activeDownloads.clear()
                                cancelNotification()
                                onStateChange(0, 0f, "Отменено")
                            }
                            return
                        }

                        if (completedCount >= total) {
                            DownloadState.activeDownloads.clear()
                            cancelNotification()
                            val msg = if (skippedCount > 0) "Готово: $successCount, пропущено: $skippedCount" else "Готово!"
                            onStateChange(2, 100f, msg)
                            if (skippedCount > 0) {
                                activity.runOnUiThread {
                                    Toast.makeText(activity, "Скачано $successCount из $total. Пропущенные треки требуют воспроизведения в плеере или подписки Плюс.", Toast.LENGTH_LONG).show()
                                }
                            }
                            return
                        }

                        while (activeCount < maxConcurrent && index < total) {
                            val currentIndex = index
                            index++
                            
                            val track = tracksToDownload[currentIndex]
                            val tId = track.optString("id", "")
                            var tTitle = track.optString("title", "Unknown")
                            val tVersion = track.optString("version", "")
                            if (tVersion.isNotEmpty()) {
                                tTitle = "$tTitle ($tVersion)"
                            }
                            var tArtist = "Unknown"
                            if (track.has("artists")) {
                                val arr = track.getJSONArray("artists")
                                val list = mutableListOf<String>()
                                for (j in 0 until arr.length()) list.add(arr.getJSONObject(j).optString("name", ""))
                                tArtist = list.joinToString(", ")
                            }
                            
                            var tCoverUrl: String? = null
                            val albums = track.optJSONArray("albums")
                            if (albums != null && albums.length() > 0) {
                                val coverUri = albums.getJSONObject(0).optString("coverUri", "")
                                if (coverUri.isNotEmpty()) {
                                    tCoverUrl = "https://" + coverUri.replace("%%", coverSize)
                                }
                            }

                            if (tId.isEmpty() || !track.has("title")) {
                                // Deleted or unavailable track — skip
                                completedCount++
                                skippedCount++
                                continue
                            }
                            
                            activeCount++
                            val activeDownload = ActiveDownload(tId, tTitle, tArtist)
                            DownloadState.activeDownloads.add(activeDownload)
                            
                            fun advanceNext(success: Boolean) {
                                DownloadState.activeDownloads.remove(activeDownload)
                                synchronized(lock) {
                                    activeCount--
                                    completedCount++
                                    if (success) successCount++ else skippedCount++
                                    val overallProgress = (completedCount.toFloat() / total) * 100f
                                    onStateChange(1, overallProgress, "$completedCount/$total")
                                    updateNotification(if (isAlbum) "Скачивание альбома" else "Скачивание плейлиста", "$completedCount/$total", 100, overallProgress.toInt(), false)
                                }
                                downloadNext()
                            }

                            downloadTrackById(
                                activity, tId, tTitle, tArtist, tCoverUrl, targetQuality, targetFolder, token, currentIndex, total,
                                { cancelPlaylistDownload },
                                { _, p -> activeDownload.progress = p },
                                { advanceNext(true) },
                                { advanceNext(false) }
                            )
                        }
                    }
                }
                downloadNext()

            } catch (e: Exception) {
                cancelNotification()
                onStateChange(0, 0f, "Ошибка: ${e.message}")
            }
        }
    }
    private var notificationManager: android.app.NotificationManager? = null
    private var notificationBuilder: android.app.Notification.Builder? = null
    private val NOTIFICATION_ID = 9999

    private fun initNotification(activity: Activity) {
        if (notificationManager == null) {
            notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel("yamusicdl_channel", "YaMusicDL", android.app.NotificationManager.IMPORTANCE_LOW)
                notificationManager?.createNotificationChannel(channel)
            }
        }
        
        notificationBuilder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Notification.Builder(activity, "yamusicdl_channel")
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(activity)
        }
        
        val iconId = activity.resources.getIdentifier("ic_launcher", "mipmap", activity.packageName).takeIf { it != 0 }
            ?: activity.resources.getIdentifier("ic_launcher", "drawable", activity.packageName).takeIf { it != 0 }
            ?: android.R.drawable.stat_sys_download
            
        notificationBuilder?.setSmallIcon(iconId)
            ?.setContentTitle("Подготовка...")
            ?.setOngoing(true)
            ?.setProgress(100, 0, true)
            
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            notificationBuilder?.setForegroundServiceBehavior(android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
    }

    private fun updateNotification(title: String, text: String, max: Int, progress: Int, indeterminate: Boolean) {
        notificationBuilder?.setContentTitle(title)
            ?.setContentText(text)
            ?.setProgress(max, progress, indeterminate)
        try {
            notificationManager?.notify(NOTIFICATION_ID, notificationBuilder?.build())
        } catch (e: Exception) {}
    }

    private fun cancelNotification() {
        notificationManager?.cancel(NOTIFICATION_ID)
    }
}
