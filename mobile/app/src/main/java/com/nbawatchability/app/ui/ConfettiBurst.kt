package com.nbawatchability.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import com.nbawatchability.app.ui.theme.FavoriteAccent
import com.nbawatchability.app.ui.theme.TierInstantClassic
import com.nbawatchability.app.ui.theme.TierSolid
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class ConfettiParticle(
    val angleDegrees: Float,
    val distance: Float,
    val color: Color,
    val size: Float,
    val rotationDegrees: Float
)

// ~4x the spread/particle size and ~3x the duration of the original tuning -
// James's call after seeing the first pass felt too small/quick to register.
private const val PARTICLE_COUNT = 40
private const val BURST_DURATION_MS = 2700
private val CONFETTI_COLORS = listOf(TierInstantClassic, TierWorthYourTime, TierSolid, FavoriteAccent)

/**
 * One-shot particle burst, drawn from the tile's tier-badge corner (the top
 * area a Instant Classic result was just revealed) - not a general-purpose
 * confetti system, just enough for this one easter egg. No library exists in
 * this codebase for this (checked before building - see GameCard.kt's
 * instantClassicCelebrated comment), so this is a small hand-rolled Canvas
 * animation: [PARTICLE_COUNT] rectangles fired outward from a fixed origin,
 * eased out with a light downward drift (gravity), fading to transparent as
 * they travel. Calls [onFinished] once the animation completes so the caller
 * can stop compositing this overlay.
 */
@Composable
fun ConfettiBurst(modifier: Modifier = Modifier, onFinished: () -> Unit = {}) {
    val particles = remember {
        List(PARTICLE_COUNT) {
            ConfettiParticle(
                angleDegrees = Random.nextFloat() * 360f,
                distance = 280f + Random.nextFloat() * 440f,
                color = CONFETTI_COLORS.random(),
                size = 8f + Random.nextFloat() * 8f,
                rotationDegrees = (Random.nextFloat() - 0.5f) * 900f
            )
        }
    }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, animationSpec = tween(durationMillis = BURST_DURATION_MS, easing = LinearOutSlowInEasing))
        onFinished()
    }

    Canvas(modifier = modifier) {
        val originX = 60f
        val originY = 40f
        val t = progress.value
        val eased = 1f - (1f - t) * (1f - t)
        val alpha = (1f - t).coerceIn(0f, 1f)

        particles.forEach { particle ->
            val radians = Math.toRadians(particle.angleDegrees.toDouble())
            val dist = particle.distance * eased
            val x = originX + dist * cos(radians).toFloat()
            val y = originY + dist * sin(radians).toFloat() + t * t * 200f
            rotate(degrees = particle.rotationDegrees * t, pivot = Offset(x, y)) {
                drawRect(
                    color = particle.color.copy(alpha = alpha),
                    topLeft = Offset(x - particle.size / 2f, y - particle.size / 2f),
                    size = Size(particle.size, particle.size * 1.6f)
                )
            }
        }
    }
}
