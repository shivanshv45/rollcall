package com.shivansh.rollcall.data.collage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import com.shivansh.rollcall.data.video.FrameExtractor
import com.shivansh.rollcall.domain.model.Person
import com.shivansh.rollcall.domain.model.PipelineConfig
import com.shivansh.rollcall.domain.model.VideoAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Draws the finished collage at Instagram-story size.
 *
 * Tiles come from the full-resolution frame, cropped generously around the face
 * rather than to the detected box - a tight crop on a 540px working frame gives
 * a soft, low-resolution tile, which the brief calls out specifically.
 */
class CollageRenderer @Inject constructor(
    private val frames: FrameExtractor,
    private val config: PipelineConfig,
) {

    suspend fun render(uri: Uri, analysis: VideoAnalysis): Bitmap = withContext(Dispatchers.Default) {
        val canvasBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        drawBackground(canvas)

        val people = analysis.people
        val tiles = layoutFor(people.size)

        // One portrait in memory at a time. Decoding them all first put a
        // 1080x1920 frame per person on the heap at once, which is where a large
        // cast would actually run out.
        people.forEachIndexed { index, person ->
            val rect = tiles.getOrNull(index) ?: return@forEachIndexed
            val portrait = portrait(uri, person)
            drawTile(canvas, rect, portrait, person)
            portrait?.recycle()
        }

        drawHeader(canvas)
        drawFooter(canvas, analysis)
        canvasBitmap
    }

    /**
     * Where each tile sits, chosen by how many people there are.
     *
     * A single uniform grid for every count looks generic, and with an odd number
     * leaves a hole. These give each count a deliberate shape - a hero plus a
     * grid for five, which is the common case here.
     */
    private fun layoutFor(count: Int): List<RectF> {
        val left = MARGIN
        val right = WIDTH - MARGIN
        val top = HEADER_HEIGHT
        val bottom = HEIGHT - FOOTER_HEIGHT
        val width = right - left
        val height = bottom - top
        val g = GUTTER

        fun grid(columns: Int, rows: Int): List<RectF> {
            val cellW = (width - g * (columns - 1)) / columns
            val cellH = (height - g * (rows - 1)) / rows
            return (0 until rows).flatMap { r ->
                (0 until columns).map { c ->
                    RectF(
                        left + c * (cellW + g),
                        top + r * (cellH + g),
                        left + c * (cellW + g) + cellW,
                        top + r * (cellH + g) + cellH,
                    )
                }
            }
        }

        return when (count) {
            0 -> emptyList()
            1 -> listOf(RectF(left, top, right, bottom))
            2 -> grid(1, 2)
            3 -> {
                val heroH = height * 0.46f
                listOf(RectF(left, top, right, top + heroH)) +
                    grid(2, 1).map {
                        RectF(it.left, top + heroH + g, it.right, bottom)
                    }
            }
            4 -> grid(2, 2)
            5 -> {
                // Hero across the top, four beneath. The magazine-cover shape.
                val heroH = height * 0.34f
                val restTop = top + heroH + g
                val cellW = (width - g) / 2
                val cellH = (bottom - restTop - g) / 2
                listOf(RectF(left, top, right, top + heroH)) +
                    (0 until 2).flatMap { r ->
                        (0 until 2).map { c ->
                            RectF(
                                left + c * (cellW + g),
                                restTop + r * (cellH + g),
                                left + c * (cellW + g) + cellW,
                                restTop + r * (cellH + g) + cellH,
                            )
                        }
                    }
            }
            6 -> grid(2, 3)
            in 7..9 -> grid(3, 3).take(count)
            else -> {
                val columns = 3
                val rows = (count + columns - 1) / columns
                grid(columns, rows).take(count)
            }
        }
    }

    /**
     * The person's best frame, re-decoded at full resolution and cropped wide.
     *
     * portraitCropScale reaches well outside the face box so the tile shows head
     * and shoulders; the crop is nudged upward because centring a face exactly
     * looks like a mugshot.
     */
    private fun portrait(uri: Uri, person: Person): Bitmap? {
        val sample = person.representative
        val frame = frames.frameAt(uri, sample.timestampMs) ?: return null

        // The box came from the half-size working frame, so scale it to this one.
        val scale = frame.width.toFloat() / config.workWidth
        val box = sample.box
        val faceW = box.width * scale
        val faceH = box.height * scale
        val centerX = box.centerX * scale
        val centerY = box.centerY * scale - faceH * HEADROOM

        val cropW = faceW * config.portraitCropScale
        val cropH = cropW * TILE_ASPECT
        val x = (centerX - cropW / 2).coerceIn(0f, (frame.width - cropW).coerceAtLeast(0f))
        val y = (centerY - cropH / 2).coerceIn(0f, (frame.height - cropH).coerceAtLeast(0f))

        val left = x.roundToInt().coerceIn(0, frame.width - 1)
        val top = y.roundToInt().coerceIn(0, frame.height - 1)
        val right = (left + cropW.roundToInt()).coerceIn(left + 1, frame.width)
        val bottom = (top + cropH.roundToInt()).coerceIn(top + 1, frame.height)

        // Drawn into a new bitmap rather than cropped in place: createBitmap can
        // hand back the source when the crop covers it, and the source is
        // recycled on the next line.
        val out = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            frame,
            Rect(left, top, right, bottom),
            Rect(0, 0, right - left, bottom - top),
            imagePaint,
        )
        frame.recycle()
        return out
    }

    private fun drawTile(canvas: Canvas, rect: RectF, portrait: Bitmap?, person: Person) {
        val path = android.graphics.Path().apply {
            addRoundRect(rect, TILE_RADIUS, TILE_RADIUS, android.graphics.Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(path)

        if (portrait != null) {
            canvas.drawBitmap(portrait, null, centerCrop(portrait, rect), imagePaint)
        } else {
            canvas.drawRect(rect, Paint().apply { color = Color.parseColor("#16161C") })
        }

        // Scrim so the label stays readable over a bright frame.
        val scrimTop = rect.bottom - rect.height() * SCRIM_FRACTION
        canvas.drawRect(
            RectF(rect.left, scrimTop, rect.right, rect.bottom),
            Paint().apply {
                shader = LinearGradient(
                    0f, scrimTop, 0f, rect.bottom,
                    Color.TRANSPARENT, Color.parseColor("#CC000000"),
                    Shader.TileMode.CLAMP,
                )
            },
        )

        val count = person.appearanceCount
        canvas.drawText(
            person.label,
            rect.left + TILE_PADDING,
            rect.bottom - TILE_PADDING - labelPaint.textSize * 0.9f,
            chipPaint,
        )
        canvas.drawText(
            if (count == 1) "1 appearance" else "$count appearances",
            rect.left + TILE_PADDING,
            rect.bottom - TILE_PADDING,
            labelPaint,
        )
        canvas.restore()
    }

    /** Fills the tile without squashing the portrait. */
    private fun centerCrop(source: Bitmap, target: RectF): RectF {
        val scale = maxOf(target.width() / source.width, target.height() / source.height)
        val w = source.width * scale
        val h = source.height * scale
        val dx = target.centerX() - w / 2
        val dy = target.centerY() - h / 2
        return RectF(dx, dy, dx + w, dy + h)
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0B0B0F"))
        canvas.drawRect(
            RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()),
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
                    intArrayOf(
                        Color.parseColor("#33FF2E88"),
                        Color.parseColor("#0B0B0F"),
                        Color.parseColor("#22FF6B4A"),
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
    }

    private fun drawHeader(canvas: Canvas) {
        canvas.drawText("Roll Call", MARGIN, HEADER_HEIGHT - 46f, titlePaint)
    }

    private fun drawFooter(canvas: Canvas, analysis: VideoAnalysis) {
        val people = analysis.people.size
        val seconds = analysis.durationMs / 1000
        val text = "$people ${if (people == 1) "person" else "people"} · " +
            "${analysis.totalAppearances} appearances · " +
            "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        canvas.drawText(text, MARGIN, HEIGHT - FOOTER_HEIGHT + 64f, footerPaint)
    }

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 62f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = -0.02f
    }

    private val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A3FFFFFF")
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2E88")
        textSize = 40f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val MARGIN = 48f
        const val GUTTER = 16f
        const val HEADER_HEIGHT = 150f
        const val FOOTER_HEIGHT = 120f
        const val TILE_RADIUS = 28f
        const val TILE_PADDING = 28f
        const val TILE_ASPECT = 1.25f
        const val SCRIM_FRACTION = 0.38f

        /** Shifts the crop up so there is headroom above the face. */
        const val HEADROOM = 0.12f
    }
}
