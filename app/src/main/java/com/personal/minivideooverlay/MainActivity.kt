package com.personal.minivideooverlay

import android.app.Activity
import android.content.Intent
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
    private var musicUri: Uri? = null
    private var engine: ExportEngine? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            videoUri = it.getString(KEY_VIDEO)?.let(Uri::parse)
            pngUri = it.getString(KEY_PNG)?.let(Uri::parse)
            musicUri = it.getString(KEY_MUSIC)?.let(Uri::parse)
            videoVolume = it.getInt(KEY_VIDEO_VOL, 100)
            musicVolume = it.getInt(KEY_MUSIC_VOL, 100)
        }
        setContentView(buildUi())
        cleanupStalePendingOutputs()
        refreshUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        videoUri?.let { outState.putString(KEY_VIDEO, it.toString()) }
        pngUri?.let { outState.putString(KEY_PNG, it.toString()) }
        musicUri?.let { outState.putString(KEY_MUSIC, it.toString()) }
        outState.putInt(KEY_VIDEO_VOL, videoVolume); outState.putInt(KEY_MUSIC_VOL, musicVolume)
    }

    override fun onDestroy() {
        if (isFinishing) engine?.cancel()
        releasePreview()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        when (requestCode) {
            REQ_VIDEO -> { videoUri = uri; releasePreview(); previewRequested = false }
            REQ_PNG -> {
                val warning = validatePng(uri)
                if (warning != null && !warning.startsWith("Peringatan:")) { statusText.text = warning; return }
                pngUri = uri
                if (warning != null) statusText.text = warning
            }
            REQ_MUSIC -> musicUri = uri
        }
        refreshUi()
        updatePreviewOverlay()
    }

    private fun buildUi(): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)); setBackgroundColor(Color.rgb(247, 248, 250)) }
        root.addView(TextView(this).apply { text = "MiniVideoOverlay"; textSize = 25f; setTextColor(Color.rgb(20, 28, 40)); setTypeface(null, android.graphics.Typeface.BOLD) })
        root.addView(TextView(this).apply { text = "Compositor video 16:9 • 1920 × 1080"; textSize = 13f; setTextColor(Color.DKGRAY) }, margin(dp(2)))

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

        root.addView(section("INPUT MEDIA", dp(16)))
        chooseVideoButton = Button(this).apply { text = "Pilih Video Utama"; setOnClickListener { pickFile(REQ_VIDEO, "video/*") } }
        root.addView(chooseVideoButton)
        videoLabel = label(); root.addView(videoLabel, margin(dp(2)))
        choosePngButton = Button(this).apply { text = "Pilih PNG Transparan"; setOnClickListener { pickFile(REQ_PNG, "image/png") } }
        root.addView(choosePngButton, margin(dp(8)))
        pngLabel = label(); root.addView(pngLabel, margin(dp(2)))
        chooseMusicButton = Button(this).apply { text = "Pilih Musik Tambahan (Opsional)"; setOnClickListener { pickFile(REQ_MUSIC, "audio/*") } }
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
        cancelButton = Button(this).apply { text = "Batalkan Ekspor"; visibility = View.GONE; setOnClickListener { engine?.cancel(); statusText.text = "Permintaan pembatalan dikirim…" } }
        root.addView(cancelButton)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress, margin(dp(8)))
        statusText = label(); root.addView(statusText, margin(dp(4)))
        playOutputButton = Button(this).apply { text = "Putar Hasil"; visibility = View.GONE; setOnClickListener { lastOutputUri?.let { openOutput(it) } } }
        shareOutputButton = Button(this).apply { text = "Bagikan Hasil"; visibility = View.GONE; setOnClickListener { lastOutputUri?.let { shareOutput(it) } } }
        root.addView(playOutputButton, margin(dp(6))); root.addView(shareOutputButton)

        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        return scroll
    }

    private fun section(text: String, top: Int) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(Color.rgb(95, 105, 120)); setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, top, 0, 4)
    }
    private fun label() = TextView(this).apply { textSize = 12f; setTextColor(Color.DKGRAY) }
    private fun margin(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top }
    private fun seekListener(changed: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) { changed(value) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
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
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return "PNG tidak dapat dibuka."
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
        if (engine != null) return
        val job = ExportEngine(applicationContext); engine = job
        lastOutputUri = null; playOutputButton.visibility = View.GONE; shareOutputButton.visibility = View.GONE
        progress.progress = 0; progress.visibility = View.VISIBLE
        statusText.text = "Memulai ekspor…"; refreshUi()
        Thread({
            try {
                val result = job.export(video, png, musicUri, videoVolume, musicVolume) { pct, message ->
                    main.post { if (engine === job) { progress.progress = pct; statusText.text = message } }
                }
                main.post {
                    lastOutputUri = result.uri
                    statusText.text = "Selesai: ${result.fileName}\nLokasi: Movies/MiniVideoOverlay" + if (result.hasAudio) "" else "\nCatatan: output tidak memiliki track audio."
                    playOutputButton.visibility = View.VISIBLE; shareOutputButton.visibility = View.VISIBLE
                }
            } catch (e: Throwable) {
                main.post { statusText.text = if (e is ExportException) e.message else "Ekspor gagal: ${e.message ?: e.javaClass.simpleName}" }
            } finally {
                main.post { if (engine === job) engine = null; refreshUi() }
            }
        }, "mvo-export-worker").start()
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
        }
    }

    private fun updateVolumeLabels() {
        videoVolumeLabel.text = "Volume video asli: $videoVolume%"
        musicVolumeLabel.text = "Volume musik tambahan: $musicVolume%"
    }

    private fun refreshUi() {
        val busy = engine != null
        videoLabel.text = "Video: ${videoUri?.let { displayName(it) + " • " + mediaDuration(it) } ?: "Belum dipilih"}"
        pngLabel.text = "PNG: ${pngUri?.let { displayName(it) } ?: "Belum dipilih"}"
        musicLabel.text = "Musik: ${musicUri?.let { displayName(it) + " • " + mediaDuration(it) } ?: "Tidak dipilih (opsional)"}"
        updateVolumeLabels()
        chooseVideoButton.isEnabled = !busy
        choosePngButton.isEnabled = !busy
        chooseMusicButton.isEnabled = !busy
        videoSeek.isEnabled = !busy; musicSeek.isEnabled = !busy
        exportButton.isEnabled = !busy && videoUri != null && pngUri != null
        cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        previewButton.isEnabled = !busy
        if (!busy) progress.visibility = if (progress.progress == 100) View.VISIBLE else View.GONE
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
        private const val KEY_VIDEO = "video_uri"; private const val KEY_PNG = "png_uri"; private const val KEY_MUSIC = "music_uri"
        private const val KEY_VIDEO_VOL = "video_volume"; private const val KEY_MUSIC_VOL = "music_volume"
    }
}
