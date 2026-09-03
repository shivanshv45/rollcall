package com.shivansh.rollcall.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivansh.rollcall.ui.components.PrimaryButton
import com.shivansh.rollcall.ui.theme.Coral
import com.shivansh.rollcall.ui.theme.Ink
import com.shivansh.rollcall.ui.theme.Magenta
import com.shivansh.rollcall.ui.theme.Space
import com.shivansh.rollcall.ui.theme.TextSecondary

@Composable
fun HomeScreen(onVideoPicked: (android.net.Uri) -> Unit) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onVideoPicked) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding()
            .padding(horizontal = Space.lg),
    ) {
        Column(Modifier.align(Alignment.TopStart).padding(top = Space.xxl)) {
            Text(
                "Roll Call",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
            )
            Spacer(Modifier.height(Space.sm))
            Text(
                "Find everyone in a video, count how often they appear, " +
                    "and turn it into a collage.",
                fontSize = 16.sp,
                lineHeight = 23.sp,
                color = TextSecondary,
                modifier = Modifier.fillMaxWidth(0.88f),
            )
        }

        HeroMark(Modifier.align(Alignment.Center))

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            PrimaryButton(
                text = "Choose a video",
                onClick = { picker.launch(arrayOf("video/*")) },
            )
            Text(
                "Processing happens on your phone. Nothing is uploaded.",
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Stacked bars standing in for people on a timeline - the same idea the results
 * screen uses, so the app looks like one thing rather than a set of screens.
 */
@Composable
private fun HeroMark(modifier: Modifier = Modifier) {
    val rows = listOf(
        listOf(0.05f to 0.28f, 0.42f to 0.62f, 0.74f to 0.95f),
        listOf(0.12f to 0.30f, 0.55f to 0.88f),
        listOf(0.0f to 0.18f, 0.32f to 0.58f, 0.68f to 0.82f),
        listOf(0.22f to 0.46f, 0.60f to 1.0f),
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        rows.forEachIndexed { index, blocks ->
            Box(Modifier.fillMaxWidth().height(10.dp)) {
                blocks.forEach { (start, end) ->
                    Box(
                        Modifier
                            .fillMaxWidth(end - start)
                            .height(10.dp)
                            .offsetBy(start)
                            .background(
                                Brush.horizontalGradient(listOf(Magenta, Coral)),
                                RoundedCornerShape(5.dp),
                            )
                            .alpha(1f - index * 0.18f),
                    )
                }
            }
        }
    }
}

private fun Modifier.offsetBy(fraction: Float) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative((constraints.maxWidth * fraction).toInt(), 0)
    }
}
