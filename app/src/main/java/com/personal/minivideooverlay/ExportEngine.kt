package com.personal.minivideooverlay

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ExportException(message: String) : Exception(message)

class ExportEngine(private val appContext: Context) {
    data class Result(val uri: Uri, val fileName: String, val hasAudio: Boolean)

    @Volatile private var cancelled = false
    @Volatile var state: ExportState = ExportState.Idle
        private set
    private val frameSignal = Semaphore(0)
    private var videoEncoder: MediaCodec? = null
    private var videoMuxer: MediaMuxer? = null
    private var videoTrack = -1
    private var videoMuxerStarted = false
    private val encoderInfo = MediaCodec.BufferInfo()

    fun cancel() { cancelled = true }

    fun export(videoUri: Uri, pngUri: Uri, musicUri: Uri?, videoVolume: Int, musicVolume: Int,
               onProgress: (Int, String) -> Unit): Result {
        val resolver = appContext.contentResolver
        state = ExportState.Validating
        var overlay: Bitmap? = null
        val videoTemp = File.createTempFile("mvo_video_", ".mp4", appContext.cacheDir)
        var audioTemp: File? = null
        var outputUri: Uri? = null
        var success = false
        var fileName = "MiniVideoOverlay_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.mp4"
        var inputPfd: ParcelFileDescriptor? = null
        var decoderSurface: Surface? = null
        var surfaceTexture: SurfaceTexture? = null
        var compositor: GlCompositor? = null
        var encoderSurface: Surface? = null
        val extractor = MediaExtractor()
        try {
            overlay = decodeCanvasOverlay(pngUri)
            checkCancelled()
            state = ExportState.Preparing
            onProgress(1, "Memvalidasi video dan menyiapkan encoder…")
            inputPfd = resolver.openFileDescriptor(videoUri, "r") ?: throw ExportException("Video tidak dapat dibuka.")
            extractor.setDataSource(inputPfd.fileDescriptor)
            val videoIndex = findTrack(extractor, "video/") ?: throw ExportException("File yang dipilih tidak memiliki track video.")
            extractor.selectTrack(videoIndex)
            val sourceFormat = extractor.getTrackFormat(videoIndex)
            val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: throw ExportException("Codec video sumber tidak dikenali.")
            val durationUs = if (sourceFormat.containsKey(MediaFormat.KEY_DURATION)) sourceFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0L) throw ExportException("Durasi video tidak tersedia atau metadata rusak.")
            val sourceW = sourceFormat.getInteger(MediaFormat.KEY_WIDTH)
            val sourceH = sourceFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (sourceFormat.containsKey(ROTATION_KEY)) sourceFormat.getInteger(ROTATION_KEY) else 0
            val normalizedRotation = ((rotation % 360) + 360) % 360
            val swapped = normalizedRotation == 90 || normalizedRotation == 270
            val displayW = if (swapped) sourceH else sourceW
            val displayH = if (swapped) sourceW else sourceH
            val renderedHeightRatio = (OverlayConfig.OUTPUT_WIDTH.toFloat() / displayW.toFloat() * displayH.toFloat()) / OverlayConfig.OUTPUT_HEIGHT.toFloat()
            val sourceFps = runCatching { sourceFormat.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                ?.takeIf { it > 0 }?.coerceAtMost(OverlayConfig.MAX_FRAME_RATE) ?: OverlayConfig.DEFAULT_FRAME_RATE
            val fps = chooseSupportedFrameRate(sourceFps)

            onProgress(3, if (fps == sourceFps) "Menyiapkan komposisi visual 1920×1080…" else "Encoder tidak mendukung ${sourceFps} FPS; fallback terdokumentasi ke ${fps} FPS…")
            val encoder = createVideoEncoder(fps)
            videoEncoder = encoder
            encoderSurface = encoder.createInputSurface()
            encoder.start()
            videoTemp.delete()
            videoMuxer = MediaMuxer(videoTemp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            compositor = GlCompositor(encoderSurface!!, overlay!!, renderedHeightRatio, normalizedRotation)
            overlay.recycle(); overlay = null
            val texture = SurfaceTexture(compositor.videoTextureId)
            texture.setDefaultBufferSize(sourceW, sourceH)
            surfaceTexture = texture
            texture.setOnFrameAvailableListener { frameSignal.release() }
            decoderSurface = Surface(texture)

            state = ExportState.Exporting
            onProgress(5, "Merender video dan PNG…")
            renderVideo(extractor, sourceMime, sourceFormat, decoderSurface!!, texture, compositor!!, durationUs) { pts ->
                onProgress((5 + (pts * 72L / durationUs).toInt()).coerceIn(5, 77), "Merender frame video…")
            }
            encoder.signalEndOfInputStream()
            drainVideoEncoder(true)
            if (!videoMuxerStarted) throw ExportException("Encoder H.264 tidak menghasilkan video. Codec sumber mungkin tidak didukung.")
            videoMuxer?.stop()
            videoMuxerStarted = false

            runCatching { decoderSurface?.release() }; decoderSurface = null
            runCatching { surfaceTexture?.release() }; surfaceTexture = null
            runCatching { compositor?.release() }; compositor = null
            runCatching { encoderSurface?.release() }; encoderSurface = null
            runCatching { encoder.stop() }; runCatching { encoder.release() }; videoEncoder = null
            runCatching { videoMuxer?.release() }; videoMuxer = null
            runCatching { extractor.release() }
            runCatching { inputPfd?.close() }; inputPfd = null

            checkCancelled()
            state = ExportState.Finalizing
            onProgress(79, "Memproses audio dan musik…")
            val audio = AudioPipeline(appContext) { cancelled }.createMixedTrack(
                videoUri, musicUri, durationUs, videoVolume / 100f, musicVolume / 100f
            )
            audioTemp = audio.file
            val hasOutputAudio = audio.file != null
            if (!hasOutputAudio) onProgress(92, "Tidak ada sumber audio; output akan berisi video tanpa track audio.")
            checkCancelled()
            onProgress(94, "Menggabungkan track MP4…")
            fileName = "MiniVideoOverlay_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.mp4"
            val createdOutputUri = insertPendingOutput(fileName)
            outputUri = createdOutputUri
            muxFinalTracks(videoTemp, audioTemp, createdOutputUri)
            checkCancelled()
            finalizePendingOutput(createdOutputUri)
            success = true
            state = ExportState.Completed
            onProgress(100, if (hasOutputAudio) "Ekspor selesai." else "Ekspor selesai tanpa audio.")
            return Result(createdOutputUri, fileName, hasOutputAudio)
        } catch (e: ExportException) {
            state = if (cancelled || e.message?.contains("dibatalkan", ignoreCase = true) == true) ExportState.Cancelled else ExportState.Failed
            throw e
        } catch (t: Throwable) {
            state = ExportState.Failed
            throw ExportException("Ekspor gagal pada proses ${t.javaClass.simpleName}: ${t.message ?: "kesalahan tidak diketahui"}")
        } finally {
            runCatching { overlay?.recycle() }
            runCatching { decoderSurface?.release() }
            runCatching { surfaceTexture?.release() }
            runCatching { compositor?.release() }
            runCatching { encoderSurface?.release() }
            runCatching { videoEncoder?.stop() }; runCatching { videoEncoder?.release() }
            if (videoMuxerStarted) runCatching { videoMuxer?.stop() }
            runCatching { videoMuxer?.release() }
            runCatching { extractor.release() }; runCatching { inputPfd?.close() }
            if (!success && outputUri != null) runCatching { resolver.delete(outputUri!!, null, null) }
            videoTemp.delete(); audioTemp?.delete()
        }
    }

    private fun chooseSupportedFrameRate(sourceFps: Int): Int {
        val probe = try { MediaCodec.createEncoderByType(OverlayConfig.MIME_AVC) }
        catch (t: Throwable) { throw ExportException("Encoder H.264 tidak tersedia: ${t.message}") }
        try {
            val caps = probe.codecInfo.getCapabilitiesForType(OverlayConfig.MIME_AVC).videoCapabilities
            val candidates = listOf(sourceFps, min(sourceFps, 30), min(sourceFps, 24), min(sourceFps, 20), min(sourceFps, 15)).distinct()
            return candidates.firstOrNull { it > 0 && caps.areSizeAndRateSupported(OverlayConfig.OUTPUT_WIDTH, OverlayConfig.OUTPUT_HEIGHT, it.toDouble()) }
                ?: throw ExportException("Perangkat tidak mendukung encoder H.264 1920×1080 pada frame rate yang aman.")
        } finally { runCatching { probe.release() } }
    }

    private fun createVideoEncoder(frameRate: Int): MediaCodec {
        val codec = MediaCodec.createEncoderByType(OverlayConfig.MIME_AVC)
        try {
            val caps = codec.codecInfo.getCapabilitiesForType(OverlayConfig.MIME_AVC).videoCapabilities
            if (!caps.areSizeAndRateSupported(OverlayConfig.OUTPUT_WIDTH, OverlayConfig.OUTPUT_HEIGHT, frameRate.toDouble())) {
                throw ExportException("Encoder H.264 perangkat ini tidak mendukung 1920×1080 pada ${frameRate} FPS.")
            }
            val supportedBitrate = OverlayConfig.VIDEO_BITRATE.coerceIn(caps.bitrateRange.lower, caps.bitrateRange.upper)
            val format = MediaFormat.createVideoFormat(OverlayConfig.MIME_AVC, OverlayConfig.OUTPUT_WIDTH, OverlayConfig.OUTPUT_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, supportedBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return codec
        } catch (t: Throwable) {
            runCatching { codec.release() }
            if (t is ExportException) throw t
            throw ExportException("Encoder H.264 gagal dikonfigurasi: ${t.message}")
        }
    }

    private fun renderVideo(extractor: MediaExtractor, mime: String, format: MediaFormat,
                            surface: Surface, texture: SurfaceTexture, compositor: GlCompositor,
                            durationUs: Long, progress: (Long) -> Unit) {
        val decoder = try { MediaCodec.createDecoderByType(mime) }
        catch (t: Throwable) { throw ExportException("Decoder video $mime tidak tersedia: ${t.message}") }
        val matrix = FloatArray(16)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var firstPts = -1L
        var lastPts = -1L
        var frames = 0
        try {
            decoder.configure(format, surface, null, 0)
            decoder.start()
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (!outputDone) {
                checkCancelled()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(OverlayConfig.CODEC_TIMEOUT_US)
                    if (index >= 0) {
                        val input = decoder.getInputBuffer(index) ?: throw ExportException("Buffer input decoder video tidak tersedia.")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0L), extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = decoder.dequeueOutputBuffer(info, OverlayConfig.CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outIndex >= 0) {
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (info.size > 0) {
                            if (firstPts < 0L) firstPts = info.presentationTimeUs
                            val pts = (info.presentationTimeUs - firstPts).coerceAtLeast(0L)
                            if (pts < durationUs) {
                                decoder.releaseOutputBuffer(outIndex, true)
                                if (!frameSignal.tryAcquire(OverlayConfig.FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw ExportException("Timeout saat menunggu frame hasil decode.")
                                texture.updateTexImage(); texture.getTransformMatrix(matrix)
                                val safePts = max(pts, lastPts + 1L)
                                compositor.drawFrame(matrix, safePts * 1000L)
                                drainVideoEncoder(false)
                                lastPts = safePts; frames++
                                progress(pts)
                            } else decoder.releaseOutputBuffer(outIndex, false)
                        } else decoder.releaseOutputBuffer(outIndex, false)
                        if (eos) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }; runCatching { decoder.release() }
        }
        if (frames == 0) throw ExportException("Tidak ada frame video yang berhasil didekode.")
    }

    private fun drainVideoEncoder(endOfStream: Boolean) {
        val encoder = videoEncoder ?: return
        val muxer = videoMuxer ?: return
        var idle = 0
        while (true) {
            checkCancelled()
            val index = encoder.dequeueOutputBuffer(encoderInfo, if (endOfStream) OverlayConfig.CODEC_TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idle > OverlayConfig.ENCODER_DRAIN_MAX_IDLE) throw ExportException("Encoder video berhenti merespons saat finalisasi.")
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (videoMuxerStarted) throw ExportException("Format encoder video berubah setelah muxer dimulai.")
                    videoTrack = muxer.addTrack(encoder.outputFormat); muxer.start(); videoMuxerStarted = true
                }
                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index) ?: throw ExportException("Buffer output encoder video tidak tersedia.")
                    if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) encoderInfo.size = 0
                    if (encoderInfo.size > 0) {
                        if (!videoMuxerStarted) throw ExportException("Sample video diterima sebelum format muxer siap.")
                        buffer.position(encoderInfo.offset); buffer.limit(encoderInfo.offset + encoderInfo.size)
                        muxer.writeSampleData(videoTrack, buffer, encoderInfo)
                    }
                    val eos = (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder.releaseOutputBuffer(index, false)
                    if (eos) return
                }
            }
        }
    }

    private fun muxFinalTracks(videoFile: File, audioFile: File?, outputUri: Uri) {
        val resolver = appContext.contentResolver
        val out = resolver.openFileDescriptor(outputUri, "rw") ?: throw ExportException("File output tidak dapat dibuka.")
        val video = MediaExtractor()
        val audio = if (audioFile != null) MediaExtractor() else null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            video.setDataSource(videoFile.absolutePath)
            val vi = findTrack(video, "video/") ?: throw ExportException("Track video sementara tidak ditemukan.")
            video.selectTrack(vi)
            var ai = -1
            if (audio != null) {
                audio.setDataSource(audioFile!!.absolutePath)
                ai = findTrack(audio, "audio/") ?: throw ExportException("Track AAC hasil mixing tidak ditemukan.")
                audio.selectTrack(ai)
            }
            muxer = MediaMuxer(out.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideoTrack = muxer.addTrack(video.getTrackFormat(vi))
            val outAudioTrack = if (audio != null) muxer.addTrack(audio.getTrackFormat(ai)) else -1
            muxer.start(); started = true
            copyTrack(video, muxer, outVideoTrack, vi)
            if (audio != null) copyTrack(audio, muxer, outAudioTrack, ai)
            muxer.stop(); started = false
        } catch (t: Throwable) {
            if (t is ExportException) throw t
            throw ExportException("Gagal menggabungkan audio/video ke MP4: ${t.message}")
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }; runCatching { video.release() }; runCatching { audio?.release() }; runCatching { out.close() }
        }
    }

    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, outputTrack: Int, sourceTrack: Int) {
        val maxSize = (extractor.getTrackFormat(sourceTrack).let {
            if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1 shl 20
        }).coerceAtLeast(64 * 1024)
        val buffer = java.nio.ByteBuffer.allocateDirect(maxSize)
        val info = MediaCodec.BufferInfo()
        while (true) {
            checkCancelled()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            if (size == 0) { extractor.advance(); continue }
            info.set(0, size, extractor.sampleTime.coerceAtLeast(0L), extractor.sampleFlags)
            buffer.position(0); buffer.limit(size)
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
    }

    private fun decodeCanvasOverlay(uri: Uri): Bitmap {
        val signature = appContext.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArray(8); var read = 0
            while (read < bytes.size) { val count = input.read(bytes, read, bytes.size - read); if (count < 0) break; read += count }
            if (read != bytes.size) throw ExportException("File PNG terlalu pendek atau rusak.")
            bytes
        } ?: throw ExportException("File PNG tidak dapat dibuka.")
        val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (!signature.contentEquals(pngSignature)) throw ExportException("File yang dipilih bukan PNG asli. Pilih file PNG.")
        val decoded = try {
            val source = ImageDecoder.createSource(appContext.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width.coerceAtLeast(1)
                val h = info.size.height.coerceAtLeast(1)
                if (w > 12000 || h > 12000) throw ExportException("Dimensi PNG terlalu besar; maksimum 12000×12000.")
                val scale = min(OverlayConfig.OUTPUT_WIDTH.toFloat() / w, OverlayConfig.OUTPUT_HEIGHT.toFloat() / h)
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: ExportException) { throw e }
          catch (t: Throwable) { throw ExportException("PNG tidak dapat dibaca: ${t.message}") }
        if (!decoded.hasAlpha()) {
            decoded.recycle()
            throw ExportException("PNG yang dipilih tidak memiliki kanal alpha/transparansi. Pilih PNG transparan.")
        }
        val canvasBitmap = Bitmap.createBitmap(OverlayConfig.OUTPUT_WIDTH, OverlayConfig.OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.TRANSPARENT)
        val scale = min(OverlayConfig.OUTPUT_WIDTH.toFloat() / decoded.width, OverlayConfig.OUTPUT_HEIGHT.toFloat() / decoded.height)
        val w = decoded.width * scale; val h = decoded.height * scale
        val dst = RectF((OverlayConfig.OUTPUT_WIDTH - w) / 2f, (OverlayConfig.OUTPUT_HEIGHT - h) / 2f,
            (OverlayConfig.OUTPUT_WIDTH + w) / 2f, (OverlayConfig.OUTPUT_HEIGHT + h) / 2f)
        canvas.drawBitmap(decoded, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        decoded.recycle()
        return canvasBitmap
    }

    private fun insertPendingOutput(name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, OverlayConfig.OUTPUT_FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return appContext.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw ExportException("Penyimpanan gagal dibuat. Periksa ruang kosong perangkat.")
    }

    private fun finalizePendingOutput(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        if (appContext.contentResolver.update(uri, values, null, null) <= 0) throw ExportException("File selesai ditulis tetapi gagal dipublikasikan di MediaStore.")
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    private fun checkCancelled() { if (cancelled) throw ExportException("Ekspor dibatalkan oleh pengguna.") }

    companion object { private const val ROTATION_KEY = "rotation-degrees" }
}
