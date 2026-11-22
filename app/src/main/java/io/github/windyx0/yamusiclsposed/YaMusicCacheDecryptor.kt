package io.github.windyx0.yamusiclsposed

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class CachedTrackItem(
    val file: File,
    val sizeBytes: Long,
    val estimatedTrackId: String?,
    val displayName: String
)

data class DecryptResult(
    val success: Boolean,
    val message: String,
    val exportedFile: File? = null
)

object YaMusicCacheDecryptor {
    private const val DESKTOP_SIGN_KEY = "kzqU4XhfCaY6B6JTHODeq5"
    private const val DESKTOP_CLIENT = "YandexMusicDesktopAppWindows/5.95.0"
    private val FILE_INFO_CODECS = listOf("flac", "flac-mp4", "aac-mp4", "he-aac-mp4", "aac", "he-aac", "mp3")

    fun getPotentialCacheDirs(context: Context): List<File> {
        val dirs = mutableListOf<File>()
        try {
            dirs.add(File(context.cacheDir, "exo"))
            dirs.add(File(context.cacheDir, "cache_control_http_cache"))
            dirs.add(context.cacheDir)
            context.externalCacheDir?.let { dirs.add(it) }
            context.externalCacheDir?.let { dirs.add(File(it, "exo")) }

            val extStorage = Environment.getExternalStorageDirectory()
            dirs.add(File(extStorage, "Android/data/ru.yandex.music/cache"))
            dirs.add(File(extStorage, "Android/data/ru.yandex.music/cache/exo"))
            dirs.add(File(extStorage, "Android/data/ru.yandex.music/files"))
            dirs.add(File(extStorage, "Android/data/ru.yandex.music/files/tracks"))
            dirs.add(File(extStorage, "Android/media/ru.yandex.music"))
        } catch (e: Throwable) {}
        return dirs.filter { it.exists() && it.canRead() }
    }

    fun scanCacheFiles(context: Context, customDir: File? = null): List<CachedTrackItem> {
        val searchDirs = if (customDir != null && customDir.exists()) listOf(customDir) else getPotentialCacheDirs(context)
        val result = mutableListOf<CachedTrackItem>()
        val seenPaths = mutableSetOf<String>()

        fun walk(dir: File, depth: Int = 0) {
            if (depth > 4) return
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (f.isDirectory) {
                    walk(f, depth + 1)
                } else if (f.isFile && f.length() > 100 * 1024) { // Files > 100 KB
                    if (seenPaths.add(f.absolutePath)) {
                        val name = f.name
                        var estimatedId: String? = null

                        // Try to find track ID in filename
                        val m1 = Regex("(\\d{6,10})").find(name)
                        if (m1 != null) {
                            estimatedId = m1.groupValues[1]
                        }

                        // Try to read first few bytes for hints
                        if (estimatedId == null) {
                            try {
                                val buf = ByteArray(1024)
                                val read = FileInputStream(f).use { it.read(buf) }
                                if (read > 0) {
                                    val headerText = String(buf, 0, read, Charsets.ISO_8859_1)
                                    val m2 = Regex("trackId=(\\d+)").find(headerText)
                                    if (m2 != null) estimatedId = m2.groupValues[1]
                                }
                            } catch (e: Throwable) {}
                        }

                        val displayName = if (estimatedId != null) "Трек #$estimatedId (${formatSize(f.length())})" else "${f.name} (${formatSize(f.length())})"
                        result.add(CachedTrackItem(f, f.length(), estimatedId, displayName))
                    }
                }
            }
        }

        for (dir in searchDirs) {
            walk(dir)
        }
        return result.sortedByDescending { it.sizeBytes }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.1f МБ".format(bytes.toFloat() / (1024 * 1024))
            bytes >= 1024 -> "%.0f КБ".format(bytes.toFloat() / 1024)
            else -> "$bytes Б"
        }
    }

    fun fetchKeyAndCodecFromApi(trackId: String, token: String): Pair<String, String>? {
        return try {
            val timestamp = (System.currentTimeMillis() / 1000).toInt()
            val transport = "encraw"
            val quality = "lossless"
            val codecsStr = FILE_INFO_CODECS.joinToString("")
            val message = "$timestamp$trackId$quality$codecsStr$transport"

            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(DESKTOP_SIGN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val signBytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            val rawSign = android.util.Base64.encodeToString(signBytes, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING).trimEnd('=')

            val query = "ts=$timestamp&trackId=$trackId&quality=$quality&codecs=${FILE_INFO_CODECS.joinToString(",")}&transports=$transport&sign=${URLEncoder.encode(rawSign, "UTF-8")}"
            val url = URL("https://api.music.yandex.net/get-file-info?$query")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "OAuth $token")
            conn.setRequestProperty("X-Yandex-Music-Client", DESKTOP_CLIENT)
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (conn.responseCode in 200..299) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val dlInfo = json.optJSONObject("result")?.optJSONObject("downloadInfo")
                val key = dlInfo?.optString("key", "") ?: ""
                val codec = dlInfo?.optString("codec", "mp3") ?: "mp3"
                if (key.isNotEmpty()) {
                    Pair(key, codec)
                } else null
            } else null
        } catch (e: Throwable) {
            android.util.Log.e("YaMusicCacheDecryptor", "Error fetching key: ${e.message}")
            null
        }
    }

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    fun decryptFile(
        inputFile: File,
        outputFile: File,
        keyHex: String,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        return try {
            val keyBytes = hexToBytes(keyHex)
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ByteArray(16)) // 16-byte zero IV

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            val totalBytes = inputFile.length()
            var processed = 0L

            FileInputStream(inputFile).use { fis ->
                CipherInputStream(fis, cipher).use { cis ->
                    FileOutputStream(outputFile).use { fos ->
                        val buffer = ByteArray(32768)
                        var bytesRead: Int
                        var lastReport = System.currentTimeMillis()
                        while (cis.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                            processed += bytesRead
                            val now = System.currentTimeMillis()
                            if (totalBytes > 0 && (now - lastReport > 200 || processed == totalBytes)) {
                                lastReport = now
                                onProgress((processed.toFloat() / totalBytes) * 100f)
                            }
                        }
                    }
                }
            }
            outputFile.exists() && outputFile.length() > 0
        } catch (e: Throwable) {
            android.util.Log.e("YaMusicCacheDecryptor", "Decryption failed: ${e.message}")
            outputFile.delete()
            false
        }
    }

    fun extractFlacFromMp4(mp4Bytes: ByteArray): ByteArray? {
        try {
            val dflaPos = indexOf(mp4Bytes, "dfLa".toByteArray(Charsets.ISO_8859_1))
            val mdatPos = indexOf(mp4Bytes, "mdat".toByteArray(Charsets.ISO_8859_1))
            if (dflaPos != -1 && mdatPos != -1) {
                val dflaSize = ByteBuffer.wrap(mp4Bytes, dflaPos - 4, 4).order(ByteOrder.BIG_ENDIAN).int
                val flacMeta = mp4Bytes.copyOfRange(dflaPos + 8, dflaPos - 4 + dflaSize)
                val rawAudio = mp4Bytes.copyOfRange(mdatPos + 4, mp4Bytes.size)

                val out = ByteArray(4 + flacMeta.size + rawAudio.size)
                System.arraycopy("fLaC".toByteArray(Charsets.ISO_8859_1), 0, out, 0, 4)
                System.arraycopy(flacMeta, 0, out, 4, flacMeta.size)
                System.arraycopy(rawAudio, 0, out, 4 + flacMeta.size, rawAudio.size)
                return out
            }
        } catch (e: Throwable) {
            android.util.Log.e("YaMusicCacheDecryptor", "FLAC extract error: ${e.message}")
        }
        return null
    }

    private fun indexOf(data: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || data.size < target.size) return -1
        outer@ for (i in 0..data.size - target.size) {
            for (j in target.indices) {
                if (data[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
    }

    fun decryptAndExport(
        context: Context,
        cacheFile: File,
        trackId: String,
        token: String,
        targetDir: File,
        onProgress: (Float) -> Unit
    ): DecryptResult {
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            return DecryptResult(false, "Файл кэша не найден или пуст")
        }

        // 1. Get Key and Codec from API
        onProgress(5f)
        val keyInfo = fetchKeyAndCodecFromApi(trackId, token)
        if (keyInfo == null) {
            return DecryptResult(false, "Не удалось получить ключ дешифрования для трека $trackId. Проверьте токен.")
        }
        val (keyHex, codec) = keyInfo

        // 2. Fetch track metadata
        onProgress(15f)
        var title = "Track $trackId"
        var artist = "Unknown Artist"
        var coverUrl: String? = null
        try {
            val trackUrl = URL("https://api.music.yandex.net/tracks/$trackId")
            val conn = trackUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.setRequestProperty("Authorization", "OAuth $token")
            if (conn.responseCode in 200..299) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val arr = json.optJSONArray("result")
                if (arr != null && arr.length() > 0) {
                    val tr = arr.getJSONObject(0)
                    val tTitle = tr.optString("title", title)
                    val tVer = tr.optString("version", "")
                    title = if (tVer.isNotEmpty()) "$tTitle ($tVer)" else tTitle

                    val artArr = tr.optJSONArray("artists")
                    if (artArr != null && artArr.length() > 0) {
                        val names = mutableListOf<String>()
                        for (i in 0 until artArr.length()) names.add(artArr.getJSONObject(i).optString("name", ""))
                        artist = names.joinToString(", ")
                    }

                    val albums = tr.optJSONArray("albums")
                    if (albums != null && albums.length() > 0) {
                        val cUri = albums.getJSONObject(0).optString("coverUri", "")
                        if (cUri.isNotEmpty()) coverUrl = "https://" + cUri.replace("%%", "1000x1000")
                    }
                }
            }
        } catch (e: Throwable) {}

        val cleanTitle = sanitizeFileName(title).ifEmpty { "Track $trackId" }
        val cleanArtist = sanitizeFileName(artist).ifEmpty { "Unknown Artist" }

        // 3. Decrypt into temp file
        val tempDecrypted = File(context.cacheDir, "dec_${System.currentTimeMillis()}.tmp")
        val decryptedOk = decryptFile(cacheFile, tempDecrypted, keyHex) { p ->
            onProgress(20f + (p * 0.6f))
        }

        if (!decryptedOk || !tempDecrypted.exists() || tempDecrypted.length() == 0L) {
            tempDecrypted.delete()
            return DecryptResult(false, "Ошибка дешифрования данных AES-128-CTR")
        }

        onProgress(85f)
        if (!targetDir.exists()) targetDir.mkdirs()

        // 4. Determine format and tag
        var extension = when {
            codec.contains("flac") -> "flac"
            codec.contains("aac") || codec.contains("mp4") -> "m4a"
            else -> "mp3"
        }

        val outFile = File(targetDir, "$cleanArtist - $cleanTitle.$extension")

        try {
            if (codec.contains("flac")) {
                val bytes = tempDecrypted.readBytes()
                val pureFlac = extractFlacFromMp4(bytes)
                if (pureFlac != null) {
                    outFile.writeBytes(pureFlac)
                } else {
                    tempDecrypted.copyTo(outFile, overwrite = true)
                }
            } else if (extension == "mp3") {
                try {
                    val mp3 = Mp3File(tempDecrypted.absolutePath)
                    val tag = if (mp3.hasId3v2Tag()) mp3.id3v2Tag else ID3v24Tag()
                    tag.artist = artist
                    tag.title = title
                    if (coverUrl != null) {
                        try {
                            val cConn = URL(coverUrl).openConnection() as HttpURLConnection
                            tag.setAlbumImage(cConn.inputStream.readBytes(), "image/jpeg")
                        } catch (e: Throwable) {}
                    }
                    mp3.id3v2Tag = tag
                    val tagged = File(context.cacheDir, "tagged_${System.currentTimeMillis()}.mp3")
                    mp3.save(tagged.absolutePath)
                    tagged.copyTo(outFile, overwrite = true)
                    tagged.delete()
                } catch (e: Throwable) {
                    // Fallback to raw copy if tagging fails
                    tempDecrypted.copyTo(outFile, overwrite = true)
                }
            } else {
                tempDecrypted.copyTo(outFile, overwrite = true)
            }
        } finally {
            tempDecrypted.delete()
        }

        // 5. MediaScanner notification
        try {
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), null, null)
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply { data = Uri.fromFile(outFile) }
            context.sendBroadcast(intent)
        } catch (e: Throwable) {}

        onProgress(100f)
        return if (outFile.exists() && outFile.length() > 0) {
            DecryptResult(true, "Успешно экспортировано: ${outFile.name}", outFile)
        } else {
            DecryptResult(false, "Не удалось записать файл на накопитель")
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }
}
