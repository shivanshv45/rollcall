package com.shivansh.rollcall.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.shivansh.rollcall.CrashReporter
import com.shivansh.rollcall.domain.model.Failure
import com.shivansh.rollcall.domain.model.ProcessingState
import com.shivansh.rollcall.ui.collage.CollageScreen
import com.shivansh.rollcall.ui.components.EmptyState
import com.shivansh.rollcall.ui.components.PrimaryButton
import com.shivansh.rollcall.ui.home.HomeScreen
import com.shivansh.rollcall.ui.processing.ProcessingScreen
import com.shivansh.rollcall.ui.result.ResultScreen
import com.shivansh.rollcall.ui.theme.Ink

private enum class Screen { Home, Processing, Result, Collage, Error }

@Composable
fun RollCallApp(viewModel: MainViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val collage by viewModel.collage.collectAsState()
    val context = LocalContext.current

    var showCollage by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var crashReport by remember { mutableStateOf(CrashReporter.lastReport(context)) }

    crashReport?.let { report ->
        DiagnosticsScreen(report) { crashReport = null }
        return
    }

    val screen = when {
        showCollage -> Screen.Collage
        state is ProcessingState.Working -> Screen.Processing
        state is ProcessingState.Done -> Screen.Result
        state is ProcessingState.Failed -> Screen.Error
        else -> Screen.Home
    }

    // A new run should not inherit the last run's confirmation text.
    LaunchedEffect(screen) { if (screen != Screen.Collage) savedMessage = null }

    Box(Modifier.fillMaxSize().background(Ink)) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn(spring(stiffness = 380f)) togetherWith fadeOut() },
            label = "screen",
        ) { current ->
            when (current) {
                Screen.Home -> HomeScreen(onVideoPicked = viewModel::process)

                Screen.Processing -> ProcessingScreen(
                    state = state as ProcessingState.Working,
                    onCancel = viewModel::cancel,
                )

                Screen.Result -> ResultScreen(
                    analysis = (state as ProcessingState.Done).result,
                    onCreateCollage = {
                        viewModel.buildCollage()
                        showCollage = true
                    },
                    onStartOver = viewModel::reset,
                )

                Screen.Collage -> CollageScreen(
                    collage = collage,
                    savedMessage = savedMessage,
                    onSave = {
                        viewModel.save { uri ->
                            savedMessage = if (uri != null) {
                                "Saved to Photos"
                            } else {
                                "Couldn't save. Check storage space and try again."
                            }
                        }
                    },
                    onShare = { viewModel.share(context) },
                    onBack = { showCollage = false },
                )

                Screen.Error -> ErrorScreen(
                    failure = (state as ProcessingState.Failed).reason,
                    onRetry = viewModel::reset,
                )
            }
        }
    }
}

@Composable
private fun ErrorScreen(failure: Failure, onRetry: () -> Unit) {
    val (headline, detail) = when (failure) {
        Failure.UnreadableVideo ->
            "Couldn't read that video" to
                "The file may be an unsupported format or damaged. Try another one."
        Failure.NoFacesFound ->
            "No faces in this clip" to
                "Nothing in the video was clear enough to recognise. Try a video with visible faces."
        is Failure.Unexpected ->
            "Processing stopped" to failure.message
    }

    Box(Modifier.fillMaxSize(), Alignment.Center) {
        EmptyState(headline = headline, detail = detail) {
            PrimaryButton("Choose another video", onRetry)
        }
    }
}
