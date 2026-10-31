package com.personal.minivideooverlay

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class ExportException(message: String) : Exception(message)

/**
 * One-shot export pipeline. Create a new instance for every export.
 *
 *   video track -> MediaCodec decoder -> SurfaceTexture -> GlCompositor (blur, shadow, photo)
 *       -> encoder input Surface -> MediaCodec H.264 -> MediaMuxer (MP4, video only)
 *
 * Output is always exactly OverlayConfig.OUTPUT_DURATION_US long. If the source is longer,
 * decoding stops at 15 s. If it is shorter, the source is decoded again from its start
 * (loop) with timestamps continued, until 15 s is reached. The audio track is never read,
 * so the output MP4 has no audio stream.
 *
 * The output is written to MediaStore with IS_PENDING set. On failure the entry is deleted.
 */
class ExportEngine(private val appContext: Context) {

    class Result(val uri: Uri, val fileName: String)

    private class PassResult(
        val firstSrcUs: Long,
        val lastSrcUs: Long,
        val framesRendered: Int,
        val reachedTarget: Boolean
    )

    @Volatile private var cancelled = false
    private val frameSignal = Semaphore(0)
    private val encoderInfo = MediaCodec.BufferInfo()

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var muxerStarted = false

    /** Requests cancellation. The export stops at the next step and its output is deleted. */
    fun cancel() {
        cancelled = true
    }

    fun export(videoUri: Uri, photoUri: Uri, onProgress: (Int) -> Unit): Result {
        val resolver = appContext.contentResolver

        // Decode the photo first so an unreadable photo fails before any output entry is created.
        val overlay = decodeSquareOverlay(photoUri)
        val fileName = "overlay_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val outUri = insertPendingOutput(fileName)

        var success = false
        var resultName = fileName
        var videoPfd: ParcelFileDescriptor? = null
        var outPfd: ParcelFileDescriptor? = null
        val videoExtractor = MediaExtractor()
        var decoderSurface: Surface? = null
        var surfaceTexture: SurfaceTexture? = null
        var compositor: GlCompositor? = null

        try {
            outPfd = resolver.openFileDescriptor(outUri, "rw")
                ?: throw ExportException("Cannot open the output file")

            // ----- Source video: only the video track is selected. Audio is never read. -----
            videoPfd = resolver.openFileDescriptor(videoUri, "r")
                ?: throw ExportException("Cannot open the selected video")
            videoExtractor.setDataSource(videoPfd.fileDescriptor)
            val videoIndex = findTrack(videoExtractor, "video/")
                ?: throw ExportException("No video track found in the selected file")
            videoExtractor.selectTrack(videoIndex)
            val srcFormat = videoExtractor.getTrackFormat(videoIndex)
            val srcMime = srcFormat.getString(MediaFormat.KEY_MIME)
                ?: throw ExportException("Unknown video format")

            val frameRate = runCatching { srcFormat.getInteger(MediaFormat.KEY_FRAME_RATE) }
                .getOrNull()?.coerceIn(1, OverlayConfig.MAX_FRAME_RATE)
                ?: OverlayConfig.DEFAULT_FRAME_RATE
            val frameDurUs = 1_000_000L / frameRate

            // Aspect ratio after rotation metadata, for the center-crop to 9:16.
            val srcW = srcFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = srcFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (srcFormat.containsKey(ROTATION_KEY)) srcFormat.getInteger(ROTATION_KEY) else 0
            val rotated = rotation == 90 || rotation == 270
            val displayW = if (rotated) srcH else srcW
            val displayH = if (rotated) srcW else srcH
            val sourceAspect = displayW.toFloat() / displayH.toFloat()
            val targetAspect = OverlayConfig.OUTPUT_WIDTH.toFloat() / OverlayConfig.OUTPUT_HEIGHT.toFloat()
            val wider = sourceAspect > targetAspect
            val cropX = if (wider) targetAspect / sourceAspect else 1f
            val cropY = if (wider) 1f else sourceAspect / targetAspect

            // ----- Encoder: fail clearly if 1080x1920 is not supported. No fallback resolution. -----
            val enc = MediaCodec.createEncoderByType(OverlayConfig.MIME_AVC)
            encoder = enc
            val caps = enc.codecInfo.getCapabilitiesForType(OverlayConfig.MIME_AVC).videoCapabilities
            if (!caps.isSizeSupported(OverlayConfig.OUTPUT_WIDTH, OverlayConfig.OUTPUT_HEIGHT)) {
                throw ExportException(
                    "This device's H.264 encoder cannot produce 1080×1920. " +
                        "No lower-resolution output is created."
                )
            }
            val encFormat = MediaFormat.createVideoFormat(
                OverlayConfig.MIME_AVC, OverlayConfig.OUTPUT_WIDTH, OverlayConfig.OUTPUT_HEIGHT
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, OverlayConfig.VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encSurface = enc.createInputSurface()
            enc.start()

            muxer = MediaMuxer(outPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // ----- GL compositor, decoder output texture, decoder surface -----
            val comp = GlCompositor(encSurface, overlay)
            compositor = comp
            overlay.recycle()

            val texture = SurfaceTexture(comp.videoTextureId)
            surfaceTexture = texture
            texture.setOnFrameAvailableListener { frameSignal.release() }
            val dSurface = Surface(texture)
            decoderSurface = dSurface

            // ----- Decode / render passes until 15 s is reached (trim or loop) -----
            var outputBaseUs = 0L
            while (true) {
                checkCancelled()
                videoExtractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val pass = renderPass(
                    videoExtractor, srcMime, srcFormat, dSurface, texture, comp,
                    cropX, cropY, outputBaseUs, frameDurUs
                ) { outUs ->
                    onProgress((outUs * 100L / OverlayConfig.OUTPUT_DURATION_US).toInt().coerceIn(0, 99))
                }
                if (pass.reachedTarget) break
                if (pass.framesRendered == 0) throw ExportException("No frames could be decoded from the video")
                // Pass ended before 15 s: the next pass continues the timeline from here.
                outputBaseUs += pass.lastSrcUs - pass.firstSrcUs + frameDurUs
            }

            // ----- Finish -----
            enc.signalEndOfInputStream()
            drainEncoder(endOfStream = true)
            if (!muxerStarted) throw ExportException("Encoder produced no output. The video may be unsupported.")
            muxer?.stop()
            success = true
            resultName = queryDisplayName(outUri) ?: fileName
        } finally {
            runCatching { decoderSurface?.release() }
            runCatching { surfaceTexture?.release() }
            runCatching { compositor?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { videoPfd?.close() }
            runCatching { outPfd?.close() }
            encoder = null
            muxer = null
            muxerStarted = false
            videoTrack = -1

            if (success) {
                finalizePendingOutput(outUri)
            } else {
                runCatching { resolver.delete(outUri, null, null) }
            }
        }

        return Result(outUri, resultName)
    }

    /**
     * Decodes the source from its start and renders frames whose output time is below 15 s.
     * Output time = outputBaseUs + (source pts - first source pts of this pass).
     * A new decoder is created per pass so that every pass starts cleanly with EOS.
     */
    private fun renderPass(
        extractor: MediaExtractor,
        srcMime: String,
        srcFormat: MediaFormat,
        surface: Surface,
        texture: SurfaceTexture,
        comp: GlCompositor,
        cropX: Float,
        cropY: Float,
        outputBaseUs: Long,
        frameDurUs: Long,
        onFrame: (Long) -> Unit
    ): PassResult {
        val dec = MediaCodec.createDecoderByType(srcMime)
        val stMatrix = FloatArray(16)
        val info = MediaCodec.BufferInfo()
        var firstSrcUs = -1L
        var lastSrcUs = 0L
        var framesRendered = 0
        var reachedTarget = false
        try {
            dec.configure(srcFormat, surface, null, 0)
            dec.start()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                checkCancelled()

                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(OverlayConfig.CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)
                            ?: throw ExportException("Decoder input buffer unavailable")
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(info, OverlayConfig.CODEC_TIMEOUT_US)
                if (outIdx < 0) continue

                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                if (info.size > 0) {
                    val srcUs = info.presentationTimeUs
                    if (firstSrcUs < 0L) firstSrcUs = srcUs
                    lastSrcUs = srcUs
                    val outUs = outputBaseUs + (srcUs - firstSrcUs)

                    if (outUs + frameDurUs > OverlayConfig.OUTPUT_DURATION_US) {
                        // Trim point reached: a frame that would run past 15.000 s, and
                        // everything after it, is dropped so the file ends at 15 s.
                        dec.releaseOutputBuffer(outIdx, false)
                        reachedTarget = true
                        break
                    }

                    dec.releaseOutputBuffer(outIdx, true)
                    awaitFrame()
                    texture.updateTexImage()
                    texture.getTransformMatrix(stMatrix)
                    comp.drawFrame(stMatrix, cropX, cropY, outUs * 1000L)
                    drainEncoder(endOfStream = false)
                    framesRendered++
                    onFrame(outUs)
                } else {
                    dec.releaseOutputBuffer(outIdx, false)
                }

                if (eos) outputDone = true
            }
        } finally {
            runCatching { dec.stop() }
            runCatching { dec.release() }
        }
        return PassResult(firstSrcUs, lastSrcUs, framesRendered, reachedTarget)
    }

    // ------------------------------------------------------------------
    // Encoder drain (video only)
    // ------------------------------------------------------------------

    private fun drainEncoder(endOfStream: Boolean) {
        val enc = encoder ?: return
        val mux = muxer ?: return
        var idleTries = 0
        while (true) {
            val idx = enc.dequeueOutputBuffer(
                encoderInfo, if (endOfStream) OverlayConfig.CODEC_TIMEOUT_US else 0L
            )
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idleTries > OverlayConfig.ENCODER_DRAIN_MAX_IDLE) {
                        throw ExportException("Encoder stopped responding")
                    }
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrack = mux.addTrack(enc.outputFormat)
                    mux.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)
                        ?: throw ExportException("Encoder output buffer unavailable")
                    if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encoderInfo.size = 0
                    }
                    if (encoderInfo.size > 0 && muxerStarted) {
                        buf.position(encoderInfo.offset)
                        buf.limit(encoderInfo.offset + encoderInfo.size)
                        mux.writeSampleData(videoTrack, buf, encoderInfo)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun awaitFrame() {
        if (!frameSignal.tryAcquire(OverlayConfig.FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw ExportException("Video decoding timed out")
        }
    }

    private fun checkCancelled() {
        if (cancelled) throw ExportException("Export cancelled")
    }

    private fun findTrack(ex: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return null
    }

    /**
     * Decodes the photo (EXIF orientation applied), center-crops it to a square,
     * and scales it to OVERLAY_SIZE without stretching.
     */
    private fun decodeSquareOverlay(photoUri: Uri): Bitmap {
        val decoded: Bitmap = try {
            val source = ImageDecoder.createSource(appContext.contentResolver, photoUri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Keep the shorter side at least 2x the overlay size to limit memory use.
                val sample = max(1, min(info.size.width, info.size.height) / (OverlayConfig.OVERLAY_SIZE * 2))
                decoder.setTargetSampleSize(sample)
            }
        } catch (e: Exception) {
            throw ExportException("The photo cannot be read or its format is not supported")
        }
        val side = min(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(
            decoded,
            (decoded.width - side) / 2,
            (decoded.height - side) / 2,
            side,
            side
        )
        return Bitmap.createScaledBitmap(square, OverlayConfig.OVERLAY_SIZE, OverlayConfig.OVERLAY_SIZE, true)
    }

    private fun insertPendingOutput(fileName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, OverlayConfig.OUTPUT_FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return appContext.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw ExportException("Cannot create the output file. Check available storage.")
    }

    private fun finalizePendingOutput(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        appContext.contentResolver.update(uri, values, null, null)
    }

    private fun queryDisplayName(uri: Uri): String? =
        appContext.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    companion object {
        // Key used by MediaExtractor for track rotation metadata.
        private const val ROTATION_KEY = "rotation-degrees"
    }
}
