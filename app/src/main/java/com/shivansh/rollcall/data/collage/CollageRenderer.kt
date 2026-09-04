package com.shivansh.rollcall.data.collage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import com.shivansh.rollcall.data.video.PortraitPicker
import com.shivansh.rollcall.domain.model.CollageBackground
import com.shivansh.rollcall.domain.model.CollageBorder
import com.shivansh.rollcall.domain.model.Person
import com.shivansh.rollcall.domain.model.VideoAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Draws the finished collage at Instagram-story size.
 *
 * Tiles are re-decoded from the video at output size rather than reused from the
 * downscaled working frame, and cropped wide rather than to the detected box.
 *
 * Decoding those portraits is by far the slowest part, and none of it depends on
 * which border is chosen, so [prepare] does it once and hands back a [Sheet] that
 * redraws in any style. Switching borders then costs a repaint rather than
 * another pass over the video, which is what lets the picker feel immediate.
 */
class CollageRenderer @Inject constructor(
    private val picker: PortraitPicker,
) {

    /**
     * Portraits decoded and ready to draw, for one analysis.
     *
     * Holds a bitmap per person, so it is a real amount of memory - [close] it
     * when the collage screen is done with it, and do not keep two alive.
     */
    class Sheet internal constructor(
        internal val analysis: VideoAnalysis,
        internal val portraits: List<Bitmap?>,
    ) {
        private var closed = false

        internal fun portraitAt(index: Int): Bitmap? =
            portraits.getOrNull(index)?.takeIf { !closed && !it.isRecycled }

        fun close() {
            if (closed) return
            closed = true
            portraits.forEach { it?.recycle() }
        }
    }

    /**
     * Decodes one portrait per person. Call once per analysis, then render as
     * many styles off the result as the user tries.
     */
    suspend fun prepare(uri: Uri, analysis: VideoAnalysis): Sheet =
        withContext(Dispatchers.Default) {
            // Sequential on purpose: each pick decodes full-size frames, and
            // several people at once is a straightforward way to run out of heap.
            val portraits = analysis.people.map { person -> portrait(uri, person) }
            Sheet(analysis, portraits)
        }

    /**
     * @param showLabels draws each person's letter and appearance count on their
     *   tile. Off gives a plain photo grid for sharing.
     */
    suspend fun render(
        sheet: Sheet,
        border: CollageBorder = CollageBorder.DEFAULT,
        showLabels: Boolean = true,
        width: Int = WIDTH,
        height: Int = HEIGHT,
    ): Bitmap = withContext(Dispatchers.Default) {
        val scale = width.toFloat() / WIDTH
        val style = Style(border, scale, width, height)

        val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        drawBackground(canvas, style)

        val people = sheet.analysis.people
        val tiles = layoutFor(people.size, style)

        people.forEachIndexed { index, person ->
            val rect = tiles.getOrNull(index) ?: return@forEachIndexed
            drawTile(canvas, rect, sheet.portraitAt(index), person, style, showLabels)
        }

        drawHeader(canvas, style)
        if (showLabels) drawFooter(canvas, sheet.analysis, style)
        drawVignette(canvas, style)
        canvasBitmap
    }

    /**
     * Everything the drawing needs that depends on the chosen border or the
     * output size, worked out once instead of at every call site.
     *
     * [scale] is 1 at full story size and smaller for a picker thumbnail; every
     * dimension below goes through it so a thumbnail is a faithful miniature
     * rather than a full-size layout squeezed into a small bitmap.
     */
    private class Style(
        val border: CollageBorder,
        val scale: Float,
        val width: Int,
        val height: Int,
    ) {
        val margin = border.margin * scale
        val gutter = GUTTER * scale
        val headerHeight = HEADER_HEIGHT * scale
        val footerHeight = FOOTER_HEIGHT * scale
        val tileRadius = border.tileRadius * scale
        val tileInset = border.tileInset * scale
        val tileStrokeWidth = border.tileStrokeWidth * scale
        val tilePadding = TILE_PADDING * scale

        /** True on the light sheets, where white text would disappear. */
        val onLight: Boolean = when (val background = border.background) {
            is CollageBackground.Solid -> isLight(background.argb)
            is CollageBackground.Gradient -> isLight(background.base)
        }

        val chromeColor = if (onLight) Color.parseColor("#12120F") else Color.WHITE
        val chromeMuted =
            if (onLight) Color.parseColor("#8A12120F") else Color.parseColor("#A3FFFFFF")

        private fun isLight(argb: Int): Boolean {
            val r = Color.red(argb)
            val g = Color.green(argb)
            val b = Color.blue(argb)
            return (0.299f * r + 0.587f * g + 0.114f * b) > 140f
        }
    }

    /**
     * Where each tile sits, chosen by how many people there are.
     *
     * One uniform grid for every count looks generic and leaves a hole on odd
     * numbers, so each count gets its own shape.
     */
    private fun layoutFor(count: Int, style: Style): List<RectF> {
        val left = style.margin
        val right = style.width - style.margin
        val top = style.headerHeight
        val bottom = style.height - style.footerHeight
        val width = right - left
        val height = bottom - top
        val g = style.gutter

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

        val slots = when (count) {
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
                // Hero across the top, four beneath.
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

        // The mat, applied after the layout so every style divides the sheet the
        // same way and only the photo inside each slot changes size.
        val inset = style.tileInset
        return if (inset <= 0f) slots else slots.map {
            RectF(it.left + inset, it.top + inset, it.right - inset, it.bottom - inset)
        }
    }

    /** The person's portrait, chosen and checked so nobody else is in it. */
    private suspend fun portrait(uri: Uri, person: Person): Bitmap? =
        picker.pick(uri, person, WIDTH, HEIGHT, TILE_ASPECT)

    private fun drawTile(
        canvas: Canvas,
        rect: RectF,
        portrait: Bitmap?,
        person: Person,
        style: Style,
        showLabels: Boolean,
    ) {
        val radius = style.tileRadius
        val path = android.graphics.Path().apply {
            addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(path)

        if (portrait != null && !portrait.isRecycled) {
            canvas.drawBitmap(portrait, null, centerCrop(portrait, rect), imagePaint)
        } else {
            canvas.drawRect(rect, Paint().apply { color = Color.parseColor("#16161C") })
        }

        if (showLabels) drawTileLabel(canvas, rect, person, style)
        canvas.restore()

        // Outside the clip, so a stroke sits crisply on the tile's edge instead
        // of being halved by it.
        val strokeArgb = style.border.tileStrokeArgb
        if (strokeArgb != null && style.tileStrokeWidth > 0f) {
            canvas.drawRoundRect(
                RectF(rect).apply { inset(style.tileStrokeWidth / 2f, style.tileStrokeWidth / 2f) },
                radius,
                radius,
                strokePaint.apply {
                    color = strokeArgb
                    strokeWidth = style.tileStrokeWidth
                },
            )
        }
    }

    private fun drawTileLabel(canvas: Canvas, rect: RectF, person: Person, style: Style) {
        // Scrim so the label stays readable over a bright frame. Always dark
        // with white text on top: the label sits on the photo, not on the sheet,
        // so it does not follow the light styles' ink.
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

        val padding = style.tilePadding
        val count = person.appearanceCount
        labelPaint.textSize = LABEL_TEXT * style.scale
        chipPaint.textSize = CHIP_TEXT * style.scale
        chipPaint.color = style.border.tileStrokeArgb?.takeIf { Color.alpha(it) > 200 }
            ?: Color.parseColor("#FF2E88")

        canvas.drawText(
            person.label,
            rect.left + padding,
            rect.bottom - padding - labelPaint.textSize * 0.9f,
            chipPaint,
        )
        canvas.drawText(
            if (count == 1) "1 appearance" else "$count appearances",
            rect.left + padding,
            rect.bottom - padding,
            labelPaint,
        )
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

    private fun drawBackground(canvas: Canvas, style: Style) {
        val w = style.width.toFloat()
        val h = style.height.toFloat()
        when (val background = style.border.background) {
            is CollageBackground.Solid -> canvas.drawColor(background.argb)
            is CollageBackground.Gradient -> {
                canvas.drawColor(background.base)
                canvas.drawRect(
                    RectF(0f, 0f, w, h),
                    Paint().apply {
                        shader = LinearGradient(
                            0f, 0f, w, h,
                            background.colors,
                            background.stops,
                            Shader.TileMode.CLAMP,
                        )
                    },
                )
            }
        }
    }

    /** Corner darkening over the finished sheet, for the styles that ask for it. */
    private fun drawVignette(canvas: Canvas, style: Style) {
        val argb = style.border.vignetteArgb ?: return
        val w = style.width.toFloat()
        val h = style.height.toFloat()
        canvas.drawRect(
            RectF(0f, 0f, w, h),
            Paint().apply {
                shader = RadialGradient(
                    w / 2f, h / 2f, maxOf(w, h) * 0.72f,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, argb),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
    }

    private fun drawHeader(canvas: Canvas, style: Style) {
        titlePaint.textSize = TITLE_TEXT * style.scale
        titlePaint.color = style.chromeColor
        canvas.drawText(
            "Roll Call",
            style.margin,
            style.headerHeight - 46f * style.scale,
            titlePaint,
        )
    }

    private fun drawFooter(canvas: Canvas, analysis: VideoAnalysis, style: Style) {
        val people = analysis.people.size
        val seconds = analysis.durationMs / 1000
        val text = "$people ${if (people == 1) "person" else "people"} · " +
            "${analysis.totalAppearances} appearances · " +
            "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        footerPaint.textSize = FOOTER_TEXT * style.scale
        footerPaint.color = style.chromeMuted
        canvas.drawText(
            text,
            style.margin,
            style.height - style.footerHeight + 64f * style.scale,
            footerPaint,
        )
    }

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TITLE_TEXT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = -0.02f
    }

    private val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A3FFFFFF")
        textSize = FOOTER_TEXT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = LABEL_TEXT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2E88")
        textSize = CHIP_TEXT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920

        /** Wide enough to judge a style in the strip, small enough to be cheap. */
        const val THUMB_WIDTH = 150
        const val THUMB_HEIGHT = THUMB_WIDTH * HEIGHT / WIDTH

        private const val GUTTER = 16f
        private const val HEADER_HEIGHT = 150f
        private const val FOOTER_HEIGHT = 120f
        private const val TILE_PADDING = 28f
        private const val TILE_ASPECT = 1.25f
        private const val SCRIM_FRACTION = 0.38f
        private const val TITLE_TEXT = 62f
        private const val FOOTER_TEXT = 34f
        private const val LABEL_TEXT = 30f
        private const val CHIP_TEXT = 40f
    }
}
