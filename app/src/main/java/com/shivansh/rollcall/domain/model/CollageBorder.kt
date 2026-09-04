package com.shivansh.rollcall.domain.model

/**
 * A frame style for the finished collage, picked from the strip under the
 * preview the way a camera filter is.
 *
 * A border is only ever paint around the tiles: the layout, the portraits and
 * their crops are the same whichever one is chosen. That keeps switching cheap
 * enough to feel instant, since the expensive part of a collage is decoding
 * faces out of the video and none of that has to be redone.
 *
 * The values are the whole recipe rather than a branch in the renderer, so a
 * new style is a new entry here and nothing else.
 *
 * @param id stable across releases; what gets persisted, not the ordinal.
 * @param label shown under its thumbnail in the picker.
 * @param background the sheet behind the tiles.
 * @param tileStrokeArgb outline drawn just inside each tile, null for none.
 * @param tileStrokeWidth thickness of that outline, in collage pixels.
 * @param tileRadius corner rounding of the tiles, in collage pixels.
 * @param tileInset shrinks each tile inside its slot, in collage pixels. Bigger
 *   values read as a wider mat between the photos.
 * @param margin the sheet's outer edge, in collage pixels.
 * @param vignetteArgb darkening drawn in the corners over everything, null for
 *   none. Nudges attention inwards on the busier styles.
 */
data class CollageBorder(
    val id: String,
    val label: String,
    val background: CollageBackground,
    val tileStrokeArgb: Int? = null,
    val tileStrokeWidth: Float = 0f,
    val tileRadius: Float = 28f,
    val tileInset: Float = 0f,
    val margin: Float = 48f,
    val vignetteArgb: Int? = null,
) {
    companion object {
        /**
         * The styles offered, in the order they appear in the picker.
         *
         * [DEFAULT] leads because it is what the collage looked like before
         * there was a choice, so an existing user's first render is unchanged.
         */
        val ALL: List<CollageBorder> by lazy {
            listOf(DEFAULT, FILM, POLAROID, NEON, PAPER, GOLD)
        }

        /** Falls back to [DEFAULT] for an id from a build that had more styles. */
        fun byId(id: String?): CollageBorder =
            ALL.firstOrNull { it.id == id } ?: DEFAULT

        /** The house style: the magenta-to-coral wash the rest of the app uses. */
        val DEFAULT = CollageBorder(
            id = "signature",
            label = "Signature",
            background = CollageBackground.Gradient(
                base = 0xFF0B0B0F.toInt(),
                colors = intArrayOf(0x33FF2E88, 0xFF0B0B0F.toInt(), 0x22FF6B4A),
                stops = floatArrayOf(0f, 0.55f, 1f),
            ),
        )

        /** Contact sheet: black, tight, square corners, thin sprocket rails. */
        val FILM = CollageBorder(
            id = "film",
            label = "Film",
            background = CollageBackground.Solid(0xFF08080A.toInt()),
            tileStrokeArgb = 0x2BFFFFFF,
            tileStrokeWidth = 2f,
            tileRadius = 4f,
            tileInset = 6f,
            margin = 64f,
            vignetteArgb = 0x59000000,
        )

        /** Thick white mat, as if each shot were mounted. */
        val POLAROID = CollageBorder(
            id = "polaroid",
            label = "Polaroid",
            background = CollageBackground.Solid(0xFFF4F1EA.toInt()),
            tileStrokeArgb = 0x14000000,
            tileStrokeWidth = 2f,
            tileRadius = 8f,
            tileInset = 14f,
            margin = 56f,
        )

        /** Lit edges on near-black, the loudest of the set. */
        val NEON = CollageBorder(
            id = "neon",
            label = "Neon",
            background = CollageBackground.Gradient(
                base = 0xFF07070C.toInt(),
                colors = intArrayOf(0x4438BDF8, 0xFF07070C.toInt(), 0x44FF2E88),
                stops = floatArrayOf(0f, 0.5f, 1f),
            ),
            tileStrokeArgb = 0xFFFF2E88.toInt(),
            tileStrokeWidth = 5f,
            tileRadius = 30f,
            tileInset = 4f,
            vignetteArgb = 0x4D000000,
        )

        /** Warm off-white with hairline rules; the quiet one. */
        val PAPER = CollageBorder(
            id = "paper",
            label = "Paper",
            background = CollageBackground.Solid(0xFFEDE7DC.toInt()),
            tileStrokeArgb = 0x2E1A1A1A,
            tileStrokeWidth = 1.5f,
            tileRadius = 20f,
            tileInset = 8f,
            margin = 52f,
        )

        /** Thin gold rules on ink, for something that reads as printed. */
        val GOLD = CollageBorder(
            id = "gold",
            label = "Gold",
            background = CollageBackground.Gradient(
                base = 0xFF101014.toInt(),
                colors = intArrayOf(0xFF14141A.toInt(), 0xFF0B0B0F.toInt()),
                stops = floatArrayOf(0f, 1f),
            ),
            tileStrokeArgb = 0xFFD4AF6A.toInt(),
            tileStrokeWidth = 3f,
            tileRadius = 16f,
            tileInset = 10f,
            margin = 60f,
            vignetteArgb = 0x40000000,
        )
    }
}

/** How the sheet behind the tiles is filled. */
sealed interface CollageBackground {
    data class Solid(val argb: Int) : CollageBackground

    /**
     * [base] is painted first so the translucent entries in [colors] have
     * something to sit on; the gradient runs corner to corner.
     */
    data class Gradient(
        val base: Int,
        val colors: IntArray,
        val stops: FloatArray,
    ) : CollageBackground {
        // Arrays in a data class compare by reference, which would make two
        // identical gradients unequal and defeat any equality check on a border.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Gradient) return false
            return base == other.base &&
                colors.contentEquals(other.colors) &&
                stops.contentEquals(other.stops)
        }

        override fun hashCode(): Int =
            (base * 31 + colors.contentHashCode()) * 31 + stops.contentHashCode()
    }
}
