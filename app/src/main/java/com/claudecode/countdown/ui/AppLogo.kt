package com.claudecode.countdown.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import com.claudecode.countdown.AppIconStyle

/**
 * The app icon as a launcher draws it: the variant's background in a rounded square and its
 * foreground scaled so the visible 72 of the 108-unit adaptive canvas fill the tile.
 */
@Composable
fun AppLogo(style: AppIconStyle, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(percent = 30)).background(colorResource(style.background)),
        contentAlignment = Alignment.Center,
    ) {
        val side = maxWidth * 1.5f
        Box(contentAlignment = Alignment.Center) {
            Image(painterResource(style.foreground), style.label, Modifier.requiredSize(side))
        }
    }
}

/**
 * The logo drawn in the colours of the current theme (accent tile, ring and check in the colour
 * that reads on it), so inside the app it matches the interface whatever icon the launcher shows.
 * Same geometry as the launcher icon: a ring three quarters full around a check mark.
 */
@Composable
fun ThemedLogo(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tile = scheme.primary
    val ink = scheme.onPrimary
    Canvas(modifier.clip(RoundedCornerShape(percent = 30)).background(tile)) {
        // The visible 72 units of the 108-unit adaptive canvas fill the tile.
        val u = size.minDimension / 72f
        val c = Offset(size.width / 2, size.height / 2)
        val r = 21.6f * u
        val ring = Stroke(width = 5.6f * u, cap = StrokeCap.Round)
        val box = Rect(c.x - r, c.y - r, c.x + r, c.y + r)
        drawArc(ink.copy(alpha = 0.35f), 180f, 90f, false, box.topLeft, box.size, style = ring)
        drawArc(ink, 270f, 270f, false, box.topLeft, box.size, style = ring)
        val check = Path().apply {
            moveTo(c.x + (45.3f - 54) * u, c.y + (54.7f - 54) * u)
            lineTo(c.x + (51.8f - 54) * u, c.y + (61.2f - 54) * u)
            lineTo(c.x + (63.2f - 54) * u, c.y + (48.2f - 54) * u)
        }
        drawPath(check, ink, style = Stroke(width = 4.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
