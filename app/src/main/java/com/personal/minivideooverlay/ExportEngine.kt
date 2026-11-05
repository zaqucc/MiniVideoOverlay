package com.personal.minivideooverlay

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.DefaultGainProvider
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.DefaultEncoderFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.roundToInt

class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Media3-only export pipeline. Android framework APIs are used only for URI/bitmap/MediaStore I/O. */
@OptIn(UnstableApi::class)
class ExportEngine(private val appContext: Context) {
    data class Result(val uri: Uri, val fileName: String, val hasAudio: Boolean)

    @Volatile private var cancelled = false
    @Volatile var state: ExportState = ExportState.Idle
        private set
    @Volatile private var transformer: Transformer? = null
    @Volatile private var transformerHandler: Handler? = null

    fun cancel() {
        cancelled = true
        transformerHandler?.post { runCatching { transformer?.cancel() } }
    }

    fun export(
        videoUri: Uri,
        pngUri: Uri,
        musicUris: List<Uri>,
        videoVolume: Int,
        musicVolume: Int,
        trimStartMs: Long,
        trimEndMs: Long,
        onProgress: (Int, String) -> Unit
    ): Result {
        state = ExportState.Validating
        var overlayBitmap: Bitmap? = null
        var outputUri: Uri? = null
        var tempOutputFile: File? = null
        var published = false
        val fileName = "MotionIN_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.mp4"
        try {
            checkCancelled()
            onProgress(1, "Memvalidasi sumber media…")
            overlayBitmap = decodeFullCanvasPng(pngUri)
            val hasVideoAudio = videoVolume > 0 && hasAudioTrack(videoUri)
            // Preserve the exact playlist order, including repeated selections of the same URI.
            val normalizedMusic = musicUris.take(4)
            checkCancelled()

            state = ExportState.Preparing
            onProgress(3, "Menyusun timeline Media3…")
            val composition = buildComposition(
                videoUri = videoUri,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                overlayBitmap = overlayBitmap,
                musicUris = if (musicVolume > 0) normalizedMusic else emptyList(),
                includeVideoAudio = hasVideoAudio,
                videoGain = videoVolume.coerceIn(0, 100) / 100f,
                musicGain = musicVolume.coerceIn(0, 100) / 100f
            )
            // Keep the PNG bitmap alive: Media3's BitmapOverlay reads it during the asynchronous export.
            checkCancelled()

            state = ExportState.Exporting
            onProgress(5, "Media3 sedang mengekspor video…")
            val tempFile = File(appContext.cacheDir, "mvo_export_${System.nanoTime()}.mp4")
            tempOutputFile = tempFile
            if (tempFile.exists()) tempFile.delete()
            val renderedFile = runTransformer(composition, tempFile, resolveVideoBitrate(videoUri), onProgress)
            checkCancelled()

            state = ExportState.Finalizing
            onProgress(96, "Menyimpan hasil MP4…")
            val pendingUri = createPendingOutput(fileName)
            outputUri = pendingUri
            try {
                appContext.contentResolver.openOutputStream(pendingUri, "w")?.use { destination ->
                    // A larger copy buffer reduces small read/write operations for long 1080p exports.
                    renderedFile.inputStream().use { source -> source.copyTo(destination, 1024 * 1024) }
                } ?: throw ExportException("Tidak dapat membuka file tujuan di MediaStore.")
                checkCancelled()
                finalizePendingOutput(pendingUri)
            } catch (t: Throwable) {
                runCatching { appContext.contentResolver.delete(pendingUri, null, null) }
                outputUri = null
                throw t
            }
            published = true
            state = ExportState.Completed
            onProgress(100, "Ekspor selesai.")
            return Result(pendingUri, fileName, hasVideoAudio || (normalizedMusic.isNotEmpty() && musicVolume > 0))
        } catch (e: ExportException) {
            state = if (cancelled || e.message?.contains("dibatalkan", ignoreCase = true) == true) ExportState.Cancelled else ExportState.Failed
            throw e
        } catch (t: Throwable) {
            state = if (cancelled) ExportState.Cancelled else ExportState.Failed
            if (cancelled) throw ExportException("Ekspor dibatalkan.", t)
            throw ExportException("Ekspor Media3 gagal: ${t.message ?: t.javaClass.simpleName}", t)
        } finally {
            runCatching { overlayBitmap?.recycle() }
            runCatching { tempOutputFile?.delete() }
            if (!published) {
                outputUri?.let { pending -> runCatching { appContext.contentResolver.delete(pending, null, null) } }
            }
            transformer = null
            transformerHandler = null
        }
    }

    private fun buildComposition(
        videoUri: Uri,
        trimStartMs: Long,
        trimEndMs: Long,
        overlayBitmap: Bitmap,
        musicUris: List<Uri>,
        includeVideoAudio: Boolean,
        videoGain: Float,
        musicGain: Float
    ): Composition {
        val videoEffects = listOf(
            // Establish the output canvas first, then zoom only the video content.
            Presentation.createForWidthAndHeight(
                OverlayConfig.OUTPUT_WIDTH,
                OverlayConfig.OUTPUT_HEIGHT,
                Presentation.LAYOUT_SCALE_TO_FIT
            ),
            ScaleAndRotateTransformation.Builder()
                .setScale(1.16f, 1.16f)
                .build(),
            // Keep the PNG overlay last so its size and position are unaffected by video zoom.
            OverlayEffect(
                listOf(
                    BitmapOverlay.createStaticBitmapOverlay(overlayBitmap)
                )
            )
        )
        val videoAudioProcessors = if (includeVideoAudio) audioProcessors(videoGain) else emptyList()
        val clippingBuilder = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(trimStartMs.coerceAtLeast(0L))
        if (trimEndMs > 0L) clippingBuilder.setEndPositionMs(trimEndMs)
        val clipping = clippingBuilder.build()
        val clippedVideo = MediaItem.Builder().setUri(videoUri).setClippingConfiguration(clipping).build()
        val videoItem = EditedMediaItem.Builder(clippedVideo)
            .setRemoveAudio(!includeVideoAudio)
            .setEffects(Effects(videoAudioProcessors, videoEffects))
            .build()
        val videoSequence = if (includeVideoAudio) {
            EditedMediaItemSequence.withAudioAndVideoFrom(listOf(videoItem))
        } else {
            EditedMediaItemSequence.withVideoFrom(listOf(videoItem))
        }

        val sequences = mutableListOf(videoSequence)
        if (musicUris.isNotEmpty()) {
            val musicItems = musicUris.map { uri ->
                EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setRemoveVideo(true)
                    .setEffects(Effects(audioProcessors(musicGain), emptyList()))
                    .build()
            }
            val loopingMusic = EditedMediaItemSequence.withAudioFrom(musicItems)
                .buildUpon()
                .setIsLooping(true)
                .build()
            sequences += loopingMusic
        }
        return Composition.Builder(sequences).build()
    }

    private fun audioProcessors(gain: Float): List<AudioProcessor> {
        val channelMixer = ChannelMixingAudioProcessor().apply {
            for (channels in 1..6) {
                putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(channels, 2))
            }
        }
        return listOf(
            ToInt16PcmAudioProcessor(),
            channelMixer,
            GainProcessor(DefaultGainProvider.Builder(gain.coerceIn(0f, 1f)).build())
        )
    }

    private fun runTransformer(
        composition: Composition,
        outputFile: File,
        videoBitrate: Int,
        onProgress: (Int, String) -> Unit
    ): File {
        val thread = HandlerThread("mvo-media3-transformer")
        thread.start()
        val handler = Handler(thread.looper)
        transformerHandler = handler
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val progressHolder = ProgressHolder()
        val progressRunnable = object : Runnable {
            override fun run() {
                val active = transformer ?: return
                if (cancelled) {
                    runCatching { active.cancel() }
                    return
                }
                val progressState = runCatching { active.getProgress(progressHolder) }.getOrDefault(Transformer.PROGRESS_STATE_UNAVAILABLE)
                if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val percent = (5 + progressHolder.progress * 0.86f).roundToInt().coerceIn(5, 91)
                    onProgress(percent, "Media3 mengekspor video… ${progressHolder.progress}%")
                }
                if (finished.count > 0L) handler.postDelayed(this, 500L)
            }
        }

        handler.post {
            try {
                val encoderSettings = VideoEncoderSettings.Builder()
                    .setBitrate(videoBitrate)
                    .setiFrameIntervalSeconds(2f)
                    .build()
                val encoderFactory = DefaultEncoderFactory.Builder(appContext)
                    // Keep the requested H.264 settings when supported, but allow codec fallback
                    // on devices whose hardware encoder rejects a particular configuration.
                    .setEnableFallback(true)
                    .setEnableFormatFallback(true)
                    .setRequestedVideoEncoderSettings(encoderSettings)
                    .build()
                val built = Transformer.Builder(appContext)
                    .setLooper(thread.looper)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: androidx.media3.transformer.ExportResult) {
                            finished.countDown()
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                            exportException: androidx.media3.transformer.ExportException
                        ) {
                            failure.set(exportException)
                            finished.countDown()
                        }
                    })
                    .build()
                transformer = built
                if (cancelled) {
                    built.cancel()
                    finished.countDown()
                } else {
                    built.start(composition, outputFile.absolutePath)
                    handler.post(progressRunnable)
                }
            } catch (t: Throwable) {
                failure.set(t)
                finished.countDown()
            }
        }

        try {
            while (!finished.await(500, TimeUnit.MILLISECONDS)) {
                if (cancelled) handler.post { runCatching { transformer?.cancel() } }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            handler.post { runCatching { transformer?.cancel() } }
            throw ExportException("Ekspor dibatalkan.", interrupted)
        } finally {
            handler.removeCallbacks(progressRunnable)
            handler.post { transformer = null }
            thread.quitSafely()
            thread.join(2_000L)
        }
        if (cancelled) throw ExportException("Ekspor dibatalkan.")
        failure.get()?.let { throw ExportException("Media3 tidak dapat menyelesaikan ekspor: ${it.message}", it) }
        if (!outputFile.exists() || outputFile.length() <= 0L) {
            throw ExportException("Media3 selesai tetapi file hasil ekspor kosong atau tidak ditemukan.")
        }
        return outputFile
    }

    private fun decodeFullCanvasPng(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = appContext.contentResolver.openInputStream(uri)
            ?: throw ExportException("File PNG tidak dapat dibuka. Periksa izin akses file lalu pilih ulang PNG.")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ExportException("PNG tidak valid atau tidak dapat didekode.")
        if (bounds.outWidth > 12_000 || bounds.outHeight > 12_000) {
            throw ExportException("Dimensi PNG terlalu besar; maksimum 12000×12000.")
        }
        // The final composition is 1920x1080. Decoding PNGs far beyond the output canvas
        // adds memory pressure and decode time without adding visible output detail.
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 1_920) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw ExportException("PNG tidak valid atau tidak dapat didekode.")
        if (!decoded.hasAlpha()) {
            decoded.recycle()
            throw ExportException("PNG yang dipilih tidak memiliki transparansi alpha.")
        }
        try {
            val canvasBitmap = Bitmap.createBitmap(
                OverlayConfig.OUTPUT_WIDTH,
                OverlayConfig.OUTPUT_HEIGHT,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(canvasBitmap)
            // A newly allocated ARGB bitmap is already transparent; no full-canvas clear is needed.
            val scale = min(
                OverlayConfig.OUTPUT_WIDTH.toFloat() / decoded.width,
                OverlayConfig.OUTPUT_HEIGHT.toFloat() / decoded.height
            )
            val width = decoded.width * scale
            val height = decoded.height * scale
            val destination = RectF(
                (OverlayConfig.OUTPUT_WIDTH - width) / 2f,
                (OverlayConfig.OUTPUT_HEIGHT - height) / 2f,
                (OverlayConfig.OUTPUT_WIDTH + width) / 2f,
                (OverlayConfig.OUTPUT_HEIGHT + height) / 2f
            )
            canvas.drawBitmap(decoded, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
            return canvasBitmap
        } finally {
            decoded.recycle()
        }
    }


    private fun resolveVideoBitrate(uri: Uri): Int {
        val extractor = android.media.MediaExtractor()
        var descriptor: ParcelFileDescriptor? = null
        return try {
            descriptor = appContext.contentResolver.openFileDescriptor(uri, "r") ?: return OverlayConfig.VIDEO_BITRATE
            extractor.setDataSource(descriptor.fileDescriptor)
            val frameRate = (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                    runCatching {
                        format.getNumber(android.media.MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: 30f
                    }.getOrNull()
                } else null
            } ?: 30f
            if (frameRate >= 50f) OverlayConfig.VIDEO_BITRATE_60FPS else OverlayConfig.VIDEO_BITRATE
        } catch (_: Throwable) {
            OverlayConfig.VIDEO_BITRATE
        } finally {
            runCatching { extractor.release() }
            runCatching { descriptor?.close() }
        }
    }

    private fun hasAudioTrack(uri: Uri): Boolean {
        val extractor = android.media.MediaExtractor()
        var descriptor: ParcelFileDescriptor? = null
        return try {
            descriptor = appContext.contentResolver.openFileDescriptor(uri, "r") ?: return false
            extractor.setDataSource(descriptor.fileDescriptor)
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            }
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { extractor.release() }
            runCatching { descriptor?.close() }
        }
    }

    private fun createPendingOutput(name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, OverlayConfig.OUTPUT_FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return appContext.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw ExportException("Tidak dapat membuat file tujuan di MediaStore.")
    }

    private fun finalizePendingOutput(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        if (appContext.contentResolver.update(uri, values, null, null) <= 0) {
            throw ExportException("File selesai dibuat tetapi gagal dipublikasikan di MediaStore.")
        }
    }

    private fun checkCancelled() {
        if (cancelled) throw ExportException("Ekspor dibatalkan.")
    }
}

