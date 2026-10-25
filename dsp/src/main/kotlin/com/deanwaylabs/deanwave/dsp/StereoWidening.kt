package com.deanwaylabs.deanwave.dsp

/**
 * Stereo widening done the mono-safe way: mid/side processing with no
 * delay and no phase shift.
 *
 * For a stereo frame, mid = (L + R) / 2 and side = (L - R) / 2. Widening
 * turns the side up: L' = mid + side * (1 + amount), R' = mid - side *
 * (1 + amount). The mono sum never changes (L' + R' = L + R), so the
 * sound never cancels out on a mono speaker or a single earbud, and at
 * moderate amounts there is nothing phasey about it — it is pure gain
 * on the part of the signal that already differs between channels.
 *
 * Amount 0 is an exact bypass: [processFrame] returns without touching
 * the frame. Mono frames pass through untouched at any amount.
 */
class StereoWidening {
    private var amount = 0f

    /** 0.0 = untouched stereo, 1.0 = side channel doubled. */
    fun setAmount(amount: Float) {
        this.amount = amount.coerceIn(0f, 1f)
    }

    fun amount(): Float = amount

    /** Processes one interleaved frame in place (frame size == channel count). */
    fun processFrame(frame: FloatArray) {
        if (amount == 0f || frame.size != 2) return
        val left = frame[0]
        val right = frame[1]
        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.5f
        val widenedSide = side * (1f + amount)
        frame[0] = mid + widenedSide
        frame[1] = mid - widenedSide
    }

    /** Stateless — nothing to clear. */
    fun reset() = Unit
}
