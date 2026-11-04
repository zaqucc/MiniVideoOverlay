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
import java.util.concurrent.Executors
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
    private lateinit var musicListContainer: LinearLayout
    private lateinit var videoVolumeLabel: TextView
    private lateinit var musicVolumeLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var chooseVideoButton: Button
    private lateinit var choosePngButton: Button
    private lateinit var chooseMusicButton: Button
    private lateinit var exportButton: Button
    private lateinit var cancelButton: Button
    private lateinit var previewButton: TextView
    private lateinit var previewTexture: TextureView
    private var previewPlayer: MediaPlayer? = null
    private var previewOverlayBitmap: Bitmap? = null
    private val displayNameCache = mutableMapOf<String, String>()
    private val durationCache = mutableMapOf<String, String>()
    private var previewSurface: Surface? = null
    private var previewRequested = false
    private var previewPrepared = false
    private var previewPositionMs = 0L
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
    private lateinit var trimTimeline: RangeTrimTimelineView
    private val thumbnailExecutor = Executors.newSingleThreadExecutor()
    private var thumbnailLoadToken = 0
    private lateinit var trimStartLabel: TextView
    private lateinit var trimEndLabel: TextView
    private lateinit var playheadLabel: TextView
    private val editorToolViews = mutableMapOf<String, View>()
    private lateinit var trimPanel: LinearLayout
    private lateinit var mediaPanel: LinearLayout
    private lateinit var audioPanel: LinearLayout
    private lateinit var exportPanel: LinearLayout
    private lateinit var editorPanelHost: LinearLayout

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
        videoDurationMs = videoUri?.let { readDurationMs(it) } ?: 0L
        if (trimEndMs <= 0L || (videoDurationMs > 0L && trimEndMs > videoDurationMs)) trimEndMs = videoDurationMs
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2402)
        }
        cleanupStalePendingOutputs()
        ExportServiceState.outputUri = ExportServiceState.outputUri ?: getSharedPreferences("mvo_export_state", MODE_PRIVATE).getString("last_output_uri", null)
        ExportServiceState.outputUri?.let { lastOutputUri = Uri.parse(it) }
        refreshUi()
        updateTrimControls()
        videoUri?.let { loadTimelineThumbnails(it, videoDurationMs) }
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
        clearPreviewOverlay()
        thumbnailLoadToken++
        thumbnailExecutor.shutdownNow()
        if (::trimTimeline.isInitialized) trimTimeline.releaseThumbnails()
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
                loadTimelineThumbnails(chosenUri, videoDurationMs)
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
        val bg = Color.rgb(16, 17, 19)
        val surface = Color.rgb(30, 31, 34)
        val surface2 = Color.rgb(42, 43, 47)
        val muted = Color.rgb(176, 179, 184)
        val white = Color.WHITE
        val accent = Color.rgb(168, 199, 250)
        fun rounded(color: Int, radius: Int = 16) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
        }
        fun text(value: String, size: Float, color: Int = white, bold: Boolean = false) =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                typeface = android.graphics.Typeface.create(
                    if (bold) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL
                )
                includeFontPadding = false
            }
        fun panel(title: String): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(surface, 20)
            addView(text(title, 16f, white, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }
        fun actionButton(title: String, primary: Boolean = false, onClick: () -> Unit) =
            Button(this).apply {
                text = title
                isAllCaps = false
                textSize = 13f
                typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
                setTextColor(if (primary) Color.rgb(25, 28, 33) else white)
                background = rounded(if (primary) accent else surface2, 24)
                setPadding(dp(18), dp(7), dp(18), dp(7))
                minHeight = dp(46)
                stateListAnimator = null
                setOnClickListener { onClick() }
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(bg)
        }

        // Compact Material-style top app bar.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(text("MOTIONIX", 12f, muted, true))
        heading.addView(text("Edit video", 21f, white, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        topBar.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        exportButton = actionButton("Simpan", true) { startExport() }
        topBar.addView(exportButton, LinearLayout.LayoutParams(dp(104), dp(44)))
        root.addView(topBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })

        // Large, distraction-free video canvas.
        previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            background = rounded(Color.BLACK, 18)
            clipToOutline = true
        }
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
        root.addView(previewFrame, LinearLayout.LayoutParams(-1, dp(214)))

        val previewControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(18))
        }
        previewButton = TextView(this).apply {
            text = "▶"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(25, 28, 33))
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            background = rounded(accent, 28)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { togglePreview() }
        }
        previewControls.addView(previewButton)
        playheadLabel = text("00:00:00", 14f, white, true).apply {
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        previewControls.addView(playheadLabel)
        previewControls.addView(text(formatTime(videoDurationMs), 12f, muted))
        root.addView(previewControls)

        // Filmstrip with independent start/end grips and a separate white playhead.
        val timelineHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(text("Potong", 18f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("TRIM", 10f, muted, true))
        }
        root.addView(timelineHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        trimTimeline = RangeTrimTimelineView(this).apply {
            setVideoDuration(videoDurationMs)
            setRange(trimStartMs, trimEndMs)
            setPlayhead(previewPositionMs)
            onRangeChanged = { start, end ->
                trimStartMs = start
                trimEndMs = end
                if (previewPositionMs < start || previewPositionMs > end) {
                    previewPositionMs = start
                    setPlayhead(previewPositionMs)
                    seekPreviewTo(previewPositionMs)
                }
                updateTrimControls()
            }
            onScrubStarted = {
                previewRequested = false
                runCatching { previewPlayer?.pause() }
                main.removeCallbacks(previewTicker)
                if (previewPlayer == null) preparePreview()
                updatePreviewButton()
            }
            onPlayheadChanged = { position ->
                previewPositionMs = position
                playheadLabel.text = formatTime(position)
                seekPreviewTo(position)
            }
        }
        root.addView(trimTimeline, LinearLayout.LayoutParams(-1, dp(112)))

        val trimTimes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), dp(2), dp(18))
        }
        trimStartLabel = text("MULAI  00:00:00", 12f, white, true)
        trimEndLabel = text("AKHIR  00:00:00", 12f, white, true)
        trimTimes.addView(trimStartLabel, LinearLayout.LayoutParams(0, -2, 1f))
        trimTimes.addView(trimEndLabel)
        root.addView(trimTimes)

        // Symmetrical icon-and-label tool dock. Icons are drawn as simple Material-style line art.
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            background = rounded(surface, 22)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        fun tool(label: String, key: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, dp(76), 1f)
            addView(createMaterialToolIcon(key, muted), LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(text(label, 11f, muted, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(7) })
            setOnClickListener { showEditorTool(key) }
            editorToolViews[key] = this
        }
        tools.addView(tool("Potong", "trim"))
        tools.addView(tool("Overlay", "media"))
        tools.addView(tool("Audio", "audio"))
        tools.addView(tool("Ekspor", "export"))
        root.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        editorPanelHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        trimPanel = panel("Rentang video").apply {
            addView(text("Geser garis putih untuk mencari frame. Tarik pegangan kiri dan kanan untuk menentukan bagian yang disimpan.", 13f, muted))
        }
        mediaPanel = panel("Media").apply {
            chooseVideoButton = actionButton("Pilih video") { pickFile(REQ_VIDEO, "video/*") }
            addView(chooseVideoButton)
            videoLabel = text("Video belum dipilih", 12f, muted)
            addView(videoLabel, margin(dp(8)))
            choosePngButton = actionButton("Pilih overlay PNG") { pickFile(REQ_PNG, "image/png") }
            addView(choosePngButton, margin(dp(16)))
            pngLabel = text("Overlay belum dipilih", 12f, muted)
            addView(pngLabel, margin(dp(8)))
        }
        audioPanel = panel("Audio").apply {
            chooseMusicButton = actionButton("＋  Tambah musik") { pickMusicFiles() }
            addView(chooseMusicButton)
            musicLabel = text("Playlist belum ada", 12f, muted)
            addView(musicLabel, margin(dp(10)))
            musicListContainer = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(musicListContainer)
            videoVolumeLabel = text("Volume video asli: $videoVolume%", 13f, white, true)
            addView(videoVolumeLabel, margin(dp(14)))
            videoSeek = SeekBar(this@MainActivity).apply {
                max = 100; progress = videoVolume
                setOnSeekBarChangeListener(seekListener { videoVolume = it; updateVolumeLabels() })
            }
            addView(videoSeek)
            musicVolumeLabel = text("Volume musik tambahan: $musicVolume%", 13f, white, true)
            addView(musicVolumeLabel, margin(dp(12)))
            musicSeek = SeekBar(this@MainActivity).apply {
                max = 100; progress = musicVolume
                setOnSeekBarChangeListener(seekListener { musicVolume = it; updateVolumeLabels() })
            }
            addView(musicSeek)
        }
        exportPanel = panel("Ekspor video").apply {
            addView(text("Video akan disimpan sebagai MP4 Full HD.", 13f, muted))
            cancelButton = actionButton("Batalkan ekspor") {
                startService(Intent(this@MainActivity, ExportService::class.java).setAction(ExportService.ACTION_CANCEL))
                statusText.text = "Permintaan pembatalan dikirim…"
            }
            cancelButton.visibility = View.GONE
            addView(cancelButton, margin(dp(12)))
            progress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; visibility = View.GONE
                progressTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            addView(progress, margin(dp(12)))
            statusText = text("Pilih video dan overlay untuk mulai.", 12f, muted)
            addView(statusText, margin(dp(10)))
            playOutputButton = actionButton("Putar hasil") { lastOutputUri?.let { openOutput(it) } }
            shareOutputButton = actionButton("Bagikan hasil") { lastOutputUri?.let { shareOutput(it) } }
            playOutputButton.visibility = View.GONE
            shareOutputButton.visibility = View.GONE
            addView(playOutputButton, margin(dp(10)))
            addView(shareOutputButton, margin(dp(8)))
        }
        editorPanelHost.addView(trimPanel)
        editorPanelHost.addView(mediaPanel)
        editorPanelHost.addView(audioPanel)
        editorPanelHost.addView(exportPanel)
        root.addView(editorPanelHost)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(bg)
            addView(root)
        }
        showEditorTool("trim")
        updateVolumeLabels()
        return scroll
    }

    private fun createMaterialToolIcon(kind: String, tint: Int): View {
        return object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tint
                style = Paint.Style.STROKE
                strokeWidth = resources.displayMetrics.density * 1.8f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val s = resources.displayMetrics.density
                val cx = width / 2f
                val cy = height / 2f
                canvas.save()
                canvas.translate(cx - 12f * s, cy - 12f * s)
                canvas.scale(s, s)
                val path = android.graphics.Path()
                when (kind) {
                    "trim" -> {
                        path.moveTo(4f, 7f); path.lineTo(4f, 4f); path.lineTo(7f, 4f)
                        path.moveTo(17f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 7f)
                        path.moveTo(4f, 17f); path.lineTo(4f, 20f); path.lineTo(7f, 20f)
                        path.moveTo(17f, 20f); path.lineTo(20f, 20f); path.lineTo(20f, 17f)
                        canvas.drawPath(path, paint)
                        canvas.drawLine(7f, 12f, 17f, 12f, paint)
                    }
                    "media" -> {
                        path.moveTo(12f, 3f); path.lineTo(21f, 8f); path.lineTo(12f, 13f); path.lineTo(3f, 8f); path.close()
                        canvas.drawPath(path, paint)
                        path.reset(); path.moveTo(3f, 12f); path.lineTo(12f, 17f); path.lineTo(21f, 12f)
                        canvas.drawPath(path, paint)
                        path.reset(); path.moveTo(3f, 16f); path.lineTo(12f, 21f); path.lineTo(21f, 16f)
                        canvas.drawPath(path, paint)
                    }
                    "audio" -> {
                        path.moveTo(9f, 18f); path.lineTo(9f, 5f); path.lineTo(20f, 3f); path.lineTo(20f, 16f)
                        canvas.drawPath(path, paint)
                        canvas.drawCircle(6.5f, 18f, 2.5f, paint)
                        canvas.drawCircle(17.5f, 16f, 2.5f, paint)
                    }
                    else -> {
                        path.moveTo(12f, 16f); path.lineTo(12f, 3f)
                        path.moveTo(7f, 8f); path.lineTo(12f, 3f); path.lineTo(17f, 8f)
                        path.moveTo(4f, 14f); path.lineTo(4f, 20f); path.lineTo(20f, 20f); path.lineTo(20f, 14f)
                        canvas.drawPath(path, paint)
                    }
                }
                canvas.restore()
            }
        }
    }

    private fun showEditorTool(tool: String) {
        if (!::trimPanel.isInitialized) return
        trimPanel.visibility = if (tool == "trim") View.VISIBLE else View.GONE
        mediaPanel.visibility = if (tool == "media") View.VISIBLE else View.GONE
        audioPanel.visibility = if (tool == "audio") View.VISIBLE else View.GONE
        exportPanel.visibility = if (tool == "export") View.VISIBLE else View.GONE
        val muted = Color.rgb(176, 179, 184)
        val accent = Color.rgb(168, 199, 250)
        editorToolViews.forEach { (key, view) ->
            val selected = key == tool
            view.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (selected) Color.rgb(55, 62, 74) else Color.TRANSPARENT)
                cornerRadius = resources.displayMetrics.density * 16f
            }
            (view as? LinearLayout)?.let { column ->
                if (column.childCount > 0) (column.getChildAt(0) as? View)?.invalidate()
                if (column.childCount > 1) (column.getChildAt(1) as? TextView)?.setTextColor(if (selected) accent else muted)
            }
        }
    }

    private fun applyProfessionalTheme(view: View) {
        when (view) {
            is Button -> {
                view.isAllCaps = false
                view.textSize = 12f
                view.typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
                view.setTextColor(Color.WHITE)
                view.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(48, 49, 52))
                    cornerRadius = resources.displayMetrics.density * 22f
                }
            }
            is SeekBar -> {
                view.progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(65, 132, 245))
                view.thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(65, 132, 245))
                view.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(72, 72, 72))
            }
        }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) applyProfessionalTheme(view.getChildAt(index))
        }
    }

    private fun updateTrimControls() {
        if (!::trimTimeline.isInitialized) return
        val duration = videoDurationMs
        trimTimeline.isEnabled = duration > MIN_TRIM_MS && !ExportServiceState.isRunning
        trimTimeline.setVideoDuration(duration)
        trimTimeline.setRange(trimStartMs, trimEndMs)
        val selected = (trimEndMs - trimStartMs).coerceAtLeast(0L)
        trimStartLabel.text = "MULAI  ${formatTime(trimStartMs)}"
        trimEndLabel.text = "AKHIR  ${formatTime(trimEndMs)}   ·   ${formatTime(selected)}"
        if (::playheadLabel.isInitialized) playheadLabel.text = formatTime(previewPositionMs)
        if (::trimTimeline.isInitialized) trimTimeline.setPlayhead(previewPositionMs)
    }

    private fun loadTimelineThumbnails(uri: Uri, durationMs: Long) {
        val token = ++thumbnailLoadToken
        if (::trimTimeline.isInitialized) trimTimeline.releaseThumbnails()
        if (durationMs <= 0L) return
        thumbnailExecutor.execute {
            val frames = mutableListOf<Bitmap>()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                for (index in 0 until 8) {
                    val timeUs = (durationMs * (index + 0.5) / 8.0 * 1000.0).toLong()
                    val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        val scaled = Bitmap.createScaledBitmap(frame, 180, 100, true)
                        if (scaled !== frame) frame.recycle()
                        frames.add(scaled)
                    }
                }
            } catch (_: Throwable) {
                frames.forEach { if (!it.isRecycled) it.recycle() }
                frames.clear()
            } finally {
                runCatching { retriever.release() }
            }
            main.post {
                if (token == thumbnailLoadToken && !isFinishing && ::trimTimeline.isInitialized) {
                    trimTimeline.setThumbnails(frames)
                } else {
                    frames.forEach { if (!it.isRecycled) it.recycle() }
                }
            }
        }
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
        override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) changed(value) }
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
        if (player != null && previewPrepared) {
            if (player.isPlaying) {
                runCatching { player.pause() }
                previewRequested = false
                main.removeCallbacks(previewTicker)
            } else {
                if (previewPositionMs < trimStartMs || previewPositionMs >= trimEndMs) {
                    previewPositionMs = trimStartMs
                    seekPreviewTo(previewPositionMs)
                }
                previewRequested = true
                runCatching { player.start() }
                main.removeCallbacks(previewTicker)
                main.post(previewTicker)
            }
        } else {
            previewRequested = true
            preparePreview()
        }
        updatePreviewButton()
    }

    private fun updatePreviewButton() {
        if (::previewButton.isInitialized) previewButton.text = if (previewPlayer?.isPlaying == true) "Ⅱ" else "▶"
    }

    private fun seekPreviewTo(position: Long) {
        val target = position.coerceIn(0L, videoDurationMs)
        previewPositionMs = target
        if (::trimTimeline.isInitialized) trimTimeline.setPlayhead(target)
        if (::playheadLabel.isInitialized) playheadLabel.text = formatTime(target)
        if (previewPrepared) runCatching { previewPlayer?.seekTo(target.toInt()) }
    }

    private val previewTicker = object : Runnable {
        override fun run() {
            val player = previewPlayer
            if (player != null && previewPrepared && previewRequested && runCatching { player.isPlaying }.getOrDefault(false)) {
                val position = runCatching { player.currentPosition.toLong() }.getOrDefault(previewPositionMs)
                previewPositionMs = position
                if (position >= trimEndMs) {
                    runCatching { player.pause(); player.seekTo(trimEndMs.toInt()) }
                    previewPositionMs = trimEndMs
                    previewRequested = false
                    updateTrimControls()
                    updatePreviewButton()
                } else {
                    if (::trimTimeline.isInitialized) trimTimeline.setPlayhead(position)
                    if (::playheadLabel.isInitialized) playheadLabel.text = formatTime(position)
                    main.postDelayed(this, 70L)
                }
            }
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
            player.setOnPreparedListener { mp ->
                previewPrepared = true
                applyPreviewTransform()
                mp.seekTo(previewPositionMs.toInt())
                if (previewRequested) {
                    mp.start()
                    main.removeCallbacks(previewTicker)
                    main.post(previewTicker)
                }
                updatePreviewButton()
            }
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

    private fun clearPreviewOverlay() {
        if (::previewOverlay.isInitialized) previewOverlay.setImageDrawable(null)
        previewOverlayBitmap?.let { if (!it.isRecycled) it.recycle() }
        previewOverlayBitmap = null
    }

    private fun releasePreview() {
        runCatching { previewPlayer?.setSurface(null) }
        runCatching { previewPlayer?.stop() }
        runCatching { previewPlayer?.release() }
        runCatching { previewSurface?.release() }
        main.removeCallbacks(previewTicker)
        previewPlayer = null; previewSurface = null; previewPrepared = false
    }

    private fun updatePreviewOverlay() {
        val uri = pngUri ?: run { clearPreviewOverlay(); return }
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
            clearPreviewOverlay()
            previewOverlayBitmap = canvasBitmap
            previewOverlay.setImageBitmap(canvasBitmap)
        }.onFailure { error ->
            clearPreviewOverlay()
            statusText.text = "PNG terpilih, tetapi preview gagal dimuat: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun renderMusicPlaylist(busy: Boolean) {
        if (!::musicListContainer.isInitialized) return
        musicListContainer.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        musicUris.forEachIndexed { index, uri ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(5), dp(6), dp(5))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(20, 31, 48))
                    cornerRadius = dp(10).toFloat()
                }
            }
            val track = TextView(this).apply {
                text = "%02d  %s\n%s".format(index + 1, displayName(uri), mediaDuration(uri))
                textSize = 12f
                setTextColor(Color.rgb(204, 215, 228))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(4), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val remove = Button(this).apply {
                text = "Hapus"
                isEnabled = !busy
                textSize = 11f
                setOnClickListener {
                    if (!ExportServiceState.isRunning && index in musicUris.indices) {
                        musicUris.removeAt(index)
                        refreshUi()
                    }
                }
            }
            row.addView(track)
            row.addView(remove, LinearLayout.LayoutParams(dp(72), dp(42)))
            applyProfessionalTheme(row)
            musicListContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(6)
            })
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
        musicLabel.text = if (musicUris.isEmpty()) "Playlist: belum ada musik (opsional)" else "Playlist (${musicUris.size} lagu) · diputar berurutan dan diulang sampai video selesai."
        renderMusicPlaylist(busy)
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

    private fun displayName(uri: Uri): String = displayNameCache.getOrPut(uri.toString()) {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file"
    }

    private fun mediaDuration(uri: Uri): String = durationCache.getOrPut(uri.toString()) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (ms == null) "durasi tidak diketahui" else {
                val total = ms / 1000
                "%02d:%02d".format(total / 60, total % 60)
            }
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
