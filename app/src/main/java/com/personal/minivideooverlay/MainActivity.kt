package com.personal.minivideooverlay

import android.app.Activity
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.content.ContentUris
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Matrix
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.View
import android.view.TextureView
import android.view.Surface
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ScrollView

class MainActivity : Activity() {
    private var videoUri: Uri? = null
    private var pngUri: Uri? = null
    private val musicUris = mutableListOf<Uri>()
    private var exportReceiverRegistered = false
    private val exportReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ExportService.ACTION_PROGRESS -> {
                    progress.visibility = View.VISIBLE
                    progress.progress = intent.getIntExtra(ExportService.EXTRA_PROGRESS, 0)
                    statusText.text = intent.getStringExtra(ExportService.EXTRA_MESSAGE) ?: "Rendering…"
                }
                ExportService.ACTION_FINISHED -> {
                    lastOutputUri = intent.getStringExtra(ExportService.EXTRA_OUTPUT)?.let(Uri::parse)
                    progress.progress = 100; progress.visibility = View.VISIBLE
                    statusText.text = "Selesai: ${intent.getStringExtra(ExportService.EXTRA_FILENAME) ?: "Video tersimpan"}\nLokasi: Movies/MiniVideoOverlay"
                    playOutputButton.visibility = View.VISIBLE; shareOutputButton.visibility = View.VISIBLE
                    refreshUi()
                }
                ExportService.ACTION_FAILED -> {
                    statusText.text = intent.getStringExtra(ExportService.EXTRA_MESSAGE) ?: "Ekspor gagal."
                    refreshUi()
                }
                ExportService.ACTION_STATE_CHANGED -> refreshUi()
            }
        }
    }
    private var lastOutputUri: Uri? = null
    private val main = Handler(Looper.getMainLooper())

    private lateinit var videoLabel: TextView
    private lateinit var pngLabel: TextView
    private lateinit var musicLabel: TextView
    private lateinit var videoVolumeLabel: TextView
    private lateinit var musicVolumeLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var chooseVideoButton: Button
    private lateinit var choosePngButton: Button
    private lateinit var chooseMusicButton: Button
    private lateinit var exportButton: Button
    private lateinit var cancelButton: Button
    private lateinit var previewButton: Button
    private lateinit var previewTexture: TextureView
    private var previewPlayer: MediaPlayer? = null
    private var previewSurface: Surface? = null
    private var previewRequested = false
    private lateinit var previewOverlay: ImageView
    private lateinit var previewFrame: FrameLayout
    private lateinit var videoSeek: SeekBar
    private lateinit var musicSeek: SeekBar
    private lateinit var playOutputButton: Button
    private lateinit var shareOutputButton: Button
    private var videoVolume = 100
    private var musicVolume = 100
    private var videoDurationMs = 0L
    private var trimStartMs = 0L
    private var trimEndMs = 0L
    private lateinit var trimStartSeek: SeekBar
    private lateinit var trimEndSeek: SeekBar
    private lateinit var trimStartLabel: TextView
    private lateinit var trimEndLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            videoUri = it.getString(KEY_VIDEO)?.let(Uri::parse)
            pngUri = it.getString(KEY_PNG)?.let(Uri::parse)
            musicUris.clear(); musicUris.addAll(it.getStringArrayList(KEY_MUSIC_LIST)?.map(Uri::parse) ?: emptyList())
            videoVolume = it.getInt(KEY_VIDEO_VOL, 100)
            musicVolume = it.getInt(KEY_MUSIC_VOL, 100)
            trimStartMs = it.getLong(KEY_TRIM_START, 0L)
            trimEndMs = it.getLong(KEY_TRIM_END, 0L)
        }
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2402)
        }
        cleanupStalePendingOutputs()
        ExportServiceState.outputUri = ExportServiceState.outputUri ?: getSharedPreferences("mvo_export_state", MODE_PRIVATE).getString("last_output_uri", null)
        ExportServiceState.outputUri?.let { lastOutputUri = Uri.parse(it) }
        refreshUi()
        updateTrimControls()
        if (ExportServiceState.outputUri != null) { playOutputButton.visibility = View.VISIBLE; shareOutputButton.visibility = View.VISIBLE }
        else if (!ExportServiceState.isRunning) getSharedPreferences("mvo_export_state", MODE_PRIVATE).getString("last_error", null)?.let { statusText.text = it }
        if (ExportServiceState.isRunning) { progress.visibility = View.VISIBLE; progress.progress = ExportServiceState.progress; statusText.text = ExportServiceState.message }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply { addAction(ExportService.ACTION_PROGRESS); addAction(ExportService.ACTION_FINISHED); addAction(ExportService.ACTION_FAILED); addAction(ExportService.ACTION_STATE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(exportReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(exportReceiver, filter)
        }
        exportReceiverRegistered = true
    }

    override fun onStop() {
        if (exportReceiverRegistered) { unregisterReceiver(exportReceiver); exportReceiverRegistered = false }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        videoUri?.let { outState.putString(KEY_VIDEO, it.toString()) }
        pngUri?.let { outState.putString(KEY_PNG, it.toString()) }
        outState.putStringArrayList(KEY_MUSIC_LIST, ArrayList(musicUris.map { it.toString() }))
        outState.putInt(KEY_VIDEO_VOL, videoVolume); outState.putInt(KEY_MUSIC_VOL, musicVolume)
        outState.putLong(KEY_TRIM_START, trimStartMs); outState.putLong(KEY_TRIM_END, trimEndMs)
    }

    override fun onDestroy() {
        releasePreview()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data
        val flags = (data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (requestCode == REQ_MUSIC) {
            val selected = mutableListOf<Uri>()
            data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) selected.add(clip.getItemAt(i).uri) }
            if (selected.isEmpty() && uri != null) selected.add(uri)
            selected.forEach { chosen ->
                runCatching { contentResolver.takePersistableUriPermission(chosen, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                if (!musicUris.contains(chosen)) musicUris.add(chosen)
            }
            refreshUi(); return
        }
        val chosenUri = uri ?: return
        runCatching { contentResolver.takePersistableUriPermission(chosenUri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        when (requestCode) {
            REQ_VIDEO -> {
                videoUri = chosenUri; releasePreview(); previewRequested = false
                videoDurationMs = readDurationMs(chosenUri)
                trimStartMs = 0L; trimEndMs = videoDurationMs
            }
            REQ_PNG -> {
                // Simpan URI segera setelah pengguna memilih file. Validasi tidak boleh
                // membatalkan pilihan, karena beberapa document provider menghasilkan
                // metadata/decode bounds yang tidak lengkap meskipun file dapat dibaca.
                pngUri = chosenUri
                val warning = validatePng(chosenUri)
                statusText.text = warning ?: "PNG berhasil dipilih: ${displayName(chosenUri)}"
            }
            REQ_MUSIC -> { if (!musicUris.contains(uri)) musicUris.add(uri) }
        }
        refreshUi()
        updatePreviewOverlay()
    }

    private fun buildUi(): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(28)); setBackgroundColor(Color.rgb(11, 18, 32)) }
        root.addView(TextView(this).apply { text = "MINIVIDEOOVERLAY"; textSize = 22f; letterSpacing = 0.08f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD) })
        root.addView(TextView(this).apply { text = "VIDEO COMPOSER  ·  FULL HD"; textSize = 11f; letterSpacing = 0.12f; setTextColor(Color.rgb(117, 143, 170)) }, margin(dp(4)))

        previewFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewTexture = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) { if (previewRequested) preparePreview() }
                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) { applyPreviewTransform() }
                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean { releasePreview(); return true }
                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
            }
        }
        previewOverlay = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; adjustViewBounds = false }
        previewFrame.addView(previewTexture, FrameLayout.LayoutParams(-1, -1))
        previewFrame.addView(previewOverlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewFrame, LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(14) })
        previewButton = Button(this).apply { text = "Preview / Putar-Pause"; setOnClickListener { togglePreview() } }
        root.addView(previewButton, margin(dp(4)))

        root.addView(section("POTONG VIDEO", dp(20)))
        trimStartLabel = label()
        trimStartLabel.text = "Mulai: 00:00"
        root.addView(trimStartLabel, margin(dp(4)))
        trimStartSeek = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(seekListener { value ->
                if (videoDurationMs > 0L) {
                    val proposed = videoDurationMs * value / 1000L
                    trimStartMs = proposed.coerceAtMost((trimEndMs - MIN_TRIM_MS).coerceAtLeast(0L))
                    updateTrimControls()
                }
            })
        }
        root.addView(trimStartSeek)
        trimEndLabel = label()
        trimEndLabel.text = "Selesai: --:--"
        root.addView(trimEndLabel, margin(dp(8)))
        trimEndSeek = SeekBar(this).apply {
            max = 1000
            progress = 1000
            setOnSeekBarChangeListener(seekListener { value ->
                if (videoDurationMs > 0L) {
                    val proposed = videoDurationMs * value / 1000L
                    trimEndMs = proposed.coerceAtLeast((trimStartMs + MIN_TRIM_MS).coerceAtMost(videoDurationMs))
                    updateTrimControls()
                }
            })
        }
        root.addView(trimEndSeek)
        root.addView(TextView(this).apply {
            text = "Atur titik awal dan akhir. Bagian di luar rentang tidak akan masuk ke video hasil ekspor."
            textSize = 12f; setTextColor(Color.rgb(153, 166, 184))
        }, margin(dp(2)))

        root.addView(section("INPUT MEDIA", dp(16)))
        chooseVideoButton = Button(this).apply { text = "Pilih Video Utama"; setOnClickListener { pickFile(REQ_VIDEO, "video/*") } }
        root.addView(chooseVideoButton)
        videoLabel = label(); root.addView(videoLabel, margin(dp(2)))
        choosePngButton = Button(this).apply { text = "Pilih PNG Transparan"; setOnClickListener { pickFile(REQ_PNG, "image/png") } }
        root.addView(choosePngButton, margin(dp(8)))
        pngLabel = label(); root.addView(pngLabel, margin(dp(2)))
        chooseMusicButton = Button(this).apply { text = "Tambah Musik ke Playlist (Bisa Banyak)"; setOnClickListener { pickMusicFiles() } }
        root.addView(chooseMusicButton, margin(dp(8)))
        musicLabel = label(); root.addView(musicLabel, margin(dp(2)))

        root.addView(section("PENGATURAN AUDIO", dp(18)))
        videoVolumeLabel = label(); root.addView(videoVolumeLabel)
        videoSeek = SeekBar(this).apply { max = 100; progress = videoVolume; setOnSeekBarChangeListener(seekListener { videoVolume = it; updateVolumeLabels() }) }
        root.addView(videoSeek)
        musicVolumeLabel = label(); root.addView(musicVolumeLabel, margin(dp(8)))
        musicSeek = SeekBar(this).apply { max = 100; progress = musicVolume; setOnSeekBarChangeListener(seekListener { musicVolume = it; updateVolumeLabels() }) }
        root.addView(musicSeek)

        root.addView(section("EKSPOR", dp(18)))
        root.addView(TextView(this).apply { text = "Ekspor Full HD dapat menggunakan baterai dan membuat perangkat hangat."; textSize = 12f; setTextColor(Color.DKGRAY) }, margin(dp(2)))
        exportButton = Button(this).apply { text = "Ekspor Video"; setOnClickListener { startExport() } }
        root.addView(exportButton, margin(dp(10)))
        cancelButton = Button(this).apply { text = "Batalkan Ekspor"; visibility = View.GONE; setOnClickListener { startService(Intent(this@MainActivity, ExportService::class.java).setAction(ExportService.ACTION_CANCEL)); statusText.text = "Permintaan pembatalan dikirim…" } }
        root.addView(cancelButton)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress, margin(dp(8)))
        statusText = label(); root.addView(statusText, margin(dp(4)))
        playOutputButton = Button(this).apply { text = "Putar Hasil"; visibility = View.GONE; setOnClickListener { lastOutputUri?.let { openOutput(it) } } }
        shareOutputButton = Button(this).apply { text = "Bagikan Hasil"; visibility = View.GONE; setOnClickListener { lastOutputUri?.let { shareOutput(it) } } }
        root.addView(playOutputButton, margin(dp(6))); root.addView(shareOutputButton)

        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.rgb(11, 18, 32)); addView(root) }
        applyProfessionalTheme(root)
        return scroll
    }

    private fun section(text: String, top: Int) = TextView(this).apply {
        this.text = text; textSize = 11f; letterSpacing = 0.10f; setTextColor(Color.rgb(111, 190, 225)); setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, top, 0, 6)
    }
    private fun label() = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(204, 215, 228)) }

    private fun applyProfessionalTheme(view: View) {
        when (view) {
            is Button -> {
                view.isAllCaps = false
                view.textSize = 13f
                view.setTextColor(Color.WHITE)
                view.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (view === exportButton) Color.rgb(36, 126, 190) else Color.rgb(30, 43, 62))
                    cornerRadius = resources.displayMetrics.density * 12f
                }
                view.setPadding(view.paddingLeft, view.paddingTop + 3, view.paddingRight, view.paddingBottom + 3)
            }
            is SeekBar -> {
                view.progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(76, 201, 240))
                view.thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(76, 201, 240))
                view.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(48, 64, 84))
            }
            is ProgressBar -> {
                view.progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(76, 201, 240))
            }
            is TextView -> {
                if (view.textColors.defaultColor == Color.BLACK || view.textColors.defaultColor == Color.DKGRAY) {
                    view.setTextColor(Color.rgb(204, 215, 228))
                }
            }
        }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) applyProfessionalTheme(view.getChildAt(index))
        }
    }

    private fun updateTrimControls() {
        if (!::trimStartSeek.isInitialized || !::trimEndSeek.isInitialized) return
        val duration = videoDurationMs
        val hasDuration = duration > MIN_TRIM_MS
        trimStartSeek.isEnabled = hasDuration && !ExportServiceState.isRunning
        trimEndSeek.isEnabled = hasDuration && !ExportServiceState.isRunning
        trimStartSeek.progress = if (duration > 0L) (trimStartMs * 1000L / duration).toInt().coerceIn(0, 1000) else 0
        trimEndSeek.progress = if (duration > 0L) (trimEndMs * 1000L / duration).toInt().coerceIn(0, 1000) else 1000
        val selected = (trimEndMs - trimStartMs).coerceAtLeast(0L)
        trimStartLabel.text = "MULAI  ${formatTime(trimStartMs)}"
        trimEndLabel.text = "AKHIR  ${formatTime(trimEndMs)}   ·   DURASI ${formatTime(selected)}"
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
        return "%02d:%02d:%02d".format(totalSeconds / 3600L, (totalSeconds / 60L) % 60L, totalSeconds % 60L)
    }

    private fun readDurationMs(uri: Uri): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try { retriever.setDataSource(this, uri); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
        finally { retriever.release() }
    }.getOrDefault(0L)
    private fun margin(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top }
    private fun seekListener(changed: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) { changed(value) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun pickMusicFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_MUSIC)
    }

    private fun pickFile(request: Int, mime: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, request)
    }

    private fun validatePng(uri: Uri): String? {
        return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri)
            ?: return "PNG tidak dapat dibuka. Periksa akses file lalu pilih ulang PNG."
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "File bukan PNG gambar yang valid."
        val sample = maxOf(1, maxOf(bounds.outWidth / 1920, bounds.outHeight / 1080))
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return "PNG gagal didekode."
        val hasAlpha = bitmap.hasAlpha(); bitmap.recycle()
        if (!hasAlpha) return "Peringatan: PNG ini tidak memiliki transparansi alpha. Pilih PNG transparan sebelum ekspor."
        val actual = bounds.outWidth.toFloat() / bounds.outHeight
        val target = 16f / 9f
        if (kotlin.math.abs(actual - target) > 0.015f) "Peringatan: PNG bukan 16:9. Desain akan di-contain di tengah kanvas transparan; PNG 1920×1080 paling ideal." else null
        } catch (t: Throwable) { "PNG tidak valid: ${t.message}" }
    }

    private fun startExport() {
        val video = videoUri ?: return
        val png = pngUri ?: return
        if (ExportServiceState.isRunning) return
        val intent = Intent(this, ExportService::class.java).apply {
            action = ExportService.ACTION_START
            putExtra(ExportService.EXTRA_VIDEO, video.toString())
            putExtra(ExportService.EXTRA_PNG, png.toString())
            putStringArrayListExtra(ExportService.EXTRA_MUSIC_LIST, ArrayList(musicUris.map { it.toString() }))
            putExtra(ExportService.EXTRA_VIDEO_VOLUME, videoVolume)
            putExtra(ExportService.EXTRA_MUSIC_VOLUME, musicVolume)
            putExtra(ExportService.EXTRA_TRIM_START_MS, trimStartMs)
            putExtra(ExportService.EXTRA_TRIM_END_MS, trimEndMs.takeIf { it > 0L } ?: videoDurationMs)
        }
        lastOutputUri = null
        playOutputButton.visibility = View.GONE; shareOutputButton.visibility = View.GONE
        progress.progress = 0; progress.visibility = View.VISIBLE
        statusText.text = "Ekspor berjalan di latar belakang. Kamu boleh berpindah aplikasi; pantau notifikasi MiniVideoOverlay."
        ExportServiceState.isRunning = true
        refreshUi()
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (error: Throwable) {
            ExportServiceState.isRunning = false
            statusText.text = "Tidak dapat memulai layanan ekspor: ${error.message ?: error.javaClass.simpleName}"
            refreshUi()
        }
    }

    private fun togglePreview() {
        if (videoUri == null) { statusText.text = "Pilih video terlebih dahulu untuk preview."; return }
        val player = previewPlayer
        if (player != null) {
            runCatching { if (player.isPlaying) { player.pause(); previewRequested = false } else { player.start(); previewRequested = true } }
        } else {
            previewRequested = true
            preparePreview()
        }
    }

    private fun preparePreview() {
        val uri = videoUri ?: return
        if (!previewTexture.isAvailable || previewPlayer != null) return
        try {
            val player = MediaPlayer()
            previewPlayer = player
            previewSurface = Surface(previewTexture.surfaceTexture)
            player.setSurface(previewSurface)
            player.setDataSource(this, uri)
            player.isLooping = true
            player.setOnVideoSizeChangedListener { _, _, _ -> applyPreviewTransform() }
            player.setOnPreparedListener { mp -> applyPreviewTransform(); if (previewRequested) mp.start() }
            player.setOnErrorListener { _, _, _ -> statusText.text = "Preview tidak didukung decoder perangkat untuk file ini."; releasePreview(); true }
            player.prepareAsync()
        } catch (t: Throwable) {
            statusText.text = "Preview gagal dibuka: ${t.message}"
            releasePreview()
        }
    }

    private fun applyPreviewTransform() {
        val player = previewPlayer ?: return
        if (!::previewTexture.isInitialized || previewTexture.width == 0 || previewTexture.height == 0) return
        val vw = previewTexture.width.toFloat(); val vh = previewTexture.height.toFloat()
        val sw = player.videoWidth.toFloat(); val sh = player.videoHeight.toFloat()
        if (sw <= 0f || sh <= 0f) return
        val renderedHeightRatio = (vw / sw * sh) / vh
        val matrix = Matrix().apply { setScale(1f, renderedHeightRatio, vw / 2f, vh / 2f) }
        previewTexture.setTransform(matrix)
    }

    private fun releasePreview() {
        runCatching { previewPlayer?.setSurface(null) }
        runCatching { previewPlayer?.stop() }
        runCatching { previewPlayer?.release() }
        runCatching { previewSurface?.release() }
        previewPlayer = null; previewSurface = null
    }

    private fun updatePreviewOverlay() {
        val uri = pngUri ?: run { previewOverlay.setImageDrawable(null); return }
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val sample = maxOf(1, maxOf(bounds.outWidth / 1920, bounds.outHeight / 1080))
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val source = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return
            val canvasBitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBitmap)
            val scale = minOf(1920f / source.width, 1080f / source.height)
            val w = source.width * scale; val h = source.height * scale
            val dst = RectF((1920f - w) / 2f, (1080f - h) / 2f, (1920f + w) / 2f, (1080f + h) / 2f)
            canvas.drawBitmap(source, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            source.recycle()
            previewOverlay.setImageBitmap(canvasBitmap)
        }.onFailure { error ->
            previewOverlay.setImageDrawable(null)
            statusText.text = "PNG terpilih, tetapi preview gagal dimuat: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun updateVolumeLabels() {
        videoVolumeLabel.text = "Volume video asli: $videoVolume%"
        musicVolumeLabel.text = "Volume musik tambahan: $musicVolume%"
    }

    private fun refreshUi() {
        val busy = ExportServiceState.isRunning
        videoLabel.text = "Video: ${videoUri?.let { displayName(it) + " • " + mediaDuration(it) } ?: "Belum dipilih"}"
        pngLabel.text = "PNG: ${pngUri?.let { displayName(it) } ?: "Belum dipilih"}"
        musicLabel.text = if (musicUris.isEmpty()) "Playlist: belum ada musik (opsional)" else "Playlist (${musicUris.size} lagu):\n" + musicUris.mapIndexed { i, uri -> "${i + 1}. ${displayName(uri)} • ${mediaDuration(uri)}" }.joinToString("\n") + "\n\nPemutaran berurutan dan diulang sampai video selesai."
        updateVolumeLabels()
        chooseVideoButton.isEnabled = !busy
        choosePngButton.isEnabled = !busy
        chooseMusicButton.isEnabled = !busy
        videoSeek.isEnabled = !busy; musicSeek.isEnabled = !busy
        updateTrimControls()
        exportButton.isEnabled = !busy && videoUri != null && pngUri != null
        cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        previewButton.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun displayName(uri: Uri): String = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: "file"

    private fun mediaDuration(uri: Uri): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return "durasi tidak diketahui"
            val total = ms / 1000
            "%02d:%02d".format(total / 60, total % 60)
        } catch (_: Throwable) { "durasi tidak diketahui" }
        finally { runCatching { retriever.release() } }
    }

    private fun cleanupStalePendingOutputs() {
        runCatching {
            val collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(android.provider.MediaStore.Video.Media._ID)
            val selection = "${android.provider.MediaStore.MediaColumns.IS_PENDING}=1 AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val ids = mutableListOf<Long>()
            contentResolver.query(collection, projection, selection, arrayOf("${OverlayConfig.OUTPUT_FOLDER}%"), null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                while (cursor.moveToNext()) ids.add(cursor.getLong(idColumn))
            }
            ids.forEach { contentResolver.delete(ContentUris.withAppendedId(collection, it), null, null) }
        }
    }

    private fun openOutput(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "video/mp4"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        runCatching { startActivity(intent) }.onFailure { statusText.text = "Tidak ada aplikasi pemutar video yang tersedia." }
    }

    private fun shareOutput(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivity(Intent.createChooser(intent, "Bagikan video"))
    }

    companion object {
        private const val REQ_VIDEO = 1001; private const val REQ_PNG = 1002; private const val REQ_MUSIC = 1003
        private const val KEY_VIDEO = "video_uri"; private const val KEY_PNG = "png_uri"; private const val KEY_MUSIC_LIST = "music_uri_list"
        private const val KEY_VIDEO_VOL = "video_volume"; private const val KEY_MUSIC_VOL = "music_volume"
        private const val KEY_TRIM_START = "trim_start_ms"; private const val KEY_TRIM_END = "trim_end_ms"
        private const val MIN_TRIM_MS = 500L
    }
}
