package com.deanwaylabs.deanwave.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import com.deanwaylabs.deanwave.dsp.SoundEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 [AudioProcessor] that runs DeanWave's custom DSP sound engine over
 * decoded PCM.
 *
 * This is the "better than Android's Equalizer" part: the app processes
 * samples itself (parametric EQ, bass boost, limiter) instead of using the
 * system's fixed-band effect. Because it sits in the Media3 audio chain,
 * every source — Drive streams, local files, online streams — passes through
 * the same engine on playback.
 *
 * Why a Media3 AudioProcessor and not Oboe (documented choice):
 * playback already runs through Media3/ExoPlayer for every source, so the
 * processor keeps one pipeline for decoding, streaming, and DSP. Oboe would
 * mean a second native audio path plus reimplementing decoding and
 * streaming. Oboe stays an option only if ultra-low-latency monitoring is
 * ever needed, which a music player does not need.
 *
 * Supported input encodings: 16-bit PCM (the ExoPlayer default), 24-bit PCM,
 * 32-bit PCM, and float PCM. The output encoding always matches the input,
 * so 24-bit lossless stays 24-bit through the engine — and float stays
 * float, which carries 24-bit integer samples exactly (float's 24-bit
 * mantissa). One precision note: the DSP works in float internally, so
 * 32-bit int samples are float-rounded on the way in (inaudible LSB loss);
 * 24-bit int fits float's mantissa exactly, so it round-trips bit-exact. The DSP math itself already works on FloatArray frames
 * internally, so this upgrade is purely input/output conversion; no DSP
 * code changed for it. Anything else throws [UnhandledAudioFormatException].
 *
 * Bit-depth honesty: whether 24-bit reaches this processor depends on the
 * decoder's output on the device. PlaybackService enables float decoder
 * output (DefaultRenderersFactory.setEnableAudioFloatOutput), so decoders
 * that support it deliver float, and the processor keeps it float. The
 * Bluetooth ceiling still applies downstream — the phone re-encodes to the
 * earbuds' codec (AAC on the Bose buds) — but the engine itself no longer
 * narrows the audio before that point.
 */
class DeanWaveAudioProcessor(
    private val engine: SoundEngine
) : AudioProcessor {

    private var inputFormat = AudioFormat.NOT_SET
    private var outputFormat = AudioFormat.NOT_SET
    private var outputBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT -> Unit
            else -> throw UnhandledAudioFormatException(inputAudioFormat)
        }
        inputFormat = inputAudioFormat
        outputFormat = inputAudioFormat
        engine.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return outputFormat
    }

    override fun isActive(): Boolean = outputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive() || !inputBuffer.hasRemaining()) return
        val channelCount = inputFormat.channelCount
        val bytesPerSample = when (inputFormat.encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> return // configure() already rejected anything else
        }
        val bytes = inputBuffer.remaining()
        // Decoders deliver whole frames; a trailing partial frame is dropped.
        val frames = bytes / (bytesPerSample * channelCount)
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteBuffer.allocate(frames * bytesPerSample * channelCount)
            .order(ByteOrder.LITTLE_ENDIAN)
        val frame = FloatArray(channelCount)
        repeat(frames) {
            for (channel in frame.indices) {
                frame[channel] = when (inputFormat.encoding) {
                    C.ENCODING_PCM_16BIT -> input.short / 32768f
                    C.ENCODING_PCM_24BIT -> read24Bit(input) / 8388608f
                    C.ENCODING_PCM_32BIT -> input.int / 2147483648f
                    else -> input.float // ENCODING_PCM_FLOAT
                }
            }
            engine.processFrame(frame)
            for (channel in frame.indices) {
                when (inputFormat.encoding) {
                    C.ENCODING_PCM_16BIT -> {
                        val pcm = (frame[channel] * 32767f).toInt()
                            .coerceIn(-32768, 32767)
                        out.putShort(pcm.toShort())
                    }
                    C.ENCODING_PCM_24BIT -> {
                        val pcm = (frame[channel] * 8388607f).toInt()
                            .coerceIn(-8388608, 8388607)
                        write24Bit(out, pcm)
                    }
                    C.ENCODING_PCM_32BIT -> {
                        val pcm = (frame[channel].toDouble() * 2147483647.0)
                            .toInt()
                            .coerceIn(Int.MIN_VALUE, Int.MAX_VALUE)
                        out.putInt(pcm)
                    }
                    else -> out.putFloat(frame[channel]) // float passthrough
                }
            }
        }
        out.flip()
        outputBuffer = out
    }

    /** Reads one little-endian 24-bit signed sample. */
    private fun read24Bit(input: ByteBuffer): Int {
        val low = input.get().toInt() and 0xFF
        val mid = input.get().toInt() and 0xFF
        val high = input.get().toInt() // sign-extends, which is what we want
        return low or (mid shl 8) or (high shl 16)
    }

    /** Writes one little-endian 24-bit signed sample. */
    private fun write24Bit(out: ByteBuffer, value: Int) {
        out.put((value and 0xFF).toByte())
        out.put(((value shr 8) and 0xFF).toByte())
        out.put(((value shr 16) and 0xFF).toByte())
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean =
        inputEnded && outputBuffer == AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        flush()
        inputFormat = AudioFormat.NOT_SET
        outputFormat = AudioFormat.NOT_SET
    }
}
