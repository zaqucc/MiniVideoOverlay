package com.personal.minivideooverlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Single screen: choose video, choose photo, process. No settings, no overlay controls.
 * Configuration changes are handled in-place (see manifest configChanges), so the
 * activity is not recreated on rotation and the running export keeps its state.
 */
class MainActivity : Activity() {

    private var videoUri: Uri? = null
    private var photoUri: Uri? = null
    private var engine: ExportEngine? = null
    private val main = Handler(Looper.getMainLooper())

    private lateinit var videoLabel: TextView
    private lateinit var photoLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var chooseVideoButton: Button
    private lateinit var choosePhotoButton: Button
    private lateinit var processButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            videoUri = it.getString(KEY_VIDEO)?.let(Uri::parse)
            photoUri = it.getString(KEY_PHOTO)?.let(Uri::parse)
        }
        setContentView(buildUi())
        refreshUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        videoUri?.let { outState.putString(KEY_VIDEO, it.toString()) }
        photoUri?.let { outState.putString(KEY_PHOTO, it.toString()) }
    }

    override fun onDestroy() {
        // Only cancel when the user really leaves the screen, not on rotation.
        if (isFinishing) engine?.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_VIDEO -> videoUri = uri
            REQ_PHOTO -> photoUri = uri
        }
        refreshUi()
    }

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val gap = (12 * resources.displayMetrics.density).toInt()

        chooseVideoButton = Button(this).apply {
            text = "Choose Video"
            setOnClickListener { pickFile(REQ_VIDEO, "video/*") }
        }
        videoLabel = TextView(this)

        choosePhotoButton = Button(this).apply {
            text = "Choose Photo"
            setOnClickListener { pickFile(REQ_PHOTO, "image/*") }
        }
        photoLabel = TextView(this)

        processButton = Button(this).apply {
            text = "Process Video"
            setOnClickListener { startExport() }
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        statusText = TextView(this).apply { gravity = Gravity.START }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(chooseVideoButton)
            addView(videoLabel, withTopMargin(gap / 2))
            addView(choosePhotoButton, withTopMargin(gap))
            addView(photoLabel, withTopMargin(gap / 2))
            addView(processButton, withTopMargin(gap * 2))
            addView(progress, withTopMargin(gap))
            addView(statusText, withTopMargin(gap))
        }
    }

    private fun withTopMargin(px: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px }

    private fun pickFile(requestCode: Int, mimeType: String) {
        // Storage Access Framework: no storage permissions needed.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
        }
        startActivityForResult(intent, requestCode)
    }

    private fun startExport() {
        val video = videoUri ?: return
        val photo = photoUri ?: return
        if (engine != null) return // prevent duplicate export jobs

        val job = ExportEngine(applicationContext)
        engine = job
        statusText.text = "Exporting..."
        progress.progress = 0
        refreshUi()

        Thread({
            try {
                val result = job.export(video, photo) { pct ->
                    main.post { if (engine === job) progress.progress = pct }
                }
                main.post {
                    progress.progress = 100
                    statusText.text = "Done. Saved to ${OverlayConfig.OUTPUT_FOLDER}/${result.fileName}"
                }
            } catch (e: ExportException) {
                main.post { statusText.text = "Export failed: ${e.message}" }
            } catch (e: Throwable) {
                main.post { statusText.text = "Export failed: ${e.message ?: e.javaClass.simpleName}" }
            } finally {
                main.post {
                    if (engine === job) engine = null
                    refreshUi()
                }
            }
        }, "overlay-export").start()
    }

    private fun refreshUi() {
        val busy = engine != null
        videoLabel.text = "Video: " + (videoUri?.let { displayName(it) } ?: "not selected")
        photoLabel.text = "Photo: " + (photoUri?.let { displayName(it) } ?: "not selected")
        chooseVideoButton.isEnabled = !busy
        choosePhotoButton.isEnabled = !busy
        processButton.isEnabled = !busy && videoUri != null && photoUri != null
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment
            ?: "selected"

    companion object {
        private const val REQ_VIDEO = 1001
        private const val REQ_PHOTO = 1002
        private const val KEY_VIDEO = "video_uri"
        private const val KEY_PHOTO = "photo_uri"
    }
}
