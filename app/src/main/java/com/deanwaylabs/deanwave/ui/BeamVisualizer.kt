package com.deanwaylabs.deanwave.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.deanwaylabs.deanwave.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The DeanWave visualizer: gold beams beaming DeanWay colors while music
 * plays, plus the DeanWay Labs Circle D emblem drifting and glowing.
 *
 * This is the moving part the theme concept promised, and it is
 * audio-reactive, never a canned loop: [energies] comes from the sound
 * engine's own output frames ([com.deanwaylabs.deanwave.dsp.SoundEngine]
 * .bandEnergies(), sampled by the screen at ~30 Hz). When the music
 * stops, the energies decay and the beams settle to a faint glow. The
 * emblem's slow drift is the only motion not driven by audio — it sways
 * gently while its glow follows the music's overall energy.
 *
 * Beams radiate from the center (where the album art sits on top of this
 * box), one pair per energy band, their length and brightness following
 * their band's live energy.
 */
@Composable
fun BeamVisualizer(
    energies: FloatArray,
    modifier: Modifier = Modifier
) {
    val gold = MaterialTheme.colorScheme.primary
    val meanEnergy = if (energies.isEmpty()) 0f else energies.average().toFloat()

    // Slow emblem drift (9s across, 11s up/down) — gentle sway, not a spin.
    val drift = rememberInfiniteTransition(label = "emblem drift")
    val driftXProgress by drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift x"
    )
    val driftYProgress by drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(11000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift y"
    )
    val driftX = (sin(driftXProgress * 2 * PI) * 14).dp
    val driftY = (cos(driftYProgress * 2 * PI) * 10).dp
    val emblemAlpha = 0.75f + 0.25f * meanEnergy

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = size.center
            val beamCount = if (energies.isEmpty()) 0 else energies.size * 2
            for (i in 0 until beamCount) {
                val energy = energies[i % energies.size]
                val angle = (2 * PI * i / beamCount) - (PI / 2)
                val length = (60.dp + (energy * 150).dp).toPx()
                val end = Offset(
                    x = center.x + (cos(angle) * length).toFloat(),
                    y = center.y + (sin(angle) * length).toFloat()
                )
                drawLine(
                    color = gold,
                    start = center,
                    end = end,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    alpha = 0.25f + 0.75f * energy
                )
            }
            // Soft center glow that breathes with the overall level.
            drawCircle(
                color = gold,
                radius = 70.dp.toPx(),
                center = center,
                alpha = 0.08f + 0.15f * meanEnergy
            )
        }
        Image(
            painter = painterResource(R.drawable.deanway_circle_d),
            contentDescription = "DeanWay Labs",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(x = driftX, y = driftY)
                .size(72.dp),
            alpha = emblemAlpha
        )
    }
}

/**
 * The full-screen animated background: DeanWay navy with slow-drifting
 * gold dust and a soft breathing glow. This is the decoration Jon
 * asked for beyond the floating Circle D — alive even when nothing is
 * playing, calm enough never to fight the content on top of it.
 *
 * Cheap by design for a 4 GB phone: ~40 precomputed dust motes on one
 * Canvas, one shared 36-second phase, no per-frame allocations.
 */
private data class DustMote(
    val baseX: Float,
    val baseY: Float,
    val radiusDp: Float,
    val rise: Float,
    val sway: Float,
    val twinkle: Float
)

private val DUST: List<DustMote> = run {
    val random = kotlin.random.Random(7)
    List(42) {
        DustMote(
            baseX = random.nextFloat(),
            baseY = random.nextFloat(),
            radiusDp = 1f + random.nextFloat() * 2.5f,
            rise = 0.15f + random.nextFloat() * 0.5f,
            sway = 0.01f + random.nextFloat() * 0.03f,
            twinkle = 0.5f + random.nextFloat() * 1.5f
        )
    }
}

@Composable
fun AnimatedBackground(modifier: Modifier = Modifier) {
    val navy = MaterialTheme.colorScheme.background
    val gold = MaterialTheme.colorScheme.primary
    val dustPhase = rememberInfiniteTransition(label = "gold dust")
    val phase by dustPhase.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(36000),
            repeatMode = RepeatMode.Restart
        ),
        label = "dust phase"
    )
    Canvas(modifier = modifier) {
        drawRect(color = navy)
        // Soft breathing glow near the top, barely there.
        val glowAlpha = 0.04f + 0.02f * sin(phase * 2 * PI).toFloat()
        drawCircle(
            color = gold,
            radius = size.width * 0.55f,
            center = Offset(size.width / 2f, size.height * 0.12f),
            alpha = glowAlpha
        )
        // Drifting gold dust: slow rise, gentle sway, twinkling alpha.
        for (mote in DUST) {
            val y = ((mote.baseY + phase * mote.rise) % 1f) * size.height
            val x = (mote.baseX + sin(phase * 2 * PI + mote.baseY * 10).toFloat() * mote.sway) *
                size.width
            val twinkle = 0.5f + 0.5f *
                sin(phase * 2 * PI * mote.twinkle + mote.baseX * 20).toFloat()
            drawCircle(
                color = gold,
                radius = mote.radiusDp.dp.toPx(),
                center = Offset(x, y),
                alpha = 0.06f + 0.20f * twinkle
            )
        }
    }
}
