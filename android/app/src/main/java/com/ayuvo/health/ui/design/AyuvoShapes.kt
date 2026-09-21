package com.ayuvo.health.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** One radius per role (docs/ui-structure.md): cards 16, tiles/buttons 12, icon squares 7. */
object AyuvoShapes {
    val Card = RoundedCornerShape(16.dp)
    val Tile = RoundedCornerShape(12.dp)
    val Field = RoundedCornerShape(10.dp)
    val Capsule = RoundedCornerShape(50)
    val Icon = RoundedCornerShape(7.dp)
    val Sheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
}

val AyuvoMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)
