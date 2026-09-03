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
import com.shivansh.rollcall.domain.model.VideoAnalysis
import com.shivansh.rollcall.ui.collage.CollageScreen
import com.shivansh.rollcall.ui.components.EmptyState
import com.shivansh.rollcall.ui.components.PrimaryButton
import com.shivansh.rollcall.ui.home.HomeScreen
import com.shivansh.rollcall.ui.processing.ProcessingScreen
import com.shivansh.rollcall.ui.result.ResultScreen
import com.shivansh.rollcall.ui.theme.Ink

/**
 * What a screen needs to draw itself, captured when it was routed to.
 *
 * AnimatedContent keeps rendering the outgoing screen through the crossfade, so
 * a branch that reads live state renders one frame after that state moved on.
 */
private sealed interface Screen {
    data object Home : Screen
    data class Processing(val state: ProcessingState.Working) : Screen
    data class Result(val analysis: VideoAnalysis, val report: String) : Screen
    data object Collage : Screen
    data class Error(val failure: Failure) : Screen
}

/** Animates on screen identity, so a progress tick is not a screen change. */
private val Screen.key: Int
    get() = when (this) {
        Screen.Home -> 0
        is Screen.Processing -> 1
        is Screen.Result -> 2
        Screen.Collage -> 3
        is Screen.Error -> 4
    }

@Composable
fun RollCallApp(viewModel: MainViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val collage by viewModel.collage.collectAsState()
    val portraits by viewModel.portraits.collectAsState()
    val showLabels by viewModel.showLabels.collectAsState()
    val context = LocalContext.current

    var showCollage by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var crashReport by remember { mutableStateOf(CrashReporter.lastReport(context)) }

    var details by remember { mutableStateOf<String?>(null) }

    crashReport?.let { report ->
        DiagnosticsScreen(report, onDismiss = {
            CrashReporter.clear(context)
            crashReport = null
        })
        return
    }

    details?.let { report ->
        DiagnosticsScreen(
            report,
            onDismiss = { details = null },
            title = "Run details",
            subtitle = "What the pipeline saw. Share this if the result looks wrong.",
            subject = "Roll Call run report",
            dismissLabel = "Back",
        )
        return
    }

    val current = state
    val screen: Screen = when {
        showCollage -> Screen.Collage
        current is ProcessingState.Working -> Screen.Processing(current)
        current is ProcessingState.Done -> Screen.Result(current.result, current.report)
        current is ProcessingState.Failed -> Screen.Error(current.reason)
        else -> Screen.Home
    }

    // A new run should not inherit the last run's confirmation text.
    LaunchedEffect(screen.key) { if (screen !is Screen.Collage) savedMessage = null }

    Box(Modifier.fillMaxSize().background(Ink)) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn(spring(stiffness = 380f)) togetherWith fadeOut() },
            contentKey = { it.key },
            label = "screen",
        ) { target ->
            when (target) {
                Screen.Home -> HomeScreen(onVideoPicked = viewModel::process)

                is Screen.Processing -> ProcessingScreen(
                    state = target.state,
                    onCancel = viewModel::cancel,
                )

                is Screen.Result -> ResultScreen(
                    analysis = target.analysis,
                    portraits = portraits,
                    onShowDetails = { details = target.report },
                    onCreateCollage = {
                        viewModel.buildCollage()
                        showCollage = true
                    },
                    onStartOver = viewModel::reset,
                )

                Screen.Collage -> CollageScreen(
                    collage = collage,
                    savedMessage = savedMessage,
                    showLabels = showLabels,
                    onShowLabelsChange = viewModel::setShowLabels,
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

                is Screen.Error -> ErrorScreen(
                    failure = target.failure,
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
