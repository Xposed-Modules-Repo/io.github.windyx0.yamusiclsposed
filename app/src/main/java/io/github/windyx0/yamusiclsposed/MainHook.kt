package io.github.windyx0.yamusiclsposed

import android.app.Activity
import android.app.AlertDialog
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
import java.util.regex.Pattern
import kotlin.concurrent.thread

data class Config(
    val quality: Int,
    val cover: Boolean,
    val coverSize: String,
    val folderType: String,
    val customFolder: String,
    val manualToken: String
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
        .apply()
}

fun loadConfig(activity: Activity): Config {
    val prefs = activity.getSharedPreferences("YaMusicDL_Prefs", Context.MODE_PRIVATE)
    return Config(
        quality = prefs.getInt("quality", 320),
        cover = prefs.getBoolean("download_cover", true),
        coverSize = prefs.getString("cover_size", "1000x1000") ?: "1000x1000",
        folderType = prefs.getString("folder_type", "Music") ?: "Music",
        customFolder = prefs.getString("custom_folder", "") ?: "",
        manualToken = prefs.getString("token", "") ?: ""
    )
}

fun getDownloadPath(config: Config): String {
    return when (config.folderType) {
        "Downloads" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/YaMusicDL"
        "Custom" -> config.customFolder.ifEmpty { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath + "/YaMusicDL" }
        else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath + "/YaMusicDL"
    }
}

class MainHook : IXposedHookLoadPackage {

    companion object {
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
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "ru.yandex.music") return

        android.util.Log.e("YaMusicLsposed", "Successfully hooked into Yandex Music!")
        hookMediaSession(lpparam)
        hookNetworkForToken(lpparam)
        hookUI(lpparam)
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

                                val uId = cachedUserId
                                val pId = cachedPlaylistId
                                val aId = cachedAlbumId
                                if ((uId == null || pId == null) && aId == null) {
                                    Toast.makeText(activity, "Сначала откройте страницу плейлиста или альбома!", Toast.LENGTH_SHORT).show()
                                    return@setOnClickListener
                                }
                                
                                val token = findToken(activity)
                                if (token == null) {
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
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val key = param.args[0] as? String
                    val value = param.args[1] as? String
                    
                    if (key != null && key.equals("Authorization", ignoreCase = true) && value != null && value.startsWith("OAuth ")) {
                        cachedToken = value.removePrefix("OAuth ").trim()
                    }
                    
                    var urlStr = ""
                    if (param.thisObject is java.net.HttpURLConnection) {
                        urlStr = (param.thisObject as java.net.HttpURLConnection).url.toString()
                    } else {
                        try {
                            val urlObj = XposedHelpers.getObjectField(param.thisObject, "url")
                            if (urlObj != null) {
                                urlStr = urlObj.toString()
                            }
                        } catch (e: Throwable) {}
                    }
                    
                    if (urlStr.isNotEmpty()) {
                        try {
                            val logFile = java.io.File("/sdcard/Download/YaMusicLsposed_Meta.txt")
                            logFile.appendText("OAuth Request URL: $urlStr\n")
                        } catch (e: Throwable) {}
                        
                        val mPlaylist = java.util.regex.Pattern.compile("/users/([^/]+)/playlists/([^/?]+)").matcher(urlStr)
                        if (mPlaylist.find()) {
                            val extractedUserId = mPlaylist.group(1)
                            val extractedPlaylistId = mPlaylist.group(2)
                            if (extractedPlaylistId != "favorites" && !urlStr.contains("tracks")) {
                                cachedUserId = extractedUserId
                                cachedPlaylistId = extractedPlaylistId
                                cachedAlbumId = null
                            }
                        }

                        val mAlbum = java.util.regex.Pattern.compile("/albums/([^/?]+)").matcher(urlStr)
                        if (mAlbum.find()) {
                            val extractedAlbumId = mAlbum.group(1)
                            if (!urlStr.contains("tracks")) {
                                cachedAlbumId = extractedAlbumId
                                cachedUserId = null
                                cachedPlaylistId = null
                            }
                        }
                    }
                } catch(e: Throwable) {}
            }
        }

        try {
            XposedHelpers.findAndHookMethod(HttpURLConnection::class.java, "setRequestProperty", String::class.java, String::class.java, hook)
            XposedHelpers.findAndHookMethod(HttpURLConnection::class.java, "addRequestProperty", String::class.java, String::class.java, hook)
        } catch (t: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod("okhttp3.Request\$Builder", lpparam.classLoader, "header", String::class.java, String::class.java, hook)
            XposedHelpers.findAndHookMethod("okhttp3.Request\$Builder", lpparam.classLoader, "addHeader", String::class.java, String::class.java, hook)
        } catch (t: Throwable) {}
    }

    private fun findToken(activity: Activity): String? {
        if (cachedToken != null) return cachedToken
        try {
            val prefsDir = File(activity.applicationInfo.dataDir, "shared_prefs")
            if (prefsDir.exists() && prefsDir.isDirectory) {
                for (file in prefsDir.listFiles() ?: emptyArray()) {
                    if (file.name.endsWith(".xml")) {
                        val content = file.readText()
                        val matcher = Pattern.compile("(y0_[a-zA-Z0-9_-]+|A[Qg]AAAA[a-zA-Z0-9_-]+)").matcher(content)
                        if (matcher.find()) {
                            cachedToken = matcher.group(1)
                            return cachedToken
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("YaMusicLsposed", "Token search error - ${t.message}")
        }
        return null
    }

    private fun startDownloadProcess(activity: Activity, onStateChange: (Int, Float) -> Unit) {
        val title = currentTitle ?: "Unknown Title"
        val artist = currentArtist ?: "Unknown Artist"
        val rawMediaId = currentMediaId

        if (rawMediaId == null) {
            Toast.makeText(activity, "Не найден ID! Включите трек.", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = activity.getSharedPreferences("YaMusicDL_Prefs", Context.MODE_PRIVATE)
        var token = prefs.getString("token", "")
        if (token.isNullOrEmpty()) {
            token = findToken(activity)
        }

        if (token.isNullOrEmpty()) {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString()?.trim() ?: ""
            if (clipText.startsWith("y0_") || clipText.startsWith("A") && clipText.contains("AAAA")) {
                token = clipText
                prefs.edit().putString("token", token).apply()
                Toast.makeText(activity, "Токен загружен из буфера обмена!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "Необходим токен авторизации! Удерживайте кнопку загрузки.", Toast.LENGTH_LONG).show()
                return
            }
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
                
                if (downloadInfoUrlStr == null) {
                    val infoUrl = URL("https://api.music.yandex.net/tracks/$resolvedMediaId/download-info")
                    val infoConn = infoUrl.openConnection() as HttpURLConnection
                    infoConn.setRequestProperty("Authorization", "OAuth $token")
                    val infoResponse = infoConn.inputStream.bufferedReader().use(BufferedReader::readText)
                    
                    val pattern320 = Pattern.compile("\"bitrateInKbps\":320.*?\"downloadInfoUrl\":\"(https?://.*?)\"")
                    val patternAny = Pattern.compile("\"downloadInfoUrl\":\"(https?://.*?)\"")
                    
                    if (targetQuality == 320) {
                        val matcher320 = pattern320.matcher(infoResponse)
                        if (matcher320.find()) {
                            downloadInfoUrlStr = matcher320.group(1)
                        }
                    }
                    if (downloadInfoUrlStr == null) {
                        val matcherAny = patternAny.matcher(infoResponse)
                        if (matcherAny.find()) {
                            downloadInfoUrlStr = matcherAny.group(1)
                        }
                    }
                }



                if (downloadInfoUrlStr == null) {
                    onStateChange(0, 0f)
                    return@thread
                }

                val dlInfoConn = URL(downloadInfoUrlStr).openConnection() as HttpURLConnection
                dlInfoConn.setRequestProperty("Authorization", "OAuth $token")
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
                    
                    val signStr = "XGRlBW9FXlekgbPrRHuALE$path${s}"
                    val md = MessageDigest.getInstance("MD5")
                    val digest = md.digest(signStr.toByteArray())
                    val sign = digest.joinToString("") { "%02x".format(it) }
                    
                    val finalDownloadUrl = "https://$host/get-mp3/$sign/$ts$path"
                    
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
                    initNotification(activity)
                    downloadAndTagFile(activity, finalDownloadUrl, finalCoverUrl, finalArtist, finalTitle, downloadFolder) { progress ->
                        onStateChange(1, progress)
                        updateNotification("Скачивание трека", "$finalArtist - $finalTitle", 100, progress.toInt(), false)
                    }
                    
                    // Show Done state for 1 second
                    cancelNotification()
                    onStateChange(2, 0f)
                    Thread.sleep(1000)
                    onStateChange(0, 0f)
                } else {
                    cancelNotification()
                    android.util.Log.e("YaMusicLsposed", "XML parse error")
                }
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
        onProgress: (Float) -> Unit
    ) {
        val fileName = "${artist.replace("/", "_")} - ${title.replace("/", "_")}.mp3"
        val outDir = File(downloadFolder)
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, fileName)
        val tempFile = File(activity.cacheDir, fileName)
        
        try {
            val mp3Conn = URL(mp3Url).openConnection() as HttpURLConnection
            val contentLength = mp3Conn.contentLength
            mp3Conn.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastReportTime = System.currentTimeMillis()
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (cancelCheck?.invoke() == true) {
                            throw RuntimeException("Cancelled")
                        }
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        val now = System.currentTimeMillis()
                        if (contentLength > 0 && (now - lastReportTime > 200 || totalRead.toLong() == contentLength.toLong())) {
                            lastReportTime = now
                            val p = (totalRead.toFloat() / contentLength) * 100f
                            onProgress(p)
                        }
                    }
                }
            }
            
            val mp3file = Mp3File(tempFile.absolutePath)
            val id3v2Tag = if (mp3file.hasId3v2Tag()) mp3file.id3v2Tag else ID3v24Tag()
            id3v2Tag.artist = artist
            id3v2Tag.title = title
            
            if (coverUrl != null) {
                try {
                    val coverConn = URL(coverUrl).openConnection() as HttpURLConnection
                    val coverBytes = coverConn.inputStream.readBytes()
                    id3v2Tag.setAlbumImage(coverBytes, "image/jpeg")
                } catch (e: Throwable) {
                    android.util.Log.e("YaMusicLsposed", "Failed to download cover - ${e.message}")
                }
            }
            
            mp3file.id3v2Tag = id3v2Tag
            val taggedTempFile = File(activity.cacheDir, "tagged_$fileName")
            mp3file.save(taggedTempFile.absolutePath)
            
            taggedTempFile.copyTo(outFile, overwrite = true)
            tempFile.delete()
            taggedTempFile.delete()
            
            val baseTime = System.currentTimeMillis()
            val timeOffset = (totalTracks - trackIndex) * 1000L
            outFile.setLastModified(baseTime + timeOffset)
        } catch (e: Throwable) {
            android.util.Log.e("YaMusicLsposed", "Download error - ${e.stackTraceToString()}")
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

                var downloadInfoUrlStr: String? = null
                val infoUrl = java.net.URL("https://api.music.yandex.net/tracks/$trackId/download-info")
                val infoConn = infoUrl.openConnection() as java.net.HttpURLConnection
                infoConn.connectTimeout = 15000
                infoConn.readTimeout = 15000
                infoConn.setRequestProperty("Authorization", "OAuth $token")
                val infoResponse = infoConn.inputStream.bufferedReader().use(java.io.BufferedReader::readText)
                
                val pattern320 = java.util.regex.Pattern.compile("\"bitrateInKbps\":320.*?\"downloadInfoUrl\":\"(https?://.*?)\"")
                val patternAny = java.util.regex.Pattern.compile("\"downloadInfoUrl\":\"(https?://.*?)\"")
                
                if (targetQuality == 320) {
                    val matcher320 = pattern320.matcher(infoResponse)
                    if (matcher320.find()) {
                        downloadInfoUrlStr = matcher320.group(1)
                    }
                }
                if (downloadInfoUrlStr == null) {
                    val matcherAny = patternAny.matcher(infoResponse)
                    if (matcherAny.find()) {
                        downloadInfoUrlStr = matcherAny.group(1)
                    }
                }
                
                if (downloadInfoUrlStr == null) {
                    android.util.Log.e("YaMusicLsposed", "Track $trackId ($title) unavailable, skipping")
                    onError?.invoke() ?: onStateChange(0, 0f)
                    return@thread
                }

                val dlInfoConn = java.net.URL(downloadInfoUrlStr).openConnection() as java.net.HttpURLConnection
                dlInfoConn.connectTimeout = 15000
                dlInfoConn.readTimeout = 15000
                dlInfoConn.setRequestProperty("Authorization", "OAuth $token")
                val dlInfoResponse = dlInfoConn.inputStream.bufferedReader().use(java.io.BufferedReader::readText)
                
                val hostMatcher = java.util.regex.Pattern.compile("<host>(.*?)</host>").matcher(dlInfoResponse)
                val pathMatcher = java.util.regex.Pattern.compile("<path>(.*?)</path>").matcher(dlInfoResponse)
                val tsMatcher = java.util.regex.Pattern.compile("<ts>(.*?)</ts>").matcher(dlInfoResponse)
                val sMatcher = java.util.regex.Pattern.compile("<s>(.*?)</s>").matcher(dlInfoResponse)
                
                if (hostMatcher.find() && pathMatcher.find() && tsMatcher.find() && sMatcher.find()) {
                    val host = hostMatcher.group(1)
                    val path = pathMatcher.group(1)
                    val ts = tsMatcher.group(1)
                    val s = sMatcher.group(1)
                    
                    val signStr = "XGRlBW9FXlekgbPrRHuALE$path${s}"
                    val md = java.security.MessageDigest.getInstance("MD5")
                    val digest = md.digest(signStr.toByteArray())
                    val sign = digest.joinToString("") { "%02x".format(it) }
                    
                    val finalDownloadUrl = "https://$host/get-mp3/$sign/$ts$path"
                    
                    downloadAndTagFile(activity, finalDownloadUrl, coverUrl, artist, title, downloadFolder, trackIndex, totalTracks, cancelCheck) { progress ->
                        onStateChange(1, progress)
                    }
                    onStateChange(2, 100f)
                    onSuccess()
                } else {
                    android.util.Log.e("YaMusicLsposed", "XML parse error for track $trackId")
                    onError?.invoke() ?: onStateChange(0, 0f)
                }
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
                            onStateChange(2, 100f, "Готово!")
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
                                continue
                            }
                            
                            activeCount++
                            val activeDownload = ActiveDownload(tId, tTitle, tArtist)
                            DownloadState.activeDownloads.add(activeDownload)
                            
                            val advanceNext = {
                                DownloadState.activeDownloads.remove(activeDownload)
                                synchronized(lock) {
                                    activeCount--
                                    completedCount++
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
                                advanceNext,
                                advanceNext
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
