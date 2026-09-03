package com.shivansh.rollcall.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.shivansh.rollcall.CrashReporter
import com.shivansh.rollcall.ui.components.PrimaryButton
import com.shivansh.rollcall.ui.components.SecondaryButton
import com.shivansh.rollcall.ui.theme.Ink
import com.shivansh.rollcall.ui.theme.InkElevated
import com.shivansh.rollcall.ui.theme.Radius
import com.shivansh.rollcall.ui.theme.Space
import com.shivansh.rollcall.ui.theme.TextSecondary

/**
 * Shows the previous run's crash report so it can be sent on without a cable.
 */
@Composable
fun DiagnosticsScreen(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding()
            .padding(horizontal = Space.lg),
    ) {
        Spacer(Modifier.height(Space.lg))
        Text(
            "The last run crashed",
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
        )
        Spacer(Modifier.height(Space.xxs))
        Text(
            "Send this report on, then carry on using the app.",
            fontSize = 15.sp,
            color = TextSecondary,
        )

        Spacer(Modifier.height(Space.md))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(Radius.tile)
                .background(InkElevated)
                .padding(Space.sm),
        ) {
            Text(
                report,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            )
        }

        Spacer(Modifier.height(Space.md))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Box(Modifier.weight(1f)) {
                SecondaryButton(
                    text = "Dismiss",
                    onClick = {
                        CrashReporter.clear(context)
                        onDismiss()
                    },
                )
            }
            Box(Modifier.weight(1f)) {
                PrimaryButton(
                    text = "Share report",
                    onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Roll Call crash report")
                                    putExtra(Intent.EXTRA_TEXT, report)
                                },
                                null,
                            )
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(Space.md))
    }
}
