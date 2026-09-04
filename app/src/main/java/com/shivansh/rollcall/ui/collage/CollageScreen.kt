package com.shivansh.rollcall.ui.collage

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivansh.rollcall.domain.model.CollageBorder
import com.shivansh.rollcall.ui.components.PrimaryButton
import com.shivansh.rollcall.ui.components.SecondaryButton
import com.shivansh.rollcall.ui.components.TextAction
import com.shivansh.rollcall.ui.theme.Ink
import com.shivansh.rollcall.ui.theme.Magenta
import com.shivansh.rollcall.ui.theme.Radius
import com.shivansh.rollcall.ui.theme.Space
import com.shivansh.rollcall.ui.theme.TextSecondary

@Composable
fun CollageScreen(
    collage: Bitmap?,
    savedMessage: String?,
    showLabels: Boolean,
    border: CollageBorder,
    borderThumbnails: List<Bitmap>,
    onBorderChange: (CollageBorder) -> Unit,
    onShowLabelsChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding()
            .padding(horizontal = Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Space.md))

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (collage == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Magenta)
                    Spacer(Modifier.height(Space.md))
                    Text("Building the collage", color = TextSecondary, fontSize = 15.sp)
                }
            } else {
                Image(
                    bitmap = collage.asImageBitmap(),
                    contentDescription = "The finished collage",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(COLLAGE_ASPECT)
                        .clip(Radius.tile),
                )
            }
        }

        savedMessage?.let {
            Spacer(Modifier.height(Space.sm))
            Text(it, color = TextSecondary, fontSize = 14.sp)
        }

        Spacer(Modifier.height(Space.sm))
        BorderPicker(
            borders = CollageBorder.ALL,
            selected = border,
            thumbnails = borderThumbnails,
            onSelect = onBorderChange,
            enabled = collage != null,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Space.xs))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Radius.chip)
                .clickable(enabled = collage != null) { onShowLabelsChange(!showLabels) }
                .padding(vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Show names and counts", color = Color.White, fontSize = 15.sp)
                Text(
                    if (showLabels) "Tap for a plain photo grid" else "Tap to label each person",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
            Switch(
                checked = showLabels,
                onCheckedChange = onShowLabelsChange,
                enabled = collage != null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Magenta,
                ),
            )
        }

        Spacer(Modifier.height(Space.sm))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Box(Modifier.weight(1f)) {
                SecondaryButton("Save", onSave)
            }
            Box(Modifier.weight(1f)) {
                PrimaryButton("Share", onShare, enabled = collage != null)
            }
        }
        TextAction("Back to results", onBack, Modifier.padding(vertical = Space.xs))
        Spacer(Modifier.height(Space.xs))
    }
}

private const val COLLAGE_ASPECT = 1080f / 1920f
