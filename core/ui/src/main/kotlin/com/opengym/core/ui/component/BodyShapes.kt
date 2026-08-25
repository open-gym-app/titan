/*
 * Suppressed for this file and nowhere else, because this file is not code that happens to
 * contain numbers — it *is* numbers:
 *
 * - `MagicNumber`: every literal below is one coordinate of one anatomical shape. Naming
 *   `34.5f` would produce two hundred constants like `PECTORAL_OUTER_EDGE_X`, which is not
 *   more readable than the curve it is a control point of — it is the same information with
 *   a layer of indirection over it, and the way to check any of it is to look at the drawing.
 * - `LongParameterList`: `cubicTo` takes three points. That is what a cubic Bézier is.
 * - `TooManyFunctions`: one function per body part is the decomposition; the alternative is
 *   one function with every shape in it.
 */
@file:Suppress("MagicNumber", "LongParameterList", "TooManyFunctions")

package com.opengym.core.ui.component

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path

/*
 * The figure, as coordinates.
 *
 * Every shape below is written in one fixed space — [BODY_WIDTH] x [BODY_HEIGHT], origin
 * top-left — and scaled to whatever the caller gives the canvas. Authoring in a fixed space
 * rather than in fractions of the drawing size is what keeps the numbers readable as
 * anatomy: the chest sits at y 39..64 of 220 and stays there whether the diagram is rendered
 * at 56dp on a home card or at 160dp on the summary.
 *
 * The figure is symmetric about the midline, so every paired shape is written once and
 * reflected. Those are the `Sketch.()` extensions: they *draw into* a sketch rather than
 * returning a path, which is what lets [mirrored] run the same code twice against two
 * sketches. Reflecting per coordinate, rather than transforming a finished path, keeps the
 * winding correct — a matrix with a negative determinant reverses it, and two sub-paths of
 * opposite winding in one `Path` subtract instead of union.
 *
 * The two views are built once, lazily, and shared by every diagram on screen. Nothing here
 * allocates per frame.
 */

/** The width of the space every shape in this file is written in. */
internal const val BODY_WIDTH = 100f

/** The height of that space, and so the figure's aspect ratio. */
internal const val BODY_HEIGHT = 220f

/**
 * One view of the figure: the parts that are never highlighted, the parts that can be, and
 * the marks drawn over both.
 *
 * @property structure head, neck column, limbs, hands and feet — the mass the muscles sit
 *   on. Filled in the resting tone in every state, so a diagram with nothing highlighted
 *   still reads as a body rather than as a scatter of shapes.
 * @property regions the highlightable shapes, keyed by what they are. A region absent from
 *   this map is not visible in this view. **Iteration order is paint order** — the map is a
 *   `LinkedHashMap`, and the entries are written in the order the muscles have to be laid
 *   down: an adductor is a strip on the inner thigh that the quadriceps overlaps, so it is
 *   written after it and painted over it.
 * @property details hairline-only marks with no fill: the ab segments, the spine. They are
 *   what stops the torso reading as one blank slab.
 */
internal class BodyGeometry(
    val structure: List<Path>,
    val regions: Map<BodyRegion, List<Path>>,
    val details: List<Path>,
)

/** The geometry for [view]. */
internal fun bodyGeometry(view: BodyView): BodyGeometry =
    when (view) {
        BodyView.FRONT -> FRONT_BODY
        BodyView.BACK -> BACK_BODY
    }

private val FRONT_BODY: BodyGeometry by lazy {
    BodyGeometry(
        structure = commonStructure(),
        regions = mapOf(
            BodyRegion.NECK to shape { neck() },
            BodyRegion.SHOULDERS to mirrored { deltoid() },
            BodyRegion.CHEST to mirrored { pectoral() },
            BodyRegion.ABDOMINALS to shape { abdominals() },
            BodyRegion.BICEPS to mirrored { upperArmMuscle() },
            BodyRegion.FOREARMS to mirrored { forearmMuscle() },
            BodyRegion.QUADRICEPS to mirrored { quadriceps() },
            BodyRegion.ADDUCTORS to mirrored { adductor() },
            BodyRegion.ABDUCTORS to mirrored { abductorFront() },
            BodyRegion.CALVES to mirrored { calf() },
        ),
        details = abdominalSegments(),
    )
}

private val BACK_BODY: BodyGeometry by lazy {
    BodyGeometry(
        structure = commonStructure(),
        regions = mapOf(
            BodyRegion.NECK to shape { neck() },
            BodyRegion.TRAPS to shape { trapezius() },
            BodyRegion.SHOULDERS to mirrored { deltoid() },
            BodyRegion.LATS to mirrored { latissimus() },
            BodyRegion.MIDDLE_BACK to shape { middleBack() },
            BodyRegion.LOWER_BACK to shape { lowerBack() },
            BodyRegion.TRICEPS to mirrored { upperArmMuscle() },
            BodyRegion.FOREARMS to mirrored { forearmMuscle() },
            BodyRegion.GLUTES to mirrored { gluteus() },
            BodyRegion.HAMSTRINGS to mirrored { hamstring() },
            BodyRegion.ADDUCTORS to mirrored { adductor() },
            BodyRegion.ABDUCTORS to mirrored { abductorBack() },
            BodyRegion.CALVES to mirrored { calf() },
        ),
        details = shape { spine() },
    )
}

/**
 * The parts that carry no muscle of their own.
 *
 * Shared by both views: from the outline alone a front and a back figure are the same
 * silhouette, and keeping two copies would be two things to hold in step for no visible
 * difference.
 */
private fun commonStructure(): List<Path> =
    buildList {
        addAll(shape { head() })
        addAll(shape { torso() })
        addAll(mirrored { arm() })
        addAll(mirrored { leg() })
    }

// ─── Structure ──────────────────────────────────────────────────────────────────────────

private fun Sketch.head() = oval(cx = 50f, cy = 15f, rx = 8.5f, ry = 11.5f)

private fun Sketch.neck() = roundRect(left = 44f, top = 22f, right = 56f, bottom = 37f, radius = 3f)

/** Shoulders to hips: a V-taper, 39 wide across the deltoids and 28 at the hip. */
private fun Sketch.torso() {
    moveTo(32f, 35f)
    lineTo(68f, 35f)
    cubicTo(70f, 35f, 70f, 38f, 69.5f, 44f)
    cubicTo(68f, 58f, 66f, 68f, 65f, 78f)
    cubicTo(64f, 88f, 64f, 96f, 64f, 102f)
    cubicTo(64f, 110f, 58f, 114f, 50f, 114f)
    cubicTo(42f, 114f, 36f, 110f, 36f, 102f)
    cubicTo(36f, 96f, 36f, 88f, 35f, 78f)
    cubicTo(34f, 68f, 32f, 58f, 30.5f, 44f)
    cubicTo(30f, 38f, 30f, 35f, 32f, 35f)
    close()
}

/**
 * One whole arm, shoulder to hand, as a single tapered outline.
 *
 * Three stacked ovals — upper arm, forearm, hand — was the first attempt and it reads as
 * three stacked ovals: at any size the seams show, and the hand in particular floats. A limb
 * is one silhouette that narrows from the deltoid to the wrist, so it is drawn as one, and
 * the muscles that sit inside it are painted on top.
 */
private fun Sketch.arm() {
    moveTo(34f, 36f)
    cubicTo(26f, 36f, 17f, 42f, 17f, 56f)
    cubicTo(17f, 66f, 15f, 74f, 13.5f, 88f)
    cubicTo(12.5f, 97f, 12f, 105f, 14f, 110f)
    cubicTo(16f, 115f, 21.5f, 114f, 22.5f, 107f)
    cubicTo(23.5f, 101f, 23f, 95f, 24f, 88f)
    cubicTo(25.5f, 76f, 28f, 68f, 30f, 58f)
    cubicTo(32f, 48f, 33f, 41f, 34f, 36f)
    close()
}

/** One whole leg, hip to foot. Same reasoning as [arm]. */
private fun Sketch.leg() {
    moveTo(50f, 106f)
    cubicTo(42f, 105f, 31f, 108f, 30f, 122f)
    cubicTo(29.5f, 136f, 32f, 148f, 33f, 160f)
    cubicTo(34f, 172f, 31.5f, 184f, 32f, 196f)
    cubicTo(32.2f, 204f, 31f, 213f, 37.5f, 213f)
    cubicTo(44f, 213f, 45.5f, 205f, 45.5f, 196f)
    cubicTo(45.5f, 184f, 45f, 172f, 46f, 160f)
    cubicTo(47f, 148f, 49f, 136f, 49.5f, 122f)
    cubicTo(50f, 114f, 50f, 110f, 50f, 106f)
    close()
}

// ─── Front muscles ──────────────────────────────────────────────────────────────────────

private fun Sketch.deltoid() = oval(cx = 30f, cy = 43f, rx = 8.5f, ry = 9f)

/** One pectoral: square at the sternum, rounded away from it. */
private fun Sketch.pectoral() {
    moveTo(49f, 39f)
    cubicTo(43f, 37f, 36f, 38.5f, 34.5f, 44f)
    cubicTo(32.5f, 51f, 34f, 59f, 38f, 62.5f)
    cubicTo(44f, 65f, 49f, 62f, 49f, 57f)
    close()
}

private fun Sketch.abdominals() = roundRect(left = 42f, top = 66f, right = 58f, bottom = 100f, radius = 3f)

/** The six-pack, as hairlines rather than as separate fillable shapes. */
private fun abdominalSegments(): List<Path> =
    buildList {
        addAll(shape { line(50f, 68f, 50f, 96f) })
        listOf(76f, 85f, 94f).forEach { y -> addAll(shape { line(43.5f, y, 56.5f, y) }) }
    }

private fun Sketch.upperArmMuscle() = oval(cx = 23.5f, cy = 55f, rx = 5.5f, ry = 12f)

private fun Sketch.forearmMuscle() = oval(cx = 19f, cy = 87f, rx = 5f, ry = 13f)

private fun Sketch.abductorFront() = oval(cx = 34.5f, cy = 116f, rx = 4.5f, ry = 8f)

private fun Sketch.adductor() = oval(cx = 46.5f, cy = 130f, rx = 3f, ry = 18f)

private fun Sketch.quadriceps() = oval(cx = 40f, cy = 132f, rx = 8f, ry = 22f)

private fun Sketch.calf() = oval(cx = 39f, cy = 176f, rx = 6f, ry = 17f)

// ─── Back muscles ───────────────────────────────────────────────────────────────────────

/** The kite from the base of the skull out to both shoulders and down to mid-back. */
private fun Sketch.trapezius() {
    moveTo(50f, 23f)
    lineTo(62f, 39f)
    cubicTo(60f, 48f, 56f, 58f, 50f, 66f)
    cubicTo(44f, 58f, 40f, 48f, 38f, 39f)
    close()
}

/** One lat: the outer edge follows the ribcage, the inner edge stops short of the spine. */
private fun Sketch.latissimus() {
    moveTo(37f, 45f)
    cubicTo(32f, 52f, 31.5f, 64f, 34.5f, 76f)
    cubicTo(37f, 85f, 42f, 89f, 46.5f, 89.5f)
    lineTo(48f, 70f)
    cubicTo(48.5f, 58f, 48f, 50f, 47f, 45f)
    close()
}

private fun Sketch.middleBack() = roundRect(left = 44f, top = 68f, right = 56f, bottom = 88f, radius = 3f)

private fun Sketch.lowerBack() = roundRect(left = 45f, top = 90f, right = 55f, bottom = 108f, radius = 3f)

private fun Sketch.gluteus() = oval(cx = 42f, cy = 110f, rx = 8f, ry = 9.5f)

private fun Sketch.abductorBack() = oval(cx = 33.5f, cy = 112f, rx = 4.5f, ry = 8f)

private fun Sketch.hamstring() = oval(cx = 40f, cy = 134f, rx = 8f, ry = 20f)

private fun Sketch.spine() = line(50f, 26f, 50f, 106f)

// ─── Sketching ──────────────────────────────────────────────────────────────────────────

/**
 * Builds [block] once, as written.
 *
 * A single-element list rather than a bare `Path` so that a region's shapes are always a
 * list, whether it is one shape or a mirrored pair, and the drawing loop has one case.
 */
private fun shape(block: Sketch.() -> Unit): List<Path> = listOf(Sketch(mirror = false).apply(block).path)

/** Builds [block] twice — as written, and reflected about the figure's midline. */
private fun mirrored(block: Sketch.() -> Unit): List<Path> =
    listOf(
        Sketch(mirror = false).apply(block).path,
        Sketch(mirror = true).apply(block).path,
    )

/**
 * A [Path] builder whose x coordinates are reflected about the figure's midline as they are
 * written, when asked.
 *
 * Every shape is authored as an extension on this receiver rather than as a function
 * returning a path, so the same code can be run twice against two sketches. A shape that
 * built its own path would silently draw nothing when handed to [mirrored].
 */
private class Sketch(private val mirror: Boolean) {

    val path = Path()

    private fun x(value: Float): Float = if (mirror) BODY_WIDTH - value else value

    fun moveTo(
        x: Float,
        y: Float,
    ) = path.moveTo(x(x), y)

    fun lineTo(
        x: Float,
        y: Float,
    ) = path.lineTo(x(x), y)

    fun cubicTo(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x3: Float,
        y3: Float,
    ) = path.cubicTo(x(x1), y1, x(x2), y2, x(x3), y3)

    fun close() = path.close()

    fun oval(
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
    ) {
        val centre = x(cx)
        path.addOval(Rect(centre - rx, cy - ry, centre + rx, cy + ry))
    }

    fun roundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
    ) {
        val edges = listOf(x(left), x(right))
        path.addRoundRect(RoundRect(Rect(edges.min(), top, edges.max(), bottom), CornerRadius(radius)))
    }

    /** A stroke-only mark. Closed shapes are filled; this one has no interior on purpose. */
    fun line(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ) {
        path.moveTo(x(startX), startY)
        path.lineTo(x(endX), endY)
    }
}
