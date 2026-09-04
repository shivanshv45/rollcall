package com.shivansh.rollcall.ui.collage

import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivansh.rollcall.domain.model.CollageBorder
import com.shivansh.rollcall.ui.theme.InkElevated
import com.shivansh.rollcall.ui.theme.InkLine
import com.shivansh.rollcall.ui.theme.Magenta
import com.shivansh.rollcall.ui.theme.Space
import com.shivansh.rollcall.ui.theme.TextMuted
import com.shivansh.rollcall.ui.theme.TextSecondary

/**
 * The strip of frame styles under the collage.
 *
 * Each entry is the actual collage drawn in that style rather than an icon of
 * it, so the choice is made by looking at the result. They arrive from the
 * view model a moment after the collage does; until then the strip holds its
 * shape with placeholders instead of appearing late and shifting the buttons.
 *
 * @param thumbnails one per entry of [CollageBorder.ALL], in that order. A
 *   short or empty list degrades to placeholders rather than dropping styles,
 *   so a failed thumbnail render still leaves every border selectable.
 */
@Composable
fun BorderPicker(
    borders: List<CollageBorder>,
    selected: CollageBorder,
    thumbnails: List<Bitmap>,
    onSelect: (CollageBorder) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    // Keep the chosen style in view when it changes from anywhere but a tap
    // here - a reset, or a restore.
    LaunchedEffect(selected) {
        val index = borders.indexOf(selected)
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(modifier) {
        Text(
            text = "Frame",
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
        )
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            contentPadding = PaddingValues(vertical = Space.xs),
        ) {
            itemsIndexed(borders, key = { _, border -> border.id }) { index, border ->
                BorderChip(
                    border = border,
                    thumbnail = thumbnails.getOrNull(index),
                    isSelected = border == selected,
                    enabled = enabled,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelect(border)
                    },
                )
            }
        }
    }
}

@Composable
private fun BorderChip(
    border: CollageBorder,
    thumbnail: Bitmap?,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 700f),
        label = "chip",
    )
    val ring by animateDpAsState(
        targetValue = if (isSelected) 2.5.dp else 1.dp,
        animationSpec = spring(stiffness = 700f),
        label = "ring",
    )
    val interaction = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(THUMB_WIDTH)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .semantics {
                selected = isSelected
                contentDescription = "${border.label} frame"
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(THUMB_ASPECT)
                // The ring sits outside the clip so it reads as a frame around
                // the thumbnail rather than a line drawn over its edge.
                .border(ring, if (isSelected) Magenta else InkLine, THUMB_SHAPE)
                .padding(ring)
                .clip(THUMB_SHAPE)
                .background(InkElevated),
        ) {
            if (thumbnail != null && !thumbnail.isRecycled) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(scale),
                )
            }
        }
        Spacer(Modifier.height(Space.xxs))
        Text(
            text = border.label,
            color = if (isSelected) Color.White else TextMuted,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private val THUMB_WIDTH = 62.dp
private const val THUMB_ASPECT = 1080f / 1920f
private val THUMB_SHAPE = RoundedCornerShape(10.dp)
private const val DISABLED_ALPHA = 0.45f
