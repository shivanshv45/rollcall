package com.shivansh.rollcall.ui.result

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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivansh.rollcall.domain.model.Person
import com.shivansh.rollcall.domain.model.VideoAnalysis
import com.shivansh.rollcall.ui.components.IdentityChip
import com.shivansh.rollcall.ui.components.PrimaryButton
import com.shivansh.rollcall.ui.components.ScrubberStrip
import com.shivansh.rollcall.ui.theme.IdentityColors
import com.shivansh.rollcall.ui.theme.Ink
import com.shivansh.rollcall.ui.theme.InkElevated
import com.shivansh.rollcall.ui.theme.Radius
import com.shivansh.rollcall.ui.theme.Space
import com.shivansh.rollcall.ui.theme.TextMuted
import com.shivansh.rollcall.ui.theme.TextSecondary

@Composable
fun ResultScreen(
    analysis: VideoAnalysis,
    onCreateCollage: () -> Unit,
    onStartOver: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = Space.lg),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = Space.lg,
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            item {
                Header(analysis)
                Spacer(Modifier.height(Space.md))
            }
            items(analysis.people, key = { it.id }) { person ->
                PersonRow(person, analysis.durationMs)
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Ink)
                .systemBarsPadding()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            PrimaryButton("Create collage", onCreateCollage)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                com.shivansh.rollcall.ui.components.TextAction("Choose another video", onStartOver)
            }
        }
    }
}

@Composable
private fun Header(analysis: VideoAnalysis) {
    val people = analysis.people.size
    Column {
        Text(
            "$people ${if (people == 1) "person" else "people"}",
            fontSize = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
        )
        Text(
            "${analysis.totalAppearances} appearances across " +
                "${analysis.durationMs / 1000} seconds",
            fontSize = 15.sp,
            color = TextSecondary,
        )
    }
}

@Composable
private fun PersonRow(person: Person, durationMs: Long) {
    val color = IdentityColors[person.id % IdentityColors.size]
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.card)
            .background(InkElevated)
            .padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IdentityChip(person.label, color)
            Spacer(Modifier.fillMaxWidth(0.04f))
            Column(Modifier.weight(1f)) {
                Text(
                    "${person.appearanceCount} " +
                        if (person.appearanceCount == 1) "appearance" else "appearances",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    "${person.screenTimeMs / 1000}s on screen",
                    fontSize = 13.sp,
                    color = TextMuted,
                )
            }
        }
        ScrubberStrip(person.appearances, durationMs, color)
    }
}
