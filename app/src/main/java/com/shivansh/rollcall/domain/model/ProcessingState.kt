package com.shivansh.rollcall.domain.model

import android.graphics.Bitmap

/**
 * What the pipeline is doing, as a stream the UI renders directly.
 *
 * Progress is staged rather than a single float so the screen can name the step
 * in words - people trust progress they can read.
 */
sealed interface ProcessingState {
    data object Idle : ProcessingState

    data class Working(
        val stage: Stage,
        val progress: Float,
        val framesScanned: Int = 0,
        val facesFound: Int = 0,
        val previews: List<Bitmap> = emptyList(),
    ) : ProcessingState

    data class Done(val result: VideoAnalysis) : ProcessingState

    data class Failed(val reason: Failure) : ProcessingState

    data object Cancelled : ProcessingState
}

enum class Stage(val label: String) {
    ReadingFrames("Reading frames"),
    FindingFaces("Finding faces"),
    GroupingPeople("Grouping people"),
    ChoosingShots("Choosing shots"),
}

/** Failures the user can act on, rather than a stack trace. */
sealed interface Failure {
    data object UnreadableVideo : Failure
    data object NoFacesFound : Failure
    data class Unexpected(val message: String) : Failure
}

data class VideoAnalysis(
    val people: List<Person>,
    val durationMs: Long,
) {
    val totalAppearances get() = people.sumOf { it.appearanceCount }
}
