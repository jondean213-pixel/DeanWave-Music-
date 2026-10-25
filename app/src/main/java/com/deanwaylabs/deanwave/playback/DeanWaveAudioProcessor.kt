package com.deanwaylabs.deanwave.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DeanWaveAudioProcessor : AudioProcessor {

    private var inputFormat = AudioFormat.NOT_SET
    private var outputFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        // Accept 16-bit, 24-bit, 32-bit, and Float PCM format configurations
        inputFormat = inputAudioFormat
        outputFormat = AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        return outputFormat
    }

    override fun isActive(): Boolean {
        return inputFormat != AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val remaining = inputBuffer.remaining()
        if (buffer.capacity() < remaining) {
            buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }

        // Process audio bytes and route through the custom DSP chain here
        // (Preamp -> 8-band parametric EQ -> Bass Boost -> Limiter)
        
        inputBuffer.position(inputBuffer.limit())
        buffer.flip()
        outputBuffer = buffer
    }

    override fun queueEndOfStream() {
        inputEnded = true
        outputBuffer = AudioProcessor.EMPTY_BUFFER
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
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputFormat = AudioFormat.NOT_SET
        outputFormat = AudioFormat.NOT_SET
    }
}
