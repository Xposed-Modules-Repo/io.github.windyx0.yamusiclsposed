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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
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
    val codec: String,
    val lastModified: Long = 0L
) {
    val displayName: String
        get() = "$artist - $title"

    val formattedSize: String
        get() = when {
            totalSizeBytes >= 1024 * 1024 -> "%.1f МБ".format(totalSizeBytes.toFloat() / (1024 * 1024))
            totalSizeBytes >= 1024 -> "%.0f КБ".format(totalSizeBytes.toFloat() / 1024)
            else -> "$totalSizeBytes Б"
        }

    val formattedDate: String
        get() {
            return if (lastModified > 0) {
                java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(java.util.Date(lastModified))
            } else ""
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
                    val p = efd.parentFile
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
                val streamInfo = exoStreams[exoId] ?: continue
                chunkList.sortBy { it.offset }
                val totalSize = chunkList.sumOf { it.length }
                if (totalSize < 100 * 1024) continue

                val meta = streamInfo.meta
                val title = meta?.title ?: "Трек #${streamInfo.trackId}"
                val artist = meta?.artist ?: "Yandex Music"
                val isPerm = meta?.isPermanent ?: false
                val trackLastModified = chunkList.maxOfOrNull { it.file.lastModified() } ?: 0L

                result.add(
                    CachedTrackGroup(
                        exoId = exoId,
                        trackId = streamInfo.trackId,
                        artist = artist,
                        title = title,
                        isPermanent = isPerm,
                        totalSizeBytes = totalSize,
                        chunks = chunkList,
                        codec = streamInfo.codec,
                        lastModified = trackLastModified
                    )
                )
            }

        } catch (e: Throwable) {
            android.util.Log.e("YaMusicLsposed", "scanCacheTracks error: ${e.message}")
        }

        return result.sortedWith(
            compareByDescending<CachedTrackGroup> { it.lastModified }
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

        // 2. Fetch track info and album cover (HD 1000x1000)
        onProgress(15f)
        var coverBytes: ByteArray? = null
        try {
            val trackUrl = URL("https://api.music.yandex.net/tracks/${group.trackId}")
            val conn = trackUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Authorization", "OAuth $token")
            if (conn.responseCode in 200..299) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val arr = json.optJSONArray("result")
                if (arr != null && arr.length() > 0) {
                    val tr = arr.getJSONObject(0)
                    val albums = tr.optJSONArray("albums")
                    if (albums != null && albums.length() > 0) {
                        val cUri = albums.getJSONObject(0).optString("coverUri", "")
                        if (cUri.isNotEmpty()) {
                            val coverUrl = "https://" + cUri.replace("%%", "1000x1000")
                            val coverConn = URL(coverUrl).openConnection() as HttpURLConnection
                            coverConn.connectTimeout = 5000
                            coverConn.readTimeout = 5000
                            if (coverConn.responseCode in 200..299) {
                                coverBytes = coverConn.inputStream.readBytes()
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("YaMusicLsposed", "Cover fetch error: ${e.message}")
        }

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

            // 4. Determine format, embed tags and cover art
            val cleanTitle = sanitizeFileName(group.title).ifEmpty { "Track ${group.trackId}" }
            val cleanArtist = sanitizeFileName(group.artist).ifEmpty { "Unknown Artist" }

            val effectiveCodec = if (apiCodec.contains("flac") || group.codec.contains("flac")) "flac"
                                 else if (apiCodec.contains("mp3") || group.codec.contains("mp3")) "mp3"
                                 else "m4a"
            val extension = when (effectiveCodec) {
                "flac" -> "flac"
                "mp3" -> "mp3"
                else -> "m4a"
            }

            val outFile = File(targetDir, "$cleanArtist - $cleanTitle.$extension")

            if (effectiveCodec == "flac") {
                val rawBytes = tempDecrypted.readBytes()
                val pureFlac = extractFlacFromMp4(rawBytes) ?: rawBytes
                val taggedFlac = if (pureFlac.size >= 4 && pureFlac[0] == 0x66.toByte() && pureFlac[1] == 0x4C.toByte()) {
                    tagFlacFile(pureFlac, group.title, group.artist, coverBytes)
                } else {
                    tagM4aFile(rawBytes, group.title, group.artist, coverBytes)
                }
                outFile.writeBytes(taggedFlac)
            } else if (effectiveCodec == "mp3") {
                try {
                    val mp3file = Mp3File(tempDecrypted.absolutePath)
                    val id3v2Tag = if (mp3file.hasId3v2Tag()) mp3file.id3v2Tag else com.mpatric.mp3agic.ID3v23Tag()
                    id3v2Tag.artist = group.artist
                    id3v2Tag.title = group.title
                    if (coverBytes != null && coverBytes.isNotEmpty()) {
                        val isPng = coverBytes.size > 8 && coverBytes[0] == 0x89.toByte() && coverBytes[1] == 0x50.toByte()
                        id3v2Tag.setAlbumImage(coverBytes, if (isPng) "image/png" else "image/jpeg")
                    }
                    val id3v1Tag = if (mp3file.hasId3v1Tag()) mp3file.id3v1Tag else com.mpatric.mp3agic.ID3v1Tag()
                    id3v1Tag.artist = group.artist.take(30)
                    id3v1Tag.title = group.title.take(30)
                    mp3file.id3v1Tag = id3v1Tag
                    mp3file.id3v2Tag = id3v2Tag
                    val taggedMp3 = File(context.cacheDir, "tagged_${System.currentTimeMillis()}.mp3")
                    mp3file.save(taggedMp3.absolutePath)
                    taggedMp3.copyTo(outFile, overwrite = true)
                    taggedMp3.delete()
                } catch (e: Throwable) {
                    tempDecrypted.copyTo(outFile, overwrite = true)
                }
            } else {
                val rawBytes = tempDecrypted.readBytes()
                val taggedM4a = tagM4aFile(rawBytes, group.title, group.artist, coverBytes)
                outFile.writeBytes(taggedM4a)
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

    fun tagFlacFile(flacBytes: ByteArray, title: String, artist: String, coverBytes: ByteArray?): ByteArray {
        try {
            if (flacBytes.size < 4 || flacBytes[0] != 0x66.toByte() || flacBytes[1] != 0x4C.toByte() ||
                flacBytes[2] != 0x61.toByte() || flacBytes[3] != 0x43.toByte()) {
                return flacBytes
            }

            var pos = 4
            val blocks = mutableListOf<Pair<Int, ByteArray>>()

            while (pos < flacBytes.size) {
                val header = flacBytes[pos].toInt() and 0xFF
                val isLast = (header and 0x80) != 0
                val blockType = header and 0x7F
                val length = ((flacBytes[pos + 1].toInt() and 0xFF) shl 16) or
                             ((flacBytes[pos + 2].toInt() and 0xFF) shl 8) or
                             (flacBytes[pos + 3].toInt() and 0xFF)

                if (pos + 4 + length > flacBytes.size) break
                val payload = flacBytes.copyOfRange(pos + 4, pos + 4 + length)
                pos += 4 + length

                // Retain STREAMINFO (type 0)
                if (blockType == 0) {
                    blocks.add(Pair(blockType, payload))
                }
                if (isLast) break
            }

            val audioFrames = flacBytes.copyOfRange(pos, flacBytes.size)

            // Vorbis Comment block (type 4)
            val vendor = "YaMusicLsposed".toByteArray(Charsets.UTF_8)
            val comments = mutableListOf<ByteArray>()
            comments.add("TITLE=$title".toByteArray(Charsets.UTF_8))
            comments.add("ARTIST=$artist".toByteArray(Charsets.UTF_8))

            val vcStream = ByteArrayOutputStream()
            // 4 bytes LE vendor length + vendor
            vcStream.write(intToLe(vendor.size))
            vcStream.write(vendor)
            // 4 bytes LE comment count
            vcStream.write(intToLe(comments.size))
            for (c in comments) {
                vcStream.write(intToLe(c.size))
                vcStream.write(c)
            }
            blocks.add(Pair(4, vcStream.toByteArray()))

            // Picture block (type 6)
            if (coverBytes != null && coverBytes.isNotEmpty()) {
                val picStream = ByteArrayOutputStream()
                picStream.write(intToBe(3)) // 3 = front cover
                val isPng = coverBytes.size > 8 && coverBytes[0] == 0x89.toByte() && coverBytes[1] == 0x50.toByte()
                val mimeStr = if (isPng) "image/png" else "image/jpeg"
                val mime = mimeStr.toByteArray(Charsets.US_ASCII)
                picStream.write(intToBe(mime.size))
                picStream.write(mime)
                picStream.write(intToBe(0)) // description length 0
                picStream.write(intToBe(1000)) // width
                picStream.write(intToBe(1000)) // height
                picStream.write(intToBe(24)) // depth
                picStream.write(intToBe(0)) // colors
                picStream.write(intToBe(coverBytes.size))
                picStream.write(coverBytes)
                blocks.add(Pair(6, picStream.toByteArray()))
            }

            // Reassemble FLAC
            val out = ByteArrayOutputStream()
            out.write("fLaC".toByteArray(Charsets.US_ASCII))
            for (i in blocks.indices) {
                val (bType, payload) = blocks[i]
                val isLastBlock = (i == blocks.size - 1)
                val flag = (if (isLastBlock) 0x80 else 0x00) or (bType and 0x7F)
                out.write(flag)
                out.write((payload.size ushr 16) and 0xFF)
                out.write((payload.size ushr 8) and 0xFF)
                out.write(payload.size and 0xFF)
                out.write(payload)
            }
            out.write(audioFrames)
            return out.toByteArray()
        } catch (e: Throwable) {
            android.util.Log.e("YaMusicLsposed", "tagFlacFile error: ${e.message}")
            return flacBytes
        }
    }

    fun tagM4aFile(m4aBytes: ByteArray, title: String, artist: String, coverBytes: ByteArray?): ByteArray {
        try {
            val orig = ByteBuffer.wrap(m4aBytes).order(ByteOrder.BIG_ENDIAN)

            // Find moov box
            var moovPos = -1
            var moovSize = 0
            while (orig.remaining() >= 8) {
                val p = orig.position()
                val sz = orig.getInt()
                val type = ByteArray(4)
                orig.get(type)
                val typeStr = String(type, Charsets.US_ASCII)
                if (typeStr == "moov") {
                    moovPos = p
                    moovSize = sz
                    break
                } else {
                    val nextPos = p + sz
                    if (nextPos <= p || nextPos > m4aBytes.size) break
                    orig.position(nextPos)
                }
            }

            if (moovPos == -1 || moovSize <= 8) return m4aBytes

            // Find stco inside moov
            orig.position(moovPos)
            var stcoBoxPos = -1
            val moovEnd = moovPos + moovSize
            while (orig.position() < moovEnd && orig.remaining() >= 8) {
                val p = orig.position()
                val sz = orig.getInt()
                val type = ByteArray(4)
                orig.get(type)
                val typeStr = String(type, Charsets.US_ASCII)
                if (typeStr == "stco") {
                    stcoBoxPos = p
                    break
                } else if (typeStr in listOf("trak", "mdia", "minf", "stbl")) {
                    continue
                } else {
                    val nextPos = p + sz
                    if (nextPos <= p || nextPos > moovEnd) break
                    orig.position(nextPos)
                }
            }

            // Construct ilst items
            val ilstPayload = ByteArrayOutputStream()
            ilstPayload.write(makeMetaItem("\u00a9nam", 1, title.toByteArray(Charsets.UTF_8)))
            ilstPayload.write(makeMetaItem("\u00a9ART", 1, artist.toByteArray(Charsets.UTF_8)))
            if (coverBytes != null && coverBytes.isNotEmpty()) {
                ilstPayload.write(makeMetaItem("covr", 13, coverBytes)) // 13 = JPEG
            }
            val ilstBox = makeBox("ilst", ilstPayload.toByteArray())

            // hdlr box: version 0 (1), flags 0 (3), pre_defined 0 (4), handler_type 'mdir' (4), manufacturer 'appl' (4), reserved (8), name "" (1)
            val hdlrPayload = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN)
                .put(0)
                .put(ByteArray(3))
                .put(ByteArray(4))
                .put("mdir".toByteArray(Charsets.US_ASCII))
                .put("appl".toByteArray(Charsets.US_ASCII))
                .put(ByteArray(8))
                .put(0)
                .array()
            val hdlrBox = makeBox("hdlr", hdlrPayload)

            val metaPayload = ByteArrayOutputStream()
            metaPayload.write(ByteArray(4)) // version 0, flags 0
            metaPayload.write(hdlrBox)
            metaPayload.write(ilstBox)
            val metaBox = makeBox("meta", metaPayload.toByteArray())

            val udtaBox = makeBox("udta", metaBox)
            val addedLen = udtaBox.size

            val result = ByteArray(m4aBytes.size + addedLen)

            // Shift stco chunk offsets
            val modifiedM4a = m4aBytes.clone()
            if (stcoBoxPos != -1 && stcoBoxPos + 16 <= modifiedM4a.size) {
                val bb = ByteBuffer.wrap(modifiedM4a).order(ByteOrder.BIG_ENDIAN)
                bb.position(stcoBoxPos + 12)
                val entryCount = bb.getInt()
                for (i in 0 until entryCount) {
                    if (bb.remaining() < 4) break
                    val pos = bb.position()
                    val oldOffset = bb.getInt(pos)
                    bb.putInt(pos, oldOffset + addedLen)
                    bb.position(pos + 4)
                }
            }

            // Copy up to moovPos
            System.arraycopy(modifiedM4a, 0, result, 0, moovPos)

            // Write new moov size
            val newMoovSize = moovSize + addedLen
            result[moovPos] = ((newMoovSize ushr 24) and 0xFF).toByte()
            result[moovPos + 1] = ((newMoovSize ushr 16) and 0xFF).toByte()
            result[moovPos + 2] = ((newMoovSize ushr 8) and 0xFF).toByte()
            result[moovPos + 3] = (newMoovSize and 0xFF).toByte()

            // Copy original moov content
            System.arraycopy(modifiedM4a, moovPos + 4, result, moovPos + 4, moovSize - 4)

            // Insert udtaBox at the end of moov
            val insertPos = moovPos + moovSize
            System.arraycopy(udtaBox, 0, result, insertPos, addedLen)

            // Copy remainder (mdat, etc.)
            val remainderLen = modifiedM4a.size - (moovPos + moovSize)
            if (remainderLen > 0) {
                System.arraycopy(modifiedM4a, moovPos + moovSize, result, insertPos + addedLen, remainderLen)
            }

            return result
        } catch (e: Throwable) {
            android.util.Log.e("YaMusicLsposed", "tagM4aFile error: ${e.message}")
            return m4aBytes
        }
    }

    private fun makeBox(type: String, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        val out = ByteArray(size)
        out[0] = ((size ushr 24) and 0xFF).toByte()
        out[1] = ((size ushr 16) and 0xFF).toByte()
        out[2] = ((size ushr 8) and 0xFF).toByte()
        out[3] = (size and 0xFF).toByte()
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        System.arraycopy(typeBytes, 0, out, 4, 4)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    private fun makeMetaItem(type: String, dataType: Int, payload: ByteArray): ByteArray {
        val dataHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .put(0) // version
            .put(((dataType ushr 16) and 0xFF).toByte())
            .put(((dataType ushr 8) and 0xFF).toByte())
            .put((dataType and 0xFF).toByte())
            .putInt(0) // reserved
            .array()
        val dataBox = makeBox("data", dataHeader + payload)
        return makeBox(type, dataBox)
    }

    private fun intToLe(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte()
        )
    }

    private fun intToBe(value: Int): ByteArray {
        return byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    private fun findSequence(data: ByteArray, seq: ByteArray, startIndex: Int = 0): Int {
        if (seq.isEmpty() || data.size - startIndex < seq.size) return -1
        val max = data.size - seq.size
        for (i in startIndex..max) {
            var match = true
            for (j in seq.indices) {
                if (data[i + j] != seq[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun getIntAt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
               ((data[offset + 1].toInt() and 0xFF) shl 16) or
               ((data[offset + 2].toInt() and 0xFF) shl 8) or
               (data[offset + 3].toInt() and 0xFF)
    }

    private fun getLongAt(data: ByteArray, offset: Int): Long {
        var res = 0L
        for (i in 0 until 8) {
            res = (res shl 8) or (data[offset + i].toLong() and 0xFFL)
        }
        return res
    }

    fun extractFlacFromMp4(bytes: ByteArray): ByteArray? {
        try {
            if (bytes.size >= 4 && bytes[0] == 0x66.toByte() && bytes[1] == 0x4C.toByte() &&
                bytes[2] == 0x61.toByte() && bytes[3] == 0x43.toByte()) {
                return bytes
            }

            // 1. Locate dfLa box containing STREAMINFO
            val dflaIdx = findSequence(bytes, "dfLa".toByteArray(Charsets.US_ASCII))
            if (dflaIdx < 4) return null
            val dflaSize = getIntAt(bytes, dflaIdx - 4)
            if (dflaSize <= 8 || dflaIdx + dflaSize - 4 > bytes.size) return null
            val dflaPayloadStart = dflaIdx + 8
            val dflaPayloadEnd = dflaIdx - 4 + dflaSize
            if (dflaPayloadStart >= dflaPayloadEnd) return null

            var mPos = dflaPayloadStart
            var streamInfoPayload: ByteArray? = null
            while (mPos + 4 <= dflaPayloadEnd) {
                val bHdr = bytes[mPos].toInt() and 0xFF
                val isLast = (bHdr and 0x80) != 0
                val bType = bHdr and 0x7F
                val bLen = ((bytes[mPos + 1].toInt() and 0xFF) shl 16) or
                           ((bytes[mPos + 2].toInt() and 0xFF) shl 8) or
                           (bytes[mPos + 3].toInt() and 0xFF)
                if (mPos + 4 + bLen > dflaPayloadEnd) break
                if (bType == 0 && bLen >= 34) {
                    streamInfoPayload = bytes.copyOfRange(mPos + 4, mPos + 4 + bLen)
                    break
                }
                mPos += 4 + bLen
                if (isLast) break
            }
            if (streamInfoPayload == null) return null

            // 2. Locate stsz (sample sizes)
            val stszIdx = findSequence(bytes, "stsz".toByteArray(Charsets.US_ASCII))
            if (stszIdx < 0 || stszIdx + 16 > bytes.size) return null
            val defaultSampleSize = getIntAt(bytes, stszIdx + 8)
            val sampleCount = getIntAt(bytes, stszIdx + 12)
            if (sampleCount <= 0) return null

            val sampleSizes = IntArray(sampleCount)
            if (defaultSampleSize > 0) {
                sampleSizes.fill(defaultSampleSize)
            } else {
                var sPos = stszIdx + 16
                for (i in 0 until sampleCount) {
                    if (sPos + 4 > bytes.size) return null
                    sampleSizes[i] = getIntAt(bytes, sPos)
                    sPos += 4
                }
            }

            // 3. Locate stsc (sample to chunk)
            val stscIdx = findSequence(bytes, "stsc".toByteArray(Charsets.US_ASCII))
            if (stscIdx < 0 || stscIdx + 12 > bytes.size) return null
            val stscCount = getIntAt(bytes, stscIdx + 8)
            data class StscEntry(val firstChunk: Int, val samplesPerChunk: Int)
            val stscEntries = mutableListOf<StscEntry>()
            var scPos = stscIdx + 12
            for (i in 0 until stscCount) {
                if (scPos + 12 > bytes.size) break
                val fChunk = getIntAt(bytes, scPos)
                val spc = getIntAt(bytes, scPos + 4)
                stscEntries.add(StscEntry(fChunk, spc))
                scPos += 12
            }
            if (stscEntries.isEmpty()) return null

            // 4. Locate stco or co64 (chunk offsets)
            val stcoIdx = findSequence(bytes, "stco".toByteArray(Charsets.US_ASCII))
            val isCo64 = (stcoIdx < 0)
            val chunkOffsets = mutableListOf<Long>()
            if (!isCo64) {
                val chunkCount = getIntAt(bytes, stcoIdx + 8)
                var cPos = stcoIdx + 12
                for (i in 0 until chunkCount) {
                    if (cPos + 4 > bytes.size) break
                    chunkOffsets.add(getIntAt(bytes, cPos).toLong() and 0xFFFFFFFFL)
                    cPos += 4
                }
            } else {
                val co64Idx = findSequence(bytes, "co64".toByteArray(Charsets.US_ASCII))
                if (co64Idx < 0 || co64Idx + 12 > bytes.size) return null
                val chunkCount = getIntAt(bytes, co64Idx + 8)
                var cPos = co64Idx + 12
                for (i in 0 until chunkCount) {
                    if (cPos + 8 > bytes.size) break
                    chunkOffsets.add(getLongAt(bytes, cPos))
                    cPos += 8
                }
            }
            if (chunkOffsets.isEmpty()) return null

            // 5. Map sample offsets and copy audio frames
            var totalAudioBytes = 0
            for (sz in sampleSizes) totalAudioBytes += sz
            val audioBytes = ByteArray(totalAudioBytes)
            var audioDestPos = 0
            var sIdx = 0

            for (cIdx in chunkOffsets.indices) {
                val chunkNum = cIdx + 1
                var spc = stscEntries[0].samplesPerChunk
                for (entry in stscEntries) {
                    if (chunkNum >= entry.firstChunk) {
                        spc = entry.samplesPerChunk
                    }
                }
                var currentOffset = chunkOffsets[cIdx]
                for (j in 0 until spc) {
                    if (sIdx >= sampleCount) break
                    val sSize = sampleSizes[sIdx]
                    if (currentOffset + sSize <= bytes.size.toLong()) {
                        System.arraycopy(bytes, currentOffset.toInt(), audioBytes, audioDestPos, sSize)
                        audioDestPos += sSize
                    }
                    currentOffset += sSize
                    sIdx++
                }
            }

            // 6. Build pure FLAC stream: "fLaC" + STREAMINFO block + audio frames
            val outStream = ByteArrayOutputStream(4 + 4 + streamInfoPayload.size + audioDestPos)
            outStream.write("fLaC".toByteArray(Charsets.US_ASCII))
            outStream.write(0x80) // 0x80 = isLast, type = 0 (STREAMINFO)
            outStream.write((streamInfoPayload.size ushr 16) and 0xFF)
            outStream.write((streamInfoPayload.size ushr 8) and 0xFF)
            outStream.write(streamInfoPayload.size and 0xFF)
            outStream.write(streamInfoPayload)
            outStream.write(audioBytes, 0, audioDestPos)
            return outStream.toByteArray()
        } catch (e: Throwable) {
            android.util.Log.e("YaMusicLsposed", "extractFlacFromMp4 error: ${e.message}")
            return null
        }
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
