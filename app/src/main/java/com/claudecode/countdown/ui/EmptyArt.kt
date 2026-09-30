package com.claudecode.countdown.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.sin

/** One picture for empty lists, with the line that goes under it. */
class EmptyArtInfo(val name: String, val subtitle: String, internal val draw: DrawScope.(ArtColors, Float) -> Unit)

class ArtColors(val blob: Color, val ink: Color, val accent: Color, val paper: Color)

/**
 * Pictures drawn in code, so they take the theme's colours and stay crisp at any size. Each gets
 * a phase 0..1 that loops slowly: steam rises, the plane bobs, stars twinkle, the sprout sways.
 */
val EMPTY_ARTS: List<EmptyArtInfo> = listOf(
    EmptyArtInfo("Чашка чая", "Отдохните с чашечкой чая") { c, t -> tea(c, t) },
    EmptyArtInfo("Бумажный самолётик", "Наслаждайтесь прекрасным днём") { c, t -> plane(c, t) },
    EmptyArtInfo("Ночное небо", "Можно спокойно выдохнуть") { c, t -> night(c, t) },
    EmptyArtInfo("Росток", "Самое время заняться собой") { c, t -> sprout(c, t) },
    EmptyArtInfo("Всё под контролем", "Все дела под контролем") { c, t -> clipboard(c, t) },
)

/** A fixed picture when the user chose one, otherwise a different one per list and per day. */
fun emptyArtIndex(setting: Int, listKey: String, day: LocalDate): Int =
    if (setting in EMPTY_ARTS.indices) setting
    else Math.floorMod(listKey.hashCode() + day.toEpochDay().toInt(), EMPTY_ARTS.size)

@Composable
fun EmptyArt(index: Int, modifier: Modifier = Modifier) {
    val art = EMPTY_ARTS[index.coerceIn(EMPTY_ARTS.indices)]
    val scheme = MaterialTheme.colorScheme
    val colors = ArtColors(
        blob = scheme.surfaceContainerHigh,
        ink = scheme.onSurfaceVariant,
        accent = scheme.primary,
        paper = scheme.background,
    )
    val phase by rememberInfiniteTransition(label = "art").animateFloat(
        0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    Canvas(modifier) { art.draw(this, colors, phase) }
}

// --- Drawing. Coordinates are in a 100×100 box scaled to the canvas. ---

private fun DrawScope.u(v: Float) = v * size.minDimension / 100f
private fun DrawScope.p(x: Float, y: Float) = Offset(u(x), u(y))
private fun wave(t: Float, shift: Float = 0f) = sin(2 * PI * (t + shift)).toFloat()

private fun DrawScope.blob(color: Color) {
    val path = Path().apply {
        moveTo(u(12f), u(56f))
        cubicTo(u(8f), u(30f), u(34f), u(14f), u(56f), u(18f))
        cubicTo(u(82f), u(22f), u(96f), u(42f), u(89f), u(63f))
        cubicTo(u(83f), u(84f), u(56f), u(92f), u(36f), u(85f))
        cubicTo(u(20f), u(79f), u(14f), u(70f), u(12f), u(56f))
        close()
    }
    drawPath(path, color)
}

/** A four-pointed twinkle. */
private fun DrawScope.sparkle(x: Float, y: Float, r: Float, color: Color) {
    val c = p(x, y)
    val path = Path().apply {
        moveTo(c.x, c.y - u(r))
        quadraticTo(c.x, c.y, c.x + u(r), c.y)
        quadraticTo(c.x, c.y, c.x, c.y + u(r))
        quadraticTo(c.x, c.y, c.x - u(r), c.y)
        quadraticTo(c.x, c.y, c.x, c.y - u(r))
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.tea(c: ArtColors, t: Float) {
    blob(c.blob)
    sparkle(24f, 30f, 3f, c.ink.copy(alpha = 0.5f))
    sparkle(80f, 74f, 2.5f, c.ink.copy(alpha = 0.4f))
    drawOval(c.ink.copy(alpha = 0.35f), topLeft = p(26f, 68f), size = Size(u(50f), u(9f)))
    val cup = Path().apply {
        moveTo(u(32f), u(46f))
        lineTo(u(68f), u(46f))
        cubicTo(u(68f), u(63f), u(61f), u(72f), u(50f), u(72f))
        cubicTo(u(39f), u(72f), u(32f), u(63f), u(32f), u(46f))
        close()
    }
    drawPath(cup, lerp(c.ink, c.paper, 0.15f))
    drawCircle(lerp(c.ink, c.paper, 0.15f), radius = u(7f), center = p(69f, 55f), style = Stroke(u(3f)))
    drawOval(c.accent, topLeft = p(34f, 43.5f), size = Size(u(32f), u(6f)))
    // Steam: three wisps rising and fading, each a little out of step.
    for ((i, x) in listOf(42f, 50f, 58f).withIndex()) {
        val local = (t + i / 3f) % 1f
        val lift = local * 8f
        val wisp = Path().apply {
            moveTo(u(x), u(40f - lift))
            cubicTo(u(x - 4f), u(35f - lift), u(x + 4f), u(31f - lift), u(x), u(26f - lift))
        }
        drawPath(wisp, c.ink.copy(alpha = 0.6f * (1f - local)), style = Stroke(u(2f), cap = StrokeCap.Round))
    }
}

private fun DrawScope.cloud(x: Float, y: Float, scale: Float, color: Color) {
    drawCircle(color, u(7f * scale), p(x, y))
    drawCircle(color, u(9f * scale), p(x + 9f * scale, y - 3f * scale))
    drawCircle(color, u(6f * scale), p(x + 18f * scale, y + 1f * scale))
    drawRoundRect(color, topLeft = p(x - 7f * scale, y), size = Size(u(31f * scale), u(7f * scale)), cornerRadius = CornerRadius(u(4f * scale)))
}

private fun DrawScope.plane(c: ArtColors, t: Float) {
    blob(c.blob)
    val cloudColor = lerp(c.ink, c.blob, 0.55f)
    cloud(18f + wave(t) * 1.5f, 34f, 0.9f, cloudColor)
    cloud(62f - wave(t) * 1.5f, 70f, 1.1f, cloudColor)
    val bob = wave(t) * 3f
    // Dashed trail behind the plane.
    val trail = Path().apply {
        moveTo(u(30f), u(56f + bob))
        cubicTo(u(20f), u(62f), u(18f), u(74f), u(8f), u(70f))
    }
    drawPath(trail, c.ink.copy(alpha = 0.5f), style = Stroke(u(1.4f), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(u(3f), u(3f)))))
    translate(top = u(bob)) {
        rotate(wave(t) * 3f, pivot = p(55f, 48f)) {
            val shade = lerp(c.accent, Color.Black, 0.3f)
            drawPath(Path().apply { moveTo(u(28f), u(54f)); lineTo(u(80f), u(30f)); lineTo(u(50f), u(60f)); close() }, c.accent)
            drawPath(Path().apply { moveTo(u(50f), u(60f)); lineTo(u(80f), u(30f)); lineTo(u(58f), u(72f)); close() }, shade)
            drawPath(Path().apply { moveTo(u(50f), u(60f)); lineTo(u(48f), u(70f)); lineTo(u(55f), u(65f)); close() }, lerp(shade, Color.Black, 0.2f))
        }
    }
}

private fun DrawScope.night(c: ArtColors, t: Float) {
    blob(c.blob)
    // Crescent: a full moon with the sky's colour cut out of it.
    drawCircle(c.accent, u(19f), p(48f, 52f))
    drawCircle(c.blob, u(16f), p(57f, 45f))
    val stars = listOf(Triple(70f, 30f, 3.5f), Triple(78f, 55f, 2.5f), Triple(28f, 30f, 2.8f), Triple(62f, 74f, 2f), Triple(24f, 66f, 2.2f))
    for ((i, star) in stars.withIndex()) {
        val (x, y, r) = star
        val glow = 0.35f + 0.65f * (0.5f + 0.5f * wave(t, i * 0.21f))
        sparkle(x, y, r, c.ink.copy(alpha = glow))
    }
    drawCircle(c.ink.copy(alpha = 0.4f), u(1f), p(38f, 24f))
    drawCircle(c.ink.copy(alpha = 0.4f), u(1f), p(84f, 42f))
}

private fun DrawScope.sprout(c: ArtColors, t: Float) {
    blob(c.blob)
    sparkle(76f, 28f, 3f, c.ink.copy(alpha = 0.45f))
    val leaf = lerp(c.accent, Color(0xFF43A047), 0.55f)
    rotate(wave(t) * 5f, pivot = p(50f, 60f)) {
        drawLine(lerp(leaf, Color.Black, 0.2f), p(50f, 60f), p(50f, 36f), strokeWidth = u(2.4f), cap = StrokeCap.Round)
        drawPath(Path().apply { moveTo(u(50f), u(46f)); cubicTo(u(42f), u(34f), u(32f), u(38f), u(30f), u(32f)); cubicTo(u(38f), u(28f), u(48f), u(32f), u(50f), u(46f)); close() }, leaf)
        drawPath(Path().apply { moveTo(u(50f), u(40f)); cubicTo(u(56f), u(28f), u(66f), u(30f), u(70f), u(24f)); cubicTo(u(70f), u(32f), u(60f), u(40f), u(50f), u(40f)); close() }, leaf)
    }
    val pot = lerp(c.ink, c.paper, 0.2f)
    drawPath(Path().apply { moveTo(u(37f), u(62f)); lineTo(u(63f), u(62f)); lineTo(u(59f), u(82f)); lineTo(u(41f), u(82f)); close() }, pot)
    drawRoundRect(lerp(pot, Color.Black, 0.15f), topLeft = p(34f, 57f), size = Size(u(32f), u(7f)), cornerRadius = CornerRadius(u(2f)))
}

private fun DrawScope.clipboard(c: ArtColors, t: Float) {
    blob(c.blob)
    drawRoundRect(c.paper, topLeft = p(31f, 22f), size = Size(u(38f), u(60f)), cornerRadius = CornerRadius(u(5f)))
    drawRoundRect(c.ink.copy(alpha = 0.5f), topLeft = p(31f, 22f), size = Size(u(38f), u(60f)), cornerRadius = CornerRadius(u(5f)), style = Stroke(u(1.5f)))
    drawRoundRect(c.ink, topLeft = p(42f, 18f), size = Size(u(16f), u(8f)), cornerRadius = CornerRadius(u(3f)))
    for (row in 0 until 3) {
        val y = 38f + row * 14f
        drawCircle(c.accent, u(4f), p(40f, y))
        val tick = Path().apply { moveTo(u(38f), u(y)); lineTo(u(39.6f), u(y + 1.8f)); lineTo(u(42.4f), u(y - 1.8f)) }
        drawPath(tick, c.paper, style = Stroke(u(1.3f), cap = StrokeCap.Round))
        drawRoundRect(c.ink.copy(alpha = 0.4f), topLeft = p(47f, y - 1.5f), size = Size(u(16f - row * 3f), u(3f)), cornerRadius = CornerRadius(u(1.5f)))
    }
    val glow = 0.4f + 0.6f * (0.5f + 0.5f * wave(t))
    sparkle(74f, 26f, 4f, c.accent.copy(alpha = glow))
    sparkle(24f, 70f, 2.5f, c.ink.copy(alpha = 0.5f * glow))
}
