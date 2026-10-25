package com.deanwaylabs.deanwave.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import com.deanwaylabs.deanwave.dsp.SoundEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DeanWaveAudioProcessor : AudioProcessor {

    // Our sound engine: preamp -> 8-band parametric EQ -> bass boost -> limiter.
    // Default bands are flat (0 dB), so sound passes through unchanged until
    // bands are set or a preset is applied.
    private val engine = SoundEngine()

    private var inputFormat = AudioFormat.NOT_SET
    private var outputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        // Accept 16-bit, 24-bit, 32-bit, and Float PCM format configurations.
        // Anything else is refused so the sink can try another format.
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT -> Unit
            else -> throw UnhandledAudioFormatException(inputAudioFormat)
        }
        inputFormat = inputAudioFormat
        // Output is always float PCM: the engine works in float, and float
        // carries 24-bit integer samples exactly (24-bit mantissa).
        outputFormat = AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        engine.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return outputFormat
    }

    override fun isActive(): Boolean {
        return inputFormat != AudioFormat.NOT_SET
    }

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
        val out = ByteBuffer.allocate(frames * channelCount * 4)
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
            // Process audio bytes and route through the custom DSP chain here
            // (Preamp -> 8-band parametric EQ -> Bass Boost -> Limiter)
            engine.processFrame(frame)
            for (channel in frame.indices) {
                out.putFloat(frame[channel])
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

    override fun queueEndOfStream() {
        // Do not clear the output here: let getOutput() drain the last
        // processed buffer first, or the tail of the track gets dropped.
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean {
        return inputEnded && outputBuffer == AudioProcessor.EMPTY_BUFFER
    }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        engine.reset()
    }

    override fun reset() {
        flush()
        inputFormat = AudioFormat.NOT_SET
        outputFormat = AudioFormat.NOT_SET
    }
}
