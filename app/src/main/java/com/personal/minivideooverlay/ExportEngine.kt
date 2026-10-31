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
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class ExportException(message: String) : Exception(message)

/**
 * One-shot export pipeline. Create a new instance for every export; do not reuse it.
 *
 *   video file -> MediaCodec decoder -> SurfaceTexture (OES) -> GlCompositor
 *       -> encoder input Surface -> MediaCodec H.264 encoder -> MediaMuxer (MP4)
 *   audio track -> copied packet-by-packet (no re-encode), interleaved by timestamp
 *
 * The output is written into MediaStore with IS_PENDING set, so a partial file never
 * shows up as a finished video. On failure the pending entry is deleted.
 */
class ExportEngine(private val appContext: Context) {

    class Result(val uri: Uri, val fileName: String)

    @Volatile private var cancelled = false
    private val frameSignal = Semaphore(0)
    private val encoderInfo = MediaCodec.BufferInfo()

    // Encoder/muxer state shared by the drain helpers. Set during export().
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var audioExtractor: MediaExtractor? = null
    private var audioFormat: MediaFormat? = null
    private var audioBuffer: ByteBuffer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false

    /** Requests cancellation. The export stops at the next frame and the output is deleted. */
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
        var videoPfd: ParcelFileDescriptor? = null
        var audioPfd: ParcelFileDescriptor? = null
        var outPfd: ParcelFileDescriptor? = null
        val videoExtractor = MediaExtractor()
        val audioEx = MediaExtractor()
        var decoder: MediaCodec? = null
        var decoderSurface: Surface? = null
        var surfaceTexture: SurfaceTexture? = null
        var compositor: GlCompositor? = null
        var resultName = fileName

        try {
            outPfd = resolver.openFileDescriptor(outUri, "rw")
                ?: throw ExportException("Cannot open the output file")

            // ----- Input video -----
            videoPfd = resolver.openFileDescriptor(videoUri, "r")
                ?: throw ExportException("Cannot open the selected video")
            videoExtractor.setDataSource(videoPfd.fileDescriptor)
            val videoIndex = findTrack(videoExtractor, "video/")
                ?: throw ExportException("No video track found in the selected file")
            videoExtractor.selectTrack(videoIndex)
            val srcFormat = videoExtractor.getTrackFormat(videoIndex)
            val srcMime = srcFormat.getString(MediaFormat.KEY_MIME)
                ?: throw ExportException("Unknown video format")
            val durationUs = if (srcFormat.containsKey(MediaFormat.KEY_DURATION))
                srcFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0L) throw ExportException("Video duration could not be read")

            val frameRate = runCatching { srcFormat.getInteger(MediaFormat.KEY_FRAME_RATE) }
                .getOrNull()?.coerceIn(1, OverlayConfig.MAX_FRAME_RATE)
                ?: OverlayConfig.DEFAULT_FRAME_RATE
            val frameDurUs = 1_000_000L / frameRate

            // Aspect ratio after rotation metadata, used for the center-crop.
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

            // ----- Encoder -----
            val enc = MediaCodec.createEncoderByType(OverlayConfig.MIME_AVC)
            encoder = enc
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

            // ----- Muxer -----
            muxer = MediaMuxer(outPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // ----- Optional audio (copied, not re-encoded) -----
            audioPfd = resolver.openFileDescriptor(videoUri, "r")
                ?: throw ExportException("Cannot reopen the selected video")
            audioEx.setDataSource(audioPfd.fileDescriptor)
            val audioIndex = findTrack(audioEx, "audio/")
            if (audioIndex != null) {
                audioEx.selectTrack(audioIndex)
                audioFormat = audioEx.getTrackFormat(audioIndex)
                audioBuffer = ByteBuffer.allocateDirect(OverlayConfig.AUDIO_BUFFER_BYTES)
                audioExtractor = audioEx
            }

            // ----- GL compositor and decoder -----
            val comp = GlCompositor(encSurface, overlay)
            compositor = comp
            overlay.recycle()

            val texture = SurfaceTexture(comp.videoTextureId)
            surfaceTexture = texture
            texture.setOnFrameAvailableListener { frameSignal.release() }
            val dSurface = Surface(texture)
            decoderSurface = dSurface

            val dec = MediaCodec.createDecoderByType(srcMime)
            decoder = dec
            dec.configure(srcFormat, dSurface, null, 0)
            dec.start()

            // ----- Main loop: decode -> render -> encode -----
            val stMatrix = FloatArray(16)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastPtsUs = 0L

            while (!outputDone) {
                checkCancelled()

                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(OverlayConfig.CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)
                            ?: throw ExportException("Decoder input buffer unavailable")
                        val size = videoExtractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, videoExtractor.sampleTime, 0)
                            videoExtractor.advance()
                        }
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(info, OverlayConfig.CODEC_TIMEOUT_US)
                if (outIdx >= 0) {
                    val hasFrame = info.size > 0
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    dec.releaseOutputBuffer(outIdx, hasFrame)
                    if (hasFrame) {
                        awaitFrame()
                        texture.updateTexImage()
                        texture.getTransformMatrix(stMatrix)
                        val ptsNs = texture.timestamp
                        comp.drawFrame(stMatrix, cropX, cropY, ptsNs)
                        lastPtsUs = ptsNs / 1000L
                        drainEncoder(endOfStream = false)
                        onProgress(progressOf(lastPtsUs, durationUs))
                    }
                    if (eos) outputDone = true
                }
            }

            // ----- Finish -----
            enc.signalEndOfInputStream()
            drainEncoder(endOfStream = true)
            drainAudioUntil(lastPtsUs + frameDurUs)

            if (!muxerStarted) throw ExportException("Encoder produced no output. The video may be unsupported.")
            muxer?.stop()
            success = true
            resultName = queryDisplayName(outUri) ?: fileName
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { decoderSurface?.release() }
            runCatching { surfaceTexture?.release() }
            runCatching { compositor?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioEx.release() }
            runCatching { videoPfd?.close() }
            runCatching { audioPfd?.close() }
            runCatching { outPfd?.close() }
            encoder = null
            muxer = null
            audioExtractor = null
            audioBuffer = null
            muxerStarted = false

            if (success) {
                finalizePendingOutput(outUri)
            } else {
                runCatching { resolver.delete(outUri, null, null) }
            }
        }

        return Result(outUri, resultName)
    }

    // ------------------------------------------------------------------
    // Encoder drain and audio interleave
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
                    audioFormat?.let { audioTrack = mux.addTrack(it) }
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
                        drainAudioUntil(encoderInfo.presentationTimeUs)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    /** Writes audio packets whose timestamp is at or before [limitUs]. */
    private fun drainAudioUntil(limitUs: Long) {
        val ex = audioExtractor ?: return
        val buf = audioBuffer ?: return
        val mux = muxer ?: return
        if (audioTrack < 0 || !muxerStarted) return
        val info = MediaCodec.BufferInfo()
        while (ex.sampleTrackIndex >= 0 && ex.sampleTime <= limitUs) {
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            buf.position(0)
            buf.limit(size)
            val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            info.set(0, size, ex.sampleTime, flags)
            mux.writeSampleData(audioTrack, buf, info)
            ex.advance()
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

    private fun progressOf(ptsUs: Long, durationUs: Long): Int =
        (ptsUs * 100L / durationUs).toInt().coerceIn(0, 99)

    private fun findTrack(ex: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return null
    }

    /**
     * Decodes the photo (EXIF orientation applied), center-crops to a square,
     * and scales it to OVERLAY_SIZE. No stretching.
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
