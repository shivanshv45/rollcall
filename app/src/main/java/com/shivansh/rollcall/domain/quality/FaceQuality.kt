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
     * Frontality from head pose. Yaw and pitch multiply, so turning away in
     * either axis costs; roll is ignored because alignment corrects it anyway.
     */
    fun frontality(yawDegrees: Float, pitchDegrees: Float): Float {
        val yaw = Math.toRadians(yawDegrees.toDouble())
        val pitch = Math.toRadians(pitchDegrees.toDouble())
        return (Math.cos(yaw) * Math.cos(pitch)).toFloat().coerceAtLeast(0f)
    }
}
