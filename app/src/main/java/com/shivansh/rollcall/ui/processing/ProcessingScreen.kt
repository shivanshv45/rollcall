package com.shivansh.rollcall.ui.processing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivansh.rollcall.domain.model.ProcessingState
import com.shivansh.rollcall.ui.components.ProgressBar
import com.shivansh.rollcall.ui.components.TextAction
import com.shivansh.rollcall.ui.theme.Ink
import com.shivansh.rollcall.ui.theme.Magenta
import com.shivansh.rollcall.ui.theme.Radius
import com.shivansh.rollcall.ui.theme.Space
import com.shivansh.rollcall.ui.theme.TextMuted
import com.shivansh.rollcall.ui.theme.TextSecondary

/**
 * The waiting screen.
 *
 * Progress is determinate and the stage is named in words, because a bare
 * spinner gives no sense of whether a long job is moving. Faces appear as they
 * are found, which turns the wait into something worth watching.
 */
@Composable
fun ProcessingScreen(state: ProcessingState.Working, onCancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding()
            .padding(horizontal = Space.lg),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            state.stage.label,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            "${(state.progress * 100).toInt()}%",
            fontSize = 15.sp,
            color = TextMuted,
        )

        Spacer(Modifier.height(Space.lg))
        ProgressBar(state.progress, Magenta)

        Spacer(Modifier.height(Space.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
            Counter(state.framesScanned.toString(), "frames")
            Counter(state.facesFound.toString(), "faces")
        }

        AnimatedVisibility(
            visible = state.previews.isNotEmpty(),
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
        ) {
            Column {
                Spacer(Modifier.height(Space.xl))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    items(state.previews) { face ->
                        androidx.compose.foundation.Image(
                            bitmap = face.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(THUMB)
                                .clip(Radius.chip),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Space.xxl))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextAction("Cancel", onCancel)
        }
    }
}

@Composable
private fun Counter(value: String, caption: String) {
    Column {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(caption, fontSize = 13.sp, color = TextSecondary)
    }
}

private val THUMB = 56.dp
