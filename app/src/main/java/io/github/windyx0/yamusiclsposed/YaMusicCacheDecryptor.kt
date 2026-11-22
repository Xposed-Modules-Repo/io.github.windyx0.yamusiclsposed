package io.github.windyx0.yamusiclsposed

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class CachedChunkFile(
    val file: File,
    val offset: Long,
    val length: Long
)

data class CachedTrackGroup(
    val exoId: Int,
    val trackId: String,
    val artist: String,
    val title: String,
    val isPermanent: Boolean,
    val totalSizeBytes: Long,
    val chunks: List<CachedChunkFile>,
    val codec: String
) {
    val displayName: String
        get() = "$artist - $title"

    val formattedSize: String
        get() = when {
            totalSizeBytes >= 1024 * 1024 -> "%.1f МБ".format(totalSizeBytes.toFloat() / (1024 * 1024))
            totalSizeBytes >= 1024 -> "%.0f КБ".format(totalSizeBytes.toFloat() / 1024)
            else -> "$totalSizeBytes Б"
        }
}

data class DecryptResult(
    val success: Boolean,
    val message: String,
    val exportedFile: File? = null
)

object YaMusicCacheDecryptor {
    private const val DESKTOP_SIGN_KEY = "kzqU4XhfCaY6B6JTHODeq5"
    private const val DESKTOP_CLIENT = "YandexMusicDesktopAppWindows/5.95.0"

    private data class TrackMeta(
        val title: String,
        val artist: String,
        val isPermanent: Boolean
    )

    private data class ExoStreamMeta(
        val trackId: String,
        val codec: String,
        val meta: TrackMeta?
    )

    fun scanCacheTracks(context: Context): List<CachedTrackGroup> {
        val result = mutableListOf<CachedTrackGroup>()
        try {
            // 1. Scan SQLite databases for track metadata (ymusic.sqlite / userDb)
            val trackMetadata = mutableMapOf<String, TrackMeta>()
            val dbDir = File(context.applicationInfo.dataDir, "databases")
            if (dbDir.exists()) {
                val dbFiles = dbDir.listFiles() ?: emptyArray()
                for (dbFile in dbFiles) {
                    if (dbFile.isFile && (dbFile.name.startsWith("ymusic.sqlite") || dbFile.name.startsWith("userDb"))) {
                        try {
                            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                            try {
                                val cursor = db.rawQuery(
                                    "SELECT real_id, name, artist_name, is_permanent FROM track_mview",
                                    null
                                )
                                val idCol = cursor.getColumnIndex("real_id")
                                val nameCol = cursor.getColumnIndex("name")
                                val artistCol = cursor.getColumnIndex("artist_name")
                                val permCol = cursor.getColumnIndex("is_permanent")
                                while (cursor.moveToNext()) {
                                    val rId = cursor.getString(idCol) ?: continue
                                    val name = cursor.getString(nameCol) ?: "Unknown Title"
                                    val rawArtist = cursor.getString(artistCol) ?: "Unknown Artist"
                                    val cleanArtist = rawArtist.replace("#|", "").replace("#", ", ").trim()
                                    val isPerm = cursor.getInt(permCol) == 1
                                    trackMetadata[rId] = TrackMeta(name, cleanArtist.ifEmpty { "Unknown Artist" }, isPerm)
                                }
                                cursor.close()
                            } catch (e: Throwable) {
                                // Fallback: try track table
                                try {
                                    val cursor = db.rawQuery("SELECT original_id, name, ugc_artist_name FROM track", null)
                                    val idCol = cursor.getColumnIndex("original_id")
                                    val nameCol = cursor.getColumnIndex("name")
                                    val artCol = cursor.getColumnIndex("ugc_artist_name")
                                    while (cursor.moveToNext()) {
                                        val oId = cursor.getString(idCol) ?: continue
                                        val name = cursor.getString(nameCol) ?: "Unknown Title"
                                        val art = cursor.getString(artCol) ?: "Unknown Artist"
                                        trackMetadata.putIfAbsent(oId, TrackMeta(name, art, false))
                                    }
                                    cursor.close()
                                } catch (ignored: Throwable) {}
                            }
                            db.close()
                        } catch (e: Throwable) {}
                    }
                }
            }

            // 2. Scan ExoPlayer databases for stream index (Exo ID -> trackId)
            val exoStreams = mutableMapOf<Int, ExoStreamMeta>()
            if (dbDir.exists()) {
                val dbFiles = dbDir.listFiles() ?: emptyArray()
                for (dbFile in dbFiles) {
                    if (dbFile.isFile && (dbFile.name.startsWith("exo_music") || dbFile.name.startsWith("exoplayer"))) {
                        try {
                            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                            val tablesCursor = db.rawQuery(
                                "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'ExoPlayerCacheIndex%'",
                                null
                            )
                            val tableNames = mutableListOf<String>()
                            while (tablesCursor.moveToNext()) {
                                tableNames.add(tablesCursor.getString(0))
                            }
                            tablesCursor.close()

                            for (tbl in tableNames) {
                                val c = db.rawQuery("SELECT id, key FROM $tbl", null)
                                while (c.moveToNext()) {
                                    val exoId = c.getInt(0)
                                    val key = c.getString(1) ?: continue
                                    val m = Regex("""\.(\d{6,10})/""").find(key)
                                        ?: Regex("""trackId=(\d+)""").find(key)
                                        ?: Regex("""/(\d{6,10})/""").find(key)
                                        ?: Regex("""\.(\d{6,10})\.""").find(key)
                                    val tId = m?.groupValues?.get(1)
                                    if (tId != null) {
                                        val codec = when {
                                            key.contains("flac") -> "flac"
                                            key.contains("mp3") -> "mp3"
                                            else -> "m4a"
                                        }
                                        val meta = trackMetadata[tId]
                                        exoStreams[exoId] = ExoStreamMeta(tId, codec, meta)
                                    }
                                }
                                c.close()
                            }
                            db.close()
                        } catch (e: Throwable) {}
                    }
                }
            }

            // 3. Scan physical chunk files in music_cache folders
            val musicCacheDirs = mutableListOf<File>()
            val extFilesDir = context.getExternalFilesDirs(null)
            for (efd in extFilesDir) {
                if (efd != null && efd.exists()) {
                    val p = efd.parentFile // user_XXXX
                    if (p != null) {
                        val mc = File(p, "music_cache")
                        if (mc.exists()) musicCacheDirs.add(mc)
                    }
                }
            }

            val storage = Environment.getExternalStorageDirectory()
            val yandexFilesRoot = File(storage, "Android/data/ru.yandex.music/files")
            if (yandexFilesRoot.exists()) {
                val userDirs = yandexFilesRoot.listFiles() ?: emptyArray()
                for (ud in userDirs) {
                    if (ud.isDirectory) {
                        val mc = File(ud, "music_cache")
                        if (mc.exists() && !musicCacheDirs.contains(mc)) {
                            musicCacheDirs.add(mc)
                        }
                    }
                }
            }

            // Group chunk files by Exo ID
            val chunksByExoId = mutableMapOf<Int, MutableList<CachedChunkFile>>()
            val chunkPattern = Regex("""^(\d+)\.(\d+)\.(\d+)\.v3\.exo$""")

            for (mcDir in musicCacheDirs) {
                val subDirs = mcDir.listFiles() ?: emptyArray()
                for (sd in subDirs) {
                    if (sd.isDirectory) {
                        val chunkFiles = sd.listFiles() ?: emptyArray()
                        for (cf in chunkFiles) {
                            val match = chunkPattern.find(cf.name)
                            if (match != null) {
                                val exoId = match.groupValues[1].toIntOrNull() ?: continue
                                val offset = match.groupValues[2].toLongOrNull() ?: 0L
                                val list = chunksByExoId.getOrPut(exoId) { mutableListOf() }
                                list.add(CachedChunkFile(cf, offset, cf.length()))
                            }
                        }
                    }
                }
            }

            // 4. Assemble clean CachedTrackGroup items
            for ((exoId, chunkList) in chunksByExoId) {
                val streamInfo = exoStreams[exoId] ?: continue // Skip if not recognized as track stream
                chunkList.sortBy { it.offset }
                val totalSize = chunkList.sumOf { it.length }
                if (totalSize < 100 * 1024) continue // Skip tiny fragments < 100KB

                val meta = streamInfo.meta
                val title = meta?.title ?: "Трек #${streamInfo.trackId}"
                val artist = meta?.artist ?: "Yandex Music"
                val isPerm = meta?.isPermanent ?: false

                result.add(
                    CachedTrackGroup(
                        exoId = exoId,
                        trackId = streamInfo.trackId,
                        artist = artist,
                        title = title,
                        isPermanent = isPerm,
                        totalSizeBytes = totalSize,
                        chunks = chunkList,
                        codec = streamInfo.codec
                    )
                )
            }

        } catch (e: Throwable) {
            android.util.Log.e("YaMusicLsposed", "scanCacheTracks error: ${e.message}")
        }

        // Return sorted: offline saved first, then alphabetical by artist/title
        return result.sortedWith(
            compareByDescending<CachedTrackGroup> { it.isPermanent }
                .thenBy { it.artist.lowercase() }
                .thenBy { it.title.lowercase() }
        )
    }

    fun fetchKeyAndCodecFromApi(trackId: String, token: String, preferredCodec: String = "aac-mp4"): Pair<String, String>? {
        return try {
            val timestamp = (System.currentTimeMillis() / 1000).toInt()
            val transport = "encraw"
            val quality = if (preferredCodec.contains("flac")) "lossless" else "hq"
            val codecs = if (preferredCodec.contains("flac")) {
                listOf("flac-mp4", "flac")
            } else {
                listOf("aac-mp4", "aac", "mp3")
            }
            val codecsStr = codecs.joinToString("")
            val message = "$timestamp$trackId$quality$codecsStr$transport"

            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(DESKTOP_SIGN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val signBytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            val rawSign = android.util.Base64.encodeToString(signBytes, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING).trimEnd('=')

            val query = "ts=$timestamp&trackId=$trackId&quality=$quality&codecs=${codecs.joinToString(",")}&transports=$transport&sign=${URLEncoder.encode(rawSign, "UTF-8")}"
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
            android.util.Log.e("YaMusicLsposed", "fetchKey error: ${e.message}")
            null
        }
    }

    fun decryptAndExportTrackGroup(
        context: Context,
        group: CachedTrackGroup,
        token: String,
        targetDir: File,
        onProgress: (Float) -> Unit
    ): DecryptResult {
        if (group.chunks.isEmpty()) {
            return DecryptResult(false, "Нет фрагментов кэша для трека")
        }

        // 1. Fetch decryption key from API
        onProgress(5f)
        val keyInfo = fetchKeyAndCodecFromApi(group.trackId, token, group.codec)
        if (keyInfo == null) {
            return DecryptResult(false, "Не удалось получить ключ дешифрования для трека ${group.trackId}")
        }
        val (keyHex, apiCodec) = keyInfo

        // 2. Fetch track info for cover art
        onProgress(15f)
        var coverUrl: String? = null
        try {
            val trackUrl = URL("https://api.music.yandex.net/tracks/${group.trackId}")
            val conn = trackUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.setRequestProperty("Authorization", "OAuth $token")
            if (conn.responseCode in 200..299) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val arr = json.optJSONArray("result")
                if (arr != null && arr.length() > 0) {
                    val tr = arr.getJSONObject(0)
                    val albums = tr.optJSONArray("albums")
                    if (albums != null && albums.length() > 0) {
                        val cUri = albums.getJSONObject(0).optString("coverUri", "")
                        if (cUri.isNotEmpty()) coverUrl = "https://" + cUri.replace("%%", "1000x1000")
                    }
                }
            }
        } catch (e: Throwable) {}

        // 3. Decrypt concatenated chunks into temporary file
        val tempDecrypted = File(context.cacheDir, "dec_${System.currentTimeMillis()}_${group.trackId}.tmp")
        try {
            val keyBytes = hexToBytes(keyHex)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            val iv = IvParameterSpec(ByteArray(16))
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), iv)

            val sortedChunks = group.chunks.sortedBy { it.offset }
            val inputStreams = sortedChunks.map { FileInputStream(it.file) }
            val sequenceInputStream = SequenceInputStream(Collections.enumeration(inputStreams))

            val totalBytes = group.totalSizeBytes
            var totalRead = 0L

            sequenceInputStream.use { seqIn ->
                CipherInputStream(seqIn, cipher).use { cipherIn ->
                    tempDecrypted.outputStream().use { out ->
                        val buffer = ByteArray(32768)
                        var bytesRead: Int
                        var lastReportTime = System.currentTimeMillis()
                        while (cipherIn.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            val now = System.currentTimeMillis()
                            if (totalBytes > 0 && (now - lastReportTime > 200 || totalRead >= totalBytes)) {
                                lastReportTime = now
                                val p = 20f + ((totalRead.toFloat() / totalBytes) * 60f)
                                onProgress(p.coerceAtMost(80f))
                            }
                        }
                    }
                }
            }

            if (!tempDecrypted.exists() || tempDecrypted.length() == 0L) {
                tempDecrypted.delete()
                return DecryptResult(false, "Ошибка дешифрования данных AES-128-CTR")
            }

            onProgress(85f)
            if (!targetDir.exists()) targetDir.mkdirs()

            // 4. Determine format and tag
            val cleanTitle = sanitizeFileName(group.title).ifEmpty { "Track ${group.trackId}" }
            val cleanArtist = sanitizeFileName(group.artist).ifEmpty { "Unknown Artist" }

            val effectiveCodec = if (apiCodec.contains("flac") || group.codec.contains("flac")) "flac" else "m4a"
            val extension = when (effectiveCodec) {
                "flac" -> "flac"
                else -> "m4a"
            }

            val outFile = File(targetDir, "$cleanArtist - $cleanTitle.$extension")

            if (effectiveCodec == "flac") {
                val rawBytes = tempDecrypted.readBytes()
                val pureFlac = extractFlacFromMp4(rawBytes)
                if (pureFlac != null) {
                    outFile.writeBytes(pureFlac)
                } else {
                    tempDecrypted.copyTo(outFile, overwrite = true)
                }
            } else {
                tempDecrypted.copyTo(outFile, overwrite = true)
            }

            // 5. MediaScanner notification
            try {
                MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), null, null)
                val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply { data = Uri.fromFile(outFile) }
                context.sendBroadcast(intent)
            } catch (e: Throwable) {}

            onProgress(100f)
            return if (outFile.exists() && outFile.length() > 0) {
                DecryptResult(true, "Успешно: ${outFile.name}", outFile)
            } else {
                DecryptResult(false, "Не удалось записать файл")
            }
        } catch (e: Throwable) {
            android.util.Log.e("YaMusicLsposed", "Decryption error: ${e.message}")
            return DecryptResult(false, "Ошибка: ${e.message}")
        } finally {
            tempDecrypted.delete()
        }
    }

    fun extractFlacFromMp4(bytes: ByteArray): ByteArray? {
        try {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            var dflaBytes: ByteArray? = null
            var mdatBytes: ByteArray? = null

            while (bb.remaining() >= 8) {
                val boxStart = bb.position()
                val boxSize = bb.getInt().toLong() and 0xFFFFFFFFL
                val boxType = ByteArray(4)
                bb.get(boxType)
                val typeStr = String(boxType, Charsets.US_ASCII)

                val payloadSize = if (boxSize == 1L) {
                    if (bb.remaining() < 8) break
                    bb.getLong() - 16
                } else if (boxSize == 0L) {
                    bb.remaining().toLong()
                } else {
                    boxSize - 8
                }

                if (typeStr == "moov") {
                    val moovEnd = boxStart + (if (boxSize == 1L) payloadSize + 16 else boxSize)
                    while (bb.position() < moovEnd && bb.remaining() >= 8) {
                        val subStart = bb.position()
                        val subSize = bb.getInt().toLong() and 0xFFFFFFFFL
                        val subType = ByteArray(4)
                        bb.get(subType)
                        val subTypeStr = String(subType, Charsets.US_ASCII)

                        if (subTypeStr == "dfLa") {
                            bb.position(subStart + 12)
                            val dflaLen = (subSize - 12).toInt()
                            if (dflaLen > 0 && dflaLen <= bb.remaining()) {
                                dflaBytes = ByteArray(dflaLen)
                                bb.get(dflaBytes)
                            }
                            break
                        } else if (subTypeStr in listOf("trak", "mdia", "minf", "stbl", "stsd", "fLaC")) {
                            continue
                        } else {
                            val nextPos = subStart + subSize
                            if (nextPos > moovEnd || subSize <= 0) break
                            bb.position(nextPos.toInt())
                        }
                    }
                    bb.position(moovEnd.toInt().coerceAtMost(bytes.size))
                } else if (typeStr == "mdat") {
                    val mdatLen = payloadSize.toInt()
                    if (mdatLen in 1..bb.remaining()) {
                        mdatBytes = ByteArray(mdatLen)
                        bb.get(mdatBytes)
                    }
                    val nextPos = boxStart + (if (boxSize == 1L) payloadSize + 16 else boxSize)
                    bb.position(nextPos.toInt().coerceAtMost(bytes.size))
                } else {
                    val nextPos = boxStart + (if (boxSize == 1L) payloadSize + 16 else boxSize)
                    if (nextPos <= boxStart || nextPos > bytes.size) break
                    bb.position(nextPos.toInt())
                }
            }

            if (dflaBytes != null && mdatBytes != null) {
                val fLaCHeader = "fLaC".toByteArray(Charsets.US_ASCII)
                val out = ByteArray(fLaCHeader.size + dflaBytes.size + mdatBytes.size)
                System.arraycopy(fLaCHeader, 0, out, 0, fLaCHeader.size)
                System.arraycopy(dflaBytes, 0, out, fLaCHeader.size, dflaBytes.size)
                System.arraycopy(mdatBytes, 0, out, fLaCHeader.size + dflaBytes.size, mdatBytes.size)
                return out
            }
        } catch (e: Throwable) {
            android.util.Log.e("YaMusicLsposed", "extractFlacFromMp4 error: ${e.message}")
        }
        return null
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.trim()
        val result = ByteArray(cleanHex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = cleanHex.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }
}
