package com.umbra.app.data.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Builds a compact waveform from decoded PCM samples of a voice message. */
object AudioWaveform {
    suspend fun extract(file: File, barCount: Int = 48): List<Float> = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0) { "Запись недоступна" }
        val bars = barCount.coerceIn(16, 96)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext emptyList()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext emptyList()
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val estimatedFrames = max(1L, durationUs * sampleRate / 1_000_000L)
            val sampleStride = max(1L, estimatedFrames / (bars * 96L))
            val sums = DoubleArray(bars)
            val counts = IntArray(bars)
            var frameIndex = 0L
            var inputDone = false
            var outputDone = false
            var idleAfterInput = 0
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()

            codec = MediaCodec.createDecoderByType(mime).also {
                it.configure(format, null, null, 0)
                it.start()
            }
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inputIndex = codec!!.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec!!.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec!!.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec!!.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec!!.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    idleAfterInput = 0
                    val output = codec!!.getOutputBuffer(outputIndex)
                    if (output != null && info.size > 1) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        output.order(ByteOrder.nativeOrder())
                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val floats = output.asFloatBuffer()
                            val frameCount = floats.remaining() / channels
                            for (frame in 0 until frameCount) {
                                if (frameIndex % sampleStride == 0L) {
                                    var peak = 0f
                                    repeat(channels) { channel ->
                                        val value = abs(floats.get(frame * channels + channel))
                                        if (value > peak) peak = value
                                    }
                                    val bucket = ((frameIndex * bars) / estimatedFrames).toInt().coerceIn(0, bars - 1)
                                    sums[bucket] += peak.coerceIn(0f, 1f).toDouble()
                                    counts[bucket]++
                                }
                                frameIndex++
                            }
                        } else {
                            val shorts = output.asShortBuffer()
                            val frameCount = shorts.remaining() / channels
                            for (frame in 0 until frameCount) {
                                if (frameIndex % sampleStride == 0L) {
                                    var peak = 0
                                    repeat(channels) { channel ->
                                        val value = abs(shorts.get(frame * channels + channel).toInt())
                                        if (value > peak) peak = value
                                    }
                                    val bucket = ((frameIndex * bars) / estimatedFrames).toInt().coerceIn(0, bars - 1)
                                    sums[bucket] += peak / 32768.0
                                    counts[bucket]++
                                }
                                frameIndex++
                            }
                        }
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec!!.releaseOutputBuffer(outputIndex, false)
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outputFormat = codec!!.outputFormat
                    pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    idleAfterInput++
                    if (idleAfterInput > 200) return@withContext emptyList()
                }
            }
            val raw = List(bars) { index ->
                if (counts[index] == 0) 0f else (sums[index] / counts[index]).toFloat()
            }
            val ceiling = raw.maxOrNull()?.coerceAtLeast(0.02f) ?: 0.02f
            raw.map { (0.12f + 0.88f * (it / ceiling).coerceIn(0f, 1f)) }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
