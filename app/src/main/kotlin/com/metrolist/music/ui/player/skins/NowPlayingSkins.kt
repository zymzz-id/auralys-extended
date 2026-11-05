/*
 * Auralys Extended (C) 2026 — based on Metrolist (GPL-3.0)
 * Licensed under GPL-3.0
 */
package com.metrolist.music.ui.player.skins

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Auralys Extended: now-playing skin renderers ("marketplace").
 * Each skin draws an alternative presentation of the album artwork.
 * [skinArtwork] renders the raw artwork; [title]/[artist] are used by
 * text-driven skins.
 */
enum class SkinRenderSlot { ARTWORK }

object NowPlayingSkins {

    /**
     * Wrap the album art with a skin-specific presentation.
     */
    @Composable
    fun Decorate(
        skin: com.metrolist.music.constants.NowPlayingSkin,
        title: String,
        artist: String,
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit,
    ) {
        when (skin) {
            com.metrolist.music.constants.NowPlayingSkin.CLASSIC -> Box(modifier) { content() }

            com.metrolist.music.constants.NowPlayingSkin.VINYL ->
                VinylSkin(modifier) { content() }

            com.metrolist.music.constants.NowPlayingSkin.CASSETTE ->
                CassetteSkin(title, artist, modifier) { content() }

            com.metrolist.music.constants.NowPlayingSkin.TYPOGRAPHIC ->
                TypographicSkin(title, artist, modifier) { content() }

            com.metrolist.music.constants.NowPlayingSkin.LIQUID_GLASS ->
                LiquidGlassSkin(modifier) { content() }

            com.metrolist.music.constants.NowPlayingSkin.MINIMAL_MONO ->
                MinimalMonoSkin(title, artist, modifier) { content() }
        }
    }
}

/** Spinning vinyl record — artwork is the record label, rim + grooves drawn around. */
@Composable
private fun VinylSkin(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "vinyl")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "vinylRot",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize(1.18f)
                .graphicsLayer { rotationZ = rotation }
                .clip(CircleShape)
                .drawBehind {
                    drawCircle(Color.Black.copy(alpha = 0.85f))
                    val r = size.minDimension / 2f
                    // grooves
                    for (i in 4..12) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.05f),
                            radius = r * i / 13f,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
                        )
                    }
                    // label ring
                    drawCircle(
                        color = Color.White.copy(alpha = 0.25f),
                        radius = r * 0.34f,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize(0.34f).clip(CircleShape)) { content() }
        }
        // spindle hole
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(Color.Black)
        )
    }
}

/** Cassette tape — artwork sits in the cassette window; label shows title/artist. */
@Composable
private fun CassetteSkin(
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(Color.Black.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title.take(28).uppercase(),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        Box(Modifier.fillMaxWidth()) { content() }
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                .background(Color.Black.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center,
        ) {
            // two tape reels
            Row(horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(2) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.85f))
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            artist,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Typographic skin — big serif-ish title above a small artwork tile. */
@Composable
private fun TypographicSkin(
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            lineHeight = 36.sp,
            maxLines = 3,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Box(Modifier.size(120.dp).clip(RoundedCornerShape(8.dp))) { content() }
        Text(
            artist,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 14.sp,
            letterSpacing = 4.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** Liquid Glass 2.0 — refracted glass frame with animated sheen. */
@Composable
private fun LiquidGlassSkin(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "glass")
    val sheen by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(3800, easing = LinearEasing), RepeatMode.Restart),
        label = "sheen",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize(1.06f)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.18f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.10f),
                        ),
                        start = Offset(sheen * 600f, 0f),
                        end = Offset(sheen * 600f + 300f, 800f),
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize(0.9f).clip(RoundedCornerShape(22.dp))) { content() }
        }
    }
}

/** Minimal mono — greyscale artwork with monospace caption. */
@Composable
private fun MinimalMonoSkin(
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(0.8f).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = 0.9f }) { content() }
        Spacer(Modifier.height(14.dp))
        Text(
            "▶ $title — $artist",
            color = Color.White.copy(alpha = 0.85f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}
