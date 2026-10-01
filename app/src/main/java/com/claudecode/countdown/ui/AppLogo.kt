package com.claudecode.countdown.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
