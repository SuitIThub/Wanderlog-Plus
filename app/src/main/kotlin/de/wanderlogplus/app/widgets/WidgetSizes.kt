package de.wanderlogplus.app.widgets

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** The size steps every widget lays out for (width × height); a placed widget uses the largest that fits. */
enum class Bucket(val size: DpSize) {
    TINY(DpSize(57.dp, 57.dp)),
    STRIP(DpSize(180.dp, 57.dp)),
    SMALL(DpSize(110.dp, 110.dp)),
    MEDIUM(DpSize(180.dp, 110.dp)),
    WIDE(DpSize(250.dp, 110.dp)),
    TALL(DpSize(180.dp, 250.dp)),
    LARGE(DpSize(250.dp, 250.dp)),
    ;

    /** At least two rows of content (SMALL and up). */
    val roomy: Boolean get() = this != TINY && this != STRIP

    /** Tall enough for a list (TALL, LARGE). */
    val big: Boolean get() = this == TALL || this == LARGE

    /** Wide enough for content beside each other (STRIP, MEDIUM, WIDE, TALL, LARGE). */
    val wide: Boolean get() = this != TINY && this != SMALL
}

val ALL_SIZES: Set<DpSize> = Bucket.entries.map { it.size }.toSet()

private val PRIORITY = listOf(Bucket.LARGE, Bucket.TALL, Bucket.WIDE, Bucket.MEDIUM, Bucket.SMALL, Bucket.STRIP)

/** The largest bucket whose width and height both fit into [size]; TINY when none does. */
fun bucketOf(size: DpSize): Bucket =
    PRIORITY.firstOrNull { size.width >= it.size.width && size.height >= it.size.height } ?: Bucket.TINY
