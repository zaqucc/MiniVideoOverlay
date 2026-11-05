package com.personal.minivideooverlay

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.Toast

class MainActivity : Activity() {
    private var videoUri: Uri? = null
    private var pngUri: Uri? = null
    private val musicUris = mutableListOf<Uri>()
    private var videoDurationMs = 0L
    private var lastOutputUri: Uri? = null

    private lateinit var videoInfo: TextView
    private lateinit var pngInfo: TextView
    private lateinit var musicInfo: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var exportButton: Button
    private lateinit var cancelButton: Button
    private lateinit var resultButtons: LinearLayout

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ExportService.ACTION_PROGRESS -> {
                    progress.visibility = View.VISIBLE
                    progress.progress = intent.getIntExtra(ExportService.EXTRA_PROGRESS, 0)
                    status.text = intent.getStringExtra(ExportService.EXTRA_MESSAGE) ?: "Mengekspor…"
                }
                ExportService.ACTION_FINISHED -> {
                    lastOutputUri = intent.getStringExtra(ExportService.EXTRA_OUTPUT)?.let(Uri::parse)
                    progress.progress = 100
                    status.text = "Ekspor selesai. Video tersimpan di Movies/MotionIN."
                    resultButtons.visibility = View.VISIBLE
                    refreshControls()
                }
                ExportService.ACTION_FAILED -> status.text = intent.getStringExtra(ExportService.EXTRA_MESSAGE) ?: "Ekspor gagal."
                ExportService.ACTION_STATE_CHANGED -> refreshControls()
            }
        }
    }
    private var receiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { state ->
            videoUri = state.getString("video")?.let(Uri::parse)
            pngUri = state.getString("png")?.let(Uri::parse)
            musicUris.addAll(state.getStringArrayList("music")?.map(Uri::parse) ?: emptyList())
            lastOutputUri = state.getString("output")?.let(Uri::parse)
        }
        videoDurationMs = videoUri?.let(::readDuration) ?: 0L
        setContentView(buildUi())
        startInput.setText(savedInstanceState?.getString("trim_start") ?: "00:00:00")
        endInput.setText(savedInstanceState?.getString("trim_end") ?: formatTime(videoDurationMs))
        refreshControls()
        lastOutputUri?.let { resultButtons.visibility = View.VISIBLE }
        if (ExportServiceState.isRunning) {
            progress.visibility = View.VISIBLE
            progress.progress = ExportServiceState.progress
            status.text = ExportServiceState.message
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ExportService.ACTION_PROGRESS)
            addAction(ExportService.ACTION_FINISHED)
            addAction(ExportService.ACTION_FAILED)
            addAction(ExportService.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("video", videoUri?.toString())
        outState.putString("png", pngUri?.toString())
        outState.putStringArrayList("music", ArrayList(musicUris.map(Uri::toString)))
        outState.putString("output", lastOutputUri?.toString())
        outState.putString("trim_start", startInput.text.toString())
        outState.putString("trim_end", endInput.text.toString())
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        fun persist(uri: Uri) {
            runCatching { contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        when (requestCode) {
            REQ_VIDEO -> data.data?.let { uri ->
                persist(uri)
                videoUri = uri
                videoDurationMs = readDuration(uri)
                startInput.setText("00:00:00")
                endInput.setText(formatTime(videoDurationMs))
                status.text = "Video berhasil dipilih."
            }
            REQ_PNG -> data.data?.let { uri ->
                persist(uri)
                pngUri = uri
                status.text = "PNG berhasil dipilih."
            }
            REQ_MUSIC -> {
                val selected = mutableListOf<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) selected.add(clip.getItemAt(i).uri) }
                if (selected.isEmpty()) data.data?.let(selected::add)
                selected.forEach { uri ->
                    if (musicUris.size < MAX_MUSIC && uri !in musicUris) {
                        persist(uri)
                        musicUris.add(uri)
                    }
                }
                if (selected.size > 0 && musicUris.size >= MAX_MUSIC) status.text = "Maksimal 4 musik."
            }
        }
        refreshControls()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val background = Color.rgb(15, 18, 23)
        val card = Color.rgb(26, 32, 41)
        val accent = Color.rgb(154, 190, 232)
        fun label(value: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                typeface = android.graphics.Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", 0)
            }
        fun button(value: String, primary: Boolean = false, action: () -> Unit) =
            Button(this).apply {
                text = value
                isAllCaps = false
                textSize = 14f
                setTextColor(if (primary) Color.rgb(18, 24, 31) else Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (primary) accent else Color.rgb(47, 57, 70))
                    cornerRadius = dp(14).toFloat()
                }
                stateListAnimator = null
                minHeight = dp(48)
                setOnClickListener { action() }
            }
        fun section(title: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(18).toFloat()
            }
            addView(label(title, 16f, Color.WHITE, true))
        }
        fun info() = label("", 12f, Color.LTGRAY).apply {
            setPadding(0, dp(7), 0, dp(8))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
            setBackgroundColor(background)
        }
        root.addView(label("MotionIN", 24f, Color.WHITE, true))
        root.addView(label("Video editor", 13f, Color.LTGRAY).apply {
            setPadding(0, dp(3), 0, dp(20))
        })

        val trim = section("Rentang video")
        trim.addView(label("Mulai · HH:MM:SS", 12f, Color.LTGRAY))
        startInput = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setHint("00:00:00")
            textSize = 18f
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            singleLine = true
            setText("00:00:00")
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(37, 45, 57)); cornerRadius = dp(10).toFloat()
            }
        }
        trim.addView(startInput, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(7) })
        trim.addView(label("Akhir · HH:MM:SS", 12f, Color.LTGRAY).apply {
            setPadding(0, dp(13), 0, 0)
        })
        endInput = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setHint("00:00:00")
            textSize = 18f
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            singleLine = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(37, 45, 57)); cornerRadius = dp(10).toFloat()
            }
        }
        trim.addView(endInput, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(7) })
        trim.addView(label("Contoh: 00:00:05 sampai 00:02:30", 11f, Color.LTGRAY).apply {
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(trim, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val media = section("Media")
        val videoButton = button("＋  Tambah video") { pickSingle(REQ_VIDEO, "video/*") }
        media.addView(videoButton, fullWidth(dp(46)))
        videoInfo = info()
        media.addView(videoInfo)
        val pngButton = button("＋  Tambah PNG overlay") { pickSingle(REQ_PNG, "image/png") }
        media.addView(pngButton, fullWidth(dp(46)))
        pngInfo = info()
        media.addView(pngInfo)
        root.addView(media, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val audio = section("Musik tambahan")
        audio.addView(label("Maksimal 4 file · diputar berulang selama video", 12f, Color.LTGRAY).apply {
            setPadding(0, dp(5), 0, dp(10))
        })
        audio.addView(button("＋  Tambah musik") { pickMusic() }, fullWidth(dp(46)))
        musicInfo = info()
        audio.addView(musicInfo)
        root.addView(audio, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        exportButton = button("Ekspor MP4 1080p", true) { startExport() }
        root.addView(exportButton, fullWidth(dp(54)))
        cancelButton = button("Batalkan ekspor") {
            startService(Intent(this, ExportService::class.java).setAction(ExportService.ACTION_CANCEL))
        }
        cancelButton.visibility = View.GONE
        root.addView(cancelButton, fullWidth(dp(46)).apply { topMargin = dp(8) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, fullWidth(dp(6)).apply { topMargin = dp(16) })
        status = label("Pilih video dan PNG untuk mulai.", 12f, Color.LTGRAY).apply {
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(status)
        resultButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(button("Putar hasil") { lastOutputUri?.let(::openOutput) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(6) })
            addView(button("Bagikan") { lastOutputUri?.let(::shareOutput) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6) })
        }
        root.addView(resultButtons, fullWidth(dp(46)))
        return ScrollView(this).apply {
            setBackgroundColor(background)
            isFillViewport = true
            addView(root)
        }
    }

    private fun fullWidth(height: Int) = LinearLayout.LayoutParams(-1, height)
    private fun pickSingle(request: Int, mime: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, request)
    }
    private fun pickMusic() {
        if (musicUris.size >= MAX_MUSIC) {
            Toast.makeText(this, "Maksimal 4 file musik.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_MUSIC)
    }

    private fun refreshControls() {
        if (!::videoInfo.isInitialized) return
        videoInfo.text = videoUri?.let { "Dipilih: ${displayName(it)} · ${formatTime(videoDurationMs)}" } ?: "Belum ada video"
        pngInfo.text = pngUri?.let { "Dipilih: ${displayName(it)}" } ?: "Belum ada PNG"
        musicInfo.text = if (musicUris.isEmpty()) "Belum ada musik (opsional)" else musicUris.mapIndexed { i, u -> "${i + 1}. ${displayName(u)}" }.joinToString("\n") + "\n${musicUris.size}/4 file"
        val busy = ExportServiceState.isRunning
        exportButton.isEnabled = !busy && videoUri != null && pngUri != null
        cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun startExport() {
        val video = videoUri ?: return
        val png = pngUri ?: return
        if (ExportServiceState.isRunning) return
        val start = parseTime(startInput.text.toString())
        val end = parseTime(endInput.text.toString())
        if (start == null || end == null) {
            status.text = "Format waktu harus HH:MM:SS."
            return
        }
        if (start >= end) {
            status.text = "Waktu mulai harus lebih kecil dari waktu akhir."
            return
        }
        if (videoDurationMs <= 0L || end > videoDurationMs) {
            status.text = "Waktu akhir melebihi durasi video (${formatTime(videoDurationMs)})."
            return
        }
        val intent = Intent(this, ExportService::class.java).apply {
            action = ExportService.ACTION_START
            putExtra(ExportService.EXTRA_VIDEO, video.toString())
            putExtra(ExportService.EXTRA_PNG, png.toString())
            putStringArrayListExtra(ExportService.EXTRA_MUSIC_LIST, ArrayList(musicUris.take(MAX_MUSIC).map(Uri::toString)))
            putExtra(ExportService.EXTRA_VIDEO_VOLUME, 100)
            putExtra(ExportService.EXTRA_MUSIC_VOLUME, 100)
            putExtra(ExportService.EXTRA_TRIM_START_MS, start)
            putExtra(ExportService.EXTRA_TRIM_END_MS, end)
        }
        lastOutputUri = null
        resultButtons.visibility = View.GONE
        progress.progress = 0
        progress.visibility = View.VISIBLE
        status.text = "Menyiapkan ekspor di latar belakang…"
        ExportServiceState.isRunning = true
        refreshControls()
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (t: Throwable) {
            ExportServiceState.isRunning = false
            status.text = "Ekspor tidak dapat dimulai: ${t.message ?: t.javaClass.simpleName}"
            refreshControls()
        }
    }

    private fun parseTime(value: String): Long? {
        val match = Regex("^(\\d{2,}):(\\d{2}):(\\d{2})$").matchEntire(value.trim()) ?: return null
        val h = match.groupValues[1].toLongOrNull() ?: return null
        val m = match.groupValues[2].toLongOrNull() ?: return null
        val s = match.groupValues[3].toLongOrNull() ?: return null
        if (m !in 0..59 || s !in 0..59) return null
        return (h * 3600L + m * 60L + s) * 1000L
    }
    private fun formatTime(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1000L
        return "%02d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
    }
    private fun readDuration(uri: Uri): Long = runCatching {
        MediaMetadataRetriever().let { retriever ->
            try {
                retriever.setDataSource(this, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { retriever.release() }
        }
    }.getOrDefault(0L)
    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "File"

    private fun openOutput(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure { status.text = "Aplikasi pemutar video tidak tersedia." }
    }
    private fun shareOutput(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Bagikan video"))
    }

    companion object {
        private const val REQ_VIDEO = 1001
        private const val REQ_PNG = 1002
        private const val REQ_MUSIC = 1003
        private const val MAX_MUSIC = 4
    }
}
