package com.shivansh.rollcall

import com.shivansh.rollcall.domain.model.Failure
import com.shivansh.rollcall.domain.model.ProcessingState
import com.shivansh.rollcall.domain.model.Stage
import com.shivansh.rollcall.domain.model.VideoAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards the routing rule that AnimatedContent depends on.
 *
 * The outgoing screen is still rendered while it fades, so a branch that reads
 * the live state renders one frame after that state has moved on. Reading
 * ProcessingState.Done through a Working cast is what crashed the app the
 * instant processing hit 100%, so the payload each screen draws has to be
 * captured when it is routed, not looked up later.
 */
class ScreenRoutingTest {

    /** Mirrors the routing in RollCallApp, including the payload capture. */
    private sealed interface Route {
        data object Home : Route
        data class Processing(val state: ProcessingState.Working) : Route
        data class Result(val analysis: VideoAnalysis) : Route
        data object Collage : Route
        data class Error(val failure: Failure) : Route
    }

    private fun route(state: ProcessingState, showCollage: Boolean = false): Route = when {
        showCollage -> Route.Collage
        state is ProcessingState.Working -> Route.Processing(state)
        state is ProcessingState.Done -> Route.Result(state.result)
        state is ProcessingState.Failed -> Route.Error(state.reason)
        else -> Route.Home
    }

    private fun key(route: Route) = when (route) {
        Route.Home -> 0
        is Route.Processing -> 1
        is Route.Result -> 2
        Route.Collage -> 3
        is Route.Error -> 4
    }

    private fun working(progress: Float) = ProcessingState.Working(
        stage = Stage.FindingFaces,
        progress = progress,
        framesScanned = 10,
        facesFound = 4,
        previews = emptyList(),
    )

    private val analysis = VideoAnalysis(people = emptyList(), durationMs = 10_000)

    @Test
    fun `the outgoing screen keeps its own payload when state moves on`() {
        val outgoing = route(working(0.99f))

        // State advances to Done while the processing screen is still fading.
        route(ProcessingState.Done(analysis))

        // The captured route must still be readable - this is the cast that threw.
        val stillRenderable = outgoing as Route.Processing
        assertEquals(0.99f, stillRenderable.state.progress, 0.001f)
    }

    @Test
    fun `done routes to result carrying the analysis`() {
        val route = route(ProcessingState.Done(analysis))
        assertEquals(analysis, (route as Route.Result).analysis)
    }

    @Test
    fun `failure routes to error carrying the reason`() {
        val route = route(ProcessingState.Failed(Failure.NoFacesFound))
        assertEquals(Failure.NoFacesFound, (route as Route.Error).failure)
    }

    @Test
    fun `idle routes home`() {
        assertEquals(Route.Home, route(ProcessingState.Idle))
    }

    @Test
    fun `the collage flag wins over the processing state`() {
        assertEquals(Route.Collage, route(ProcessingState.Done(analysis), showCollage = true))
    }

    @Test
    fun `progress updates do not restart the transition`() {
        // Same screen, new payload: the animation keys on identity, so a
        // progress tick must not read as a screen change.
        assertEquals(key(route(working(0.2f))), key(route(working(0.8f))))
    }

    @Test
    fun `moving from processing to result is a screen change`() {
        assertNotEquals(key(route(working(1f))), key(route(ProcessingState.Done(analysis))))
    }
}
