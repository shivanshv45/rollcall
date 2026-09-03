package com.shivansh.rollcall

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.shivansh.rollcall.data.ProcessingRepository
import com.shivansh.rollcall.data.collage.CollageRenderer
import com.shivansh.rollcall.data.detection.MlKitFaceDetector
import com.shivansh.rollcall.data.embedding.FaceEmbedder
import com.shivansh.rollcall.data.video.FrameExtractor
import com.shivansh.rollcall.domain.model.PipelineConfig
import com.shivansh.rollcall.domain.model.ProcessingState
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real pipeline against the sample clips on a device.
 *
 * Push the videos first:
 *   adb push vids/. /sdcard/Movies/
 *
 * Sample 1 is the only clip with a published answer - the brief states five
 * people appearing four times each - so it is asserted exactly. The other two
 * only get sanity checks, because inventing an expected number for them and
 * then tuning until it passed would be the definition of over-fitting.
 */
class PipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val config = PipelineConfig()

    private fun repository() = ProcessingRepository(
        frames = FrameExtractor(context, config),
        detector = MlKitFaceDetector(config),
        embedder = FaceEmbedder(context),
        config = config,
    )

    private fun sample(n: Int): Uri? {
        val file = File("/sdcard/Movies/iykyk_handheld_chaos_sample_$n.mp4")
        return if (file.exists()) Uri.fromFile(file) else null
    }

    private fun analyse(n: Int) = runBlocking {
        val uri = sample(n)
        assumeTrue("sample $n not in Movies/ on the device", uri != null)
        val state = repository().analyse(uri!!).last()
        assertTrue("pipeline failed: $state", state is ProcessingState.Done)
        (state as ProcessingState.Done).result
    }

    @Test
    fun sampleOneMatchesTheBriefsWorkedExample() {
        val result = analyse(1)
        assertEquals("distinct people", 5, result.people.size)
        assertEquals("total appearances", 20, result.totalAppearances)
        result.people.forEach {
            assertEquals("${it.label} appearance count", 4, it.appearanceCount)
        }
    }

    @Test
    fun sampleOneFindsTheSharedFrames() {
        // The brief says two people share the frame around 10.1-11.5s and two
        // more around 20.2-21.6s. Nothing tells the pipeline this.
        val result = analyse(1)
        fun peopleVisibleAt(ms: Long) = result.people.count { person ->
            person.appearances.any { ms >= it.startMs && ms < it.endMs }
        }
        assertEquals("two people at 10.5s", 2, peopleVisibleAt(10_500))
        assertTrue("someone visible at 20.5s", peopleVisibleAt(20_500) >= 1)
    }

    @Test
    fun everyPersonGetsAUsableRepresentative() {
        val result = analyse(1)
        result.people.forEach { person ->
            val shot = person.representative
            assertTrue("${person.label} representative is too poor", shot.quality > 0.5f)
            assertTrue("${person.label} representative is turned away", shot.frontality > 0.5f)
        }
    }

    @Test
    fun otherSamplesProduceSensibleResults() {
        for (n in 2..3) {
            val result = analyse(n)
            assertTrue("sample $n found nobody", result.people.isNotEmpty())
            assertTrue("sample $n found implausibly many", result.people.size <= 10)
            result.people.forEach {
                assertTrue("sample $n has a person with no appearances", it.appearanceCount > 0)
            }
        }
    }

    @Test
    fun collageRendersAtStorySize() = runBlocking {
        val uri = sample(1)
        assumeTrue("sample 1 not on device", uri != null)
        val result = repository().analyse(uri!!).last() as ProcessingState.Done
        val bitmap = CollageRenderer(FrameExtractor(context, config), config)
            .render(uri, result.result)
        assertEquals(1080, bitmap.width)
        assertEquals(1920, bitmap.height)
    }
}
