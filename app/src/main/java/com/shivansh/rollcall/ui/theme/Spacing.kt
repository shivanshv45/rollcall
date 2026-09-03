package com.shivansh.rollcall.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The spacing scale. Values are picked to mean something - related things sit at
 * [xs] or [sm], separate ideas at [lg] or more - rather than everything landing
 * on the same default padding.
 */
object Space {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

/** Corner radii carry hierarchy: chips read tighter than cards. */
object Radius {
    val chip = RoundedCornerShape(12.dp)
    val tile = RoundedCornerShape(20.dp)
    val card = RoundedCornerShape(28.dp)
    val pill = RoundedCornerShape(999.dp)
}
