package com.personal.minivideooverlay

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Bounded-memory offline PCM decode, gain/mix, AAC encode. Temporary PCM is stored in app cache. */
class AudioPipeline(private val context: Context, private val isCancelled: () -> Boolean) {
    data class EncodedAudio(val file: File?, val durationUs: Long)

    fun createMixedTrack(videoUri: Uri, musicUri: Uri?, durationUs: Long,
                         videoGain: Float, musicGain: Float): EncodedAudio {
        val videoPcm = File.createTempFile("mvo_video_", ".pcm", context.cacheDir)
        val musicPcm = if (musicUri != null) File.createTempFile("mvo_music_", ".pcm", context.cacheDir) else null
        val mixedMp4 = File.createTempFile("mvo_audio_", ".mp4", context.cacheDir)
        try {
            val hasVideoAudio = decodeToCanonicalPcm(videoUri, videoPcm, optional = true, maxDurationUs = durationUs, preserveInitialTimestamp = true)
            val hasMusic = if (musicUri != null && musicPcm != null) decodeToCanonicalPcm(musicUri, musicPcm, optional = false, maxDurationUs = durationUs, preserveInitialTimestamp = false) else false
            if (!hasVideoAudio && !hasMusic) {
                mixedMp4.delete()
                return EncodedAudio(null, durationUs)
            }
            mixAndEncode(videoPcm.takeIf { hasVideoAudio }, musicPcm?.takeIf { hasMusic }, mixedMp4,
                durationUs, videoGain.coerceIn(0f, 1f), musicGain.coerceIn(0f, 1f))
            return EncodedAudio(mixedMp4, durationUs)
        } catch (t: Throwable) {
            mixedMp4.delete()
            throw t
        } finally {
            videoPcm.delete()
            musicPcm?.delete()
        }
    }

    /** Decodes one audio track to canonical 48 kHz stereo signed-16 PCM, in bounded chunks. */
    private fun decodeToCanonicalPcm(uri: Uri, destination: File, optional: Boolean, maxDurationUs: Long, preserveInitialTimestamp: Boolean): Boolean {
        var pfd: ParcelFileDescriptor? = null
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var found = false
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw ExportException("File audio tidak dapat dibuka.")
            extractor.setDataSource(pfd.fileDescriptor)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; break }
            }
            if (track < 0) {
                if (optional) return false
                throw ExportException("File musik tidak memiliki audio track yang dapat dibaca.")
            }
            found = true
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw ExportException("Format audio tidak dikenali.")
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            var inputDone = false
            var outputDone = false
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = 2 // Android ENCODING_PCM_16BIT default
            var inputFrameIndex = 0L
            var outputFrameIndex = 0L
            var resampledFrameIndex = 0L
            var previousLeft = 0f
            var previousRight = 0f
            var firstAudioPtsUs = -1L
            RandomAccessFile(destination, "rw").use { out ->
                out.setLength(0)
                val info = MediaCodec.BufferInfo()
                var idleOutputLoops = 0
                val maxFrames = (maxDurationUs * OverlayConfig.AUDIO_SAMPLE_RATE / 1_000_000L).coerceAtLeast(1L)
                while (!outputDone && outputFrameIndex < maxFrames) {
                    checkCancel()
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(OverlayConfig.CODEC_TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buffer = decoder.getInputBuffer(inIndex) ?: throw ExportException("Audio decoder input buffer tidak tersedia.")
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outIndex = decoder.dequeueOutputBuffer(info, OverlayConfig.CODEC_TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = decoder.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmEncoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
                            if (pcmEncoding != 2 && pcmEncoding != 4) throw ExportException("Decoder menghasilkan PCM dengan encoding $pcmEncoding yang belum didukung.")
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (inputDone && ++idleOutputLoops > 500) throw ExportException("Audio decoder tidak menyelesaikan stream setelah EOS.")
                        }
                        else -> if (outIndex >= 0) {
                            idleOutputLoops = 0
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            if (info.size > 0) {
                                if (firstAudioPtsUs < 0L) {
                                    firstAudioPtsUs = info.presentationTimeUs
                                    val leadingSilence = if (preserveInitialTimestamp) min(maxFrames, max(0L, firstAudioPtsUs) * OverlayConfig.AUDIO_SAMPLE_RATE / 1_000_000L) else 0L
                                    var silenceLeft = leadingSilence
                                    val silenceChunk = ByteArray(16 * 1024)
                                    while (silenceLeft > 0) {
                                        val frames = min(silenceLeft, (silenceChunk.size / 4).toLong()).toInt()
                                        out.write(silenceChunk, 0, frames * 4)
                                        outputFrameIndex += frames
                                        silenceLeft -= frames
                                    }
                                }
                                val buffer = decoder.getOutputBuffer(outIndex) ?: throw ExportException("Audio decoder output buffer tidak tersedia.")
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val pcm = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                                val bytesPerSample = if (pcmEncoding == 4) 4 else 2
                                val frames = info.size / (bytesPerSample * channels)
                                for (frame in 0 until frames) {
                                    val base = frame * channels * bytesPerSample
                                    fun sampleAt(channel: Int): Float {
                                        val offset = base + channel * bytesPerSample
                                        return if (pcmEncoding == 4) pcm.getFloat(offset).coerceIn(-1f, 1f)
                                        else pcm.getShort(offset).toFloat() / 32768f
                                    }
                                    val c0 = sampleAt(0)
                                    val c1 = if (channels > 1) sampleAt(1) else c0
                                    val c2 = if (channels > 2) sampleAt(2) else 0f
                                    val c4 = if (channels > 4) sampleAt(4) else 0f
                                    val c5 = if (channels > 5) sampleAt(5) else 0f
                                    val c6 = if (channels > 6) sampleAt(6) else 0f
                                    val c7 = if (channels > 7) sampleAt(7) else 0f
                                    val left = if (channels <= 2) c0 else (c0 + c2 * 0.7071f + c4 * 0.7071f + c6 * 0.5f).coerceIn(-1f, 1f)
                                    val right = if (channels <= 2) c1 else (c1 + c2 * 0.7071f + c5 * 0.7071f + c7 * 0.5f).coerceIn(-1f, 1f)
                                    // Linear interpolation resampling into the fixed 48 kHz stereo timeline.
                                    while (outputFrameIndex < maxFrames && (resampledFrameIndex.toDouble() * sampleRate / OverlayConfig.AUDIO_SAMPLE_RATE) <= inputFrameIndex.toDouble()) {
                                        val sourcePosition = resampledFrameIndex.toDouble() * sampleRate / OverlayConfig.AUDIO_SAMPLE_RATE
                                        val fraction = if (inputFrameIndex == 0L) 0f else (sourcePosition - (inputFrameIndex - 1L)).toFloat().coerceIn(0f, 1f)
                                        val mixedLeft = if (inputFrameIndex == 0L) left else previousLeft + (left - previousLeft) * fraction
                                        val mixedRight = if (inputFrameIndex == 0L) right else previousRight + (right - previousRight) * fraction
                                        val l = (mixedLeft.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                        val r = (mixedRight.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                        out.write(l.toInt() and 0xff); out.write((l.toInt() shr 8) and 0xff)
                                        out.write(r.toInt() and 0xff); out.write((r.toInt() shr 8) and 0xff)
                                        outputFrameIndex++
                                        resampledFrameIndex++
                                    }
                                    previousLeft = left
                                    previousRight = right
                                    inputFrameIndex++
                                }
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (eos) outputDone = true
                        }
                    }
                }
                if (outputFrameIndex == 0L) throw ExportException("Audio tidak menghasilkan sampel PCM.")
                out.setLength(min(outputFrameIndex, maxFrames) * 4L)
            }
            return true
        } catch (e: ExportException) {
            if (optional && !found) return false
            throw e
        } catch (t: Throwable) {
            if (optional && !found) return false
            throw ExportException("Gagal membaca/decode audio: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { extractor.release() }; runCatching { pfd?.close() }
        }
    }

    private fun mixAndEncode(videoPcm: File?, musicPcm: File?, output: File,
                             durationUs: Long, videoGain: Float, musicGain: Float) {
        val totalFrames = ((durationUs * OverlayConfig.AUDIO_SAMPLE_RATE) / 1_000_000L).coerceAtLeast(1L)
        val video = videoPcm?.let { RandomAccessFile(it, "r") }
        val music = musicPcm?.let { RandomAccessFile(it, "r") }
        val videoFrames = video?.length()?.div(4L) ?: 0L
        val musicFrames = music?.length()?.div(4L) ?: 0L
        if (music != null && musicFrames <= 0) throw ExportException("File musik tidak memiliki durasi audio yang valid.")

        val encoder = MediaCodec.createEncoderByType(OverlayConfig.MIME_AAC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var track = -1
        var encoderStarted = false
        try {
            val format = MediaFormat.createAudioFormat(OverlayConfig.MIME_AAC, OverlayConfig.AUDIO_SAMPLE_RATE, OverlayConfig.AUDIO_CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, OverlayConfig.AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start(); encoderStarted = true
            output.delete()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var submittedFrames = 0L
            var inputDone = false
            var outputDone = false
            var idleOutputLoops = 0
            val chunkFrames = 1024
            while (!outputDone) {
                checkCancel()
                if (!inputDone) {
                    val inIndex = encoder.dequeueInputBuffer(OverlayConfig.CODEC_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val input = encoder.getInputBuffer(inIndex) ?: throw ExportException("AAC encoder input buffer tidak tersedia.")
                        input.clear()
                        if (submittedFrames >= totalFrames) {
                            encoder.queueInputBuffer(inIndex, 0, 0, (submittedFrames * 1_000_000L / OverlayConfig.AUDIO_SAMPLE_RATE), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val capacityFrames = input.remaining() / 4
                            if (capacityFrames <= 0) throw ExportException("Buffer AAC encoder terlalu kecil untuk PCM stereo.")
                            val count = min(min(chunkFrames.toLong(), totalFrames - submittedFrames), capacityFrames.toLong()).toInt()
                            val vBytes = readPcmChunk(video, submittedFrames, count, videoFrames, false)
                            val mBytes = readPcmChunk(music, submittedFrames, count, musicFrames, true)
                            val vb = ByteBuffer.wrap(vBytes).order(ByteOrder.LITTLE_ENDIAN)
                            val mb = ByteBuffer.wrap(mBytes).order(ByteOrder.LITTLE_ENDIAN)
                            val out = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
                            repeat(count * 2) {
                                val a = if (video != null) vb.getShort().toFloat() / 32768f * videoGain else 0f
                                val b = if (music != null) mb.getShort().toFloat() / 32768f * musicGain else 0f
                                val sum = a + b
                                val safe = if (abs(sum) <= 0.98f) sum else (if (sum < 0) -1f else 1f) * (0.98f + 0.02f * kotlin.math.tanh(((abs(sum) - 0.98f) / 0.02f).toDouble()).toFloat())
                                out.putShort((safe.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                            }
                            out.flip(); input.put(out)
                            val pts = submittedFrames * 1_000_000L / OverlayConfig.AUDIO_SAMPLE_RATE
                            encoder.queueInputBuffer(inIndex, 0, count * 4, pts, 0)
                            submittedFrames += count
                        }
                    }
                }
                val outIndex = encoder.dequeueOutputBuffer(info, OverlayConfig.CODEC_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone && ++idleOutputLoops > 500) throw ExportException("AAC encoder tidak menyelesaikan stream setelah EOS.")
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        idleOutputLoops = 0
                        if (muxerStarted) throw ExportException("AAC encoder mengubah format lebih dari sekali.")
                        track = muxer.addTrack(encoder.outputFormat); muxer.start(); muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        idleOutputLoops = 0
                        val buffer = encoder.getOutputBuffer(outIndex) ?: throw ExportException("AAC encoder output buffer tidak tersedia.")
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (info.size > 0) {
                            if (!muxerStarted) throw ExportException("AAC sample diterima sebelum format muxer siap.")
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (eos) outputDone = true
                    }
                }
            }
            if (!muxerStarted) throw ExportException("AAC encoder tidak menghasilkan format output.")
            muxer.stop()
            muxerStarted = false
        } finally {
            runCatching { video?.close() }; runCatching { music?.close() }
            if (encoderStarted) runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun readPcmChunk(file: RandomAccessFile?, startFrame: Long, count: Int, availableFrames: Long, loop: Boolean): ByteArray {
        val result = ByteArray(count * 4)
        if (file == null || availableFrames <= 0) return result
        var writtenFrames = 0
        while (writtenFrames < count) {
            val absolute = startFrame + writtenFrames
            if (!loop && absolute >= availableFrames) break
            val frame = if (loop) absolute % availableFrames else absolute
            val amount = if (loop) min((count - writtenFrames).toLong(), availableFrames - frame).toInt()
                else min((count - writtenFrames).toLong(), availableFrames - frame).toInt()
            file.seek(frame * 4L)
            file.readFully(result, writtenFrames * 4, amount * 4)
            writtenFrames += amount
        }
        return result
    }

    private fun checkCancel() { if (isCancelled()) throw ExportException("Ekspor dibatalkan.") }
}
