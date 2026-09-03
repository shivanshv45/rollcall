package com.shivansh.rollcall.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivansh.rollcall.domain.model.Appearance
import com.shivansh.rollcall.ui.theme.InkLine
import com.shivansh.rollcall.ui.theme.Radius
import com.shivansh.rollcall.ui.theme.Space
import com.shivansh.rollcall.ui.theme.TextMuted
import com.shivansh.rollcall.ui.theme.TextSecondary

/**
 * A person's appearances laid out on the video's timeline.
 *
 * Turns "4 appearances" from a number you have to trust into something you can
 * check at a glance - and shows the spacing, which a count alone hides.
 */
@Composable
fun ScrubberStrip(
    appearances: List<Appearance>,
    durationMs: Long,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val description = appearances.joinToString(", ") { block ->
        "${block.startMs / 1000}s to ${block.endMs / 1000}s"
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            .clip(RoundedCornerShape(3.dp))
            .background(InkLine)
            .semantics { contentDescription = "Appears at $description" }
    ) {
        if (durationMs <= 0) return@Box
        for (block in appearances) {
            val start = (block.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val end = (block.endMs.toFloat() / durationMs).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth(end - start)
                    .height(STRIP_HEIGHT)
                    .offsetFraction(start)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

/** Positions a child by a fraction of the parent width without a custom layout. */
private fun Modifier.offsetFraction(fraction: Float) = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative((constraints.maxWidth * fraction).toInt(), 0)
        }
    }
)

/** Identity marker. Carries a letter as well as a colour so it reads without colour. */
@Composable
fun IdentityChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(CHIP_SIZE)
            .clip(Radius.chip)
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), Radius.chip),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun StatBlock(value: String, caption: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Text(caption, fontSize = 13.sp, color = TextMuted)
    }
}

/**
 * What a screen shows when there is nothing to show.
 *
 * Every list that can be empty gets one of these - a blank screen leaves people
 * wondering whether the app is broken or still loading.
 */
@Composable
fun EmptyState(
    headline: String,
    detail: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.padding(horizontal = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            headline,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            detail,
            fontSize = 15.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp,
        )
        action?.let {
            Box(Modifier.padding(top = Space.md)) { it() }
        }
    }
}

@Composable
fun ProgressBar(progress: Float, color: Color, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 240),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clip(Radius.pill)
            .background(InkLine)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(BAR_HEIGHT)
                .clip(Radius.pill)
                .background(color)
        )
    }
}

private val STRIP_HEIGHT = 6.dp
private val CHIP_SIZE = 34.dp
private val BAR_HEIGHT = 6.dp
