package com.shivansh.rollcall.domain.model

/**
 * How good a shot of a face is, in [0,1].
 *
 * Drives three decisions at once: which faces are worth embedding, how much each
 * one counts toward its tracklet's average, and which frame becomes the person's
 * representative shot.
 */
object FaceQuality {
    const val SHARPNESS = 0.35f
    const val FRONTALITY = 0.30f
    const val EYES_OPEN = 0.15f
    const val FACE_SIZE = 0.12f
    const val EXPRESSION = 0.08f

    fun score(s: FaceSample): Float = (
        SHARPNESS * s.sharpness +
            FRONTALITY * s.frontality +
            EYES_OPEN * s.eyesOpen +
            FACE_SIZE * s.sizeRatio +
            EXPRESSION * s.expression
        ).coerceIn(0f, 1f)

    /**
     * How good a shot is as the person's one portrait.
     *
     * Stricter than [score]. A face that shares the frame, runs off its edge, is
     * caught mid-turn or is soft still embeds fine, but makes a poor picture.
     * Sharing the frame costs most: anyone seen alone anywhere should get that
     * shot, not a tile with someone else in it.
     */
    fun portraitScore(s: FaceSample): Float {
        var v = score(s)
        if (s.coFaces.isNotEmpty()) v -= SHARED_FRAME_PENALTY
        if (s.isClipped) v -= CLIPPED_PENALTY
        if (s.frontality < PROFILE_FLOOR) v -= PROFILE_PENALTY
        if (s.sharpness < SOFT_FLOOR) v -= SOFT_PENALTY
        return v
    }

    const val SHARED_FRAME_PENALTY = 0.40f
    const val CLIPPED_PENALTY = 0.25f
    const val PROFILE_FLOOR = 0.55f
    const val PROFILE_PENALTY = 0.20f
    const val SOFT_FLOOR = 0.25f
    const val SOFT_PENALTY = 0.20f

    /**
     * Frontality from head pose. Yaw and pitch multiply, so turning away in
     * either axis costs; roll is ignored because alignment corrects it anyway.
     */
    fun frontality(yawDegrees: Float, pitchDegrees: Float): Float {
        val yaw = Math.toRadians(yawDegrees.toDouble())
        val pitch = Math.toRadians(pitchDegrees.toDouble())
        return (Math.cos(yaw) * Math.cos(pitch)).toFloat().coerceAtLeast(0f)
    }
}
