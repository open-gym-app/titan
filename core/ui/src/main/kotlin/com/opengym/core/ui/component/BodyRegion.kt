package com.opengym.core.ui.component

import com.opengym.core.domain.model.MuscleGroup
import java.util.Locale

/**
 * Which way round the figure is drawn.
 *
 * Two views rather than one, because half the body is not visible from the front and a
 * diagram that highlighted "back" by tinting the torso would be lying about which muscle was
 * worked. [BodyDiagram] draws both side by side; [BodyFigure] draws one.
 */
enum class BodyView {
    FRONT,
    BACK,
}

/**
 * A part of the drawn figure that can be highlighted.
 *
 * This is a *drawing* vocabulary, not a domain one, which is why it lives in `:core:ui`:
 * [com.opengym.core.domain.model.MuscleGroup] is a row in a table the user can add to, and
 * a row cannot be drawn. The seventeen seeded groups map onto it one for one — see
 * [MuscleGroup.bodyRegion] — and anything else maps to null and is simply not drawn, which
 * is the honest outcome for a muscle the illustration does not have a shape for.
 *
 * @property views the views this region is visible in. Most are visible from one side only;
 *   the neck, shoulders, forearms, calves and the two hip groups wrap and are drawn in both,
 *   so highlighting `SHOULDERS` marks the deltoid in the front view and the rear delt in the
 *   back one rather than picking a side.
 */
enum class BodyRegion(vararg views: BodyView) {
    NECK(BodyView.FRONT, BodyView.BACK),
    TRAPS(BodyView.BACK),
    SHOULDERS(BodyView.FRONT, BodyView.BACK),
    CHEST(BodyView.FRONT),
    LATS(BodyView.BACK),
    MIDDLE_BACK(BodyView.BACK),
    LOWER_BACK(BodyView.BACK),
    ABDOMINALS(BodyView.FRONT),
    BICEPS(BodyView.FRONT),
    TRICEPS(BodyView.BACK),
    FOREARMS(BodyView.FRONT, BodyView.BACK),
    GLUTES(BodyView.BACK),
    ABDUCTORS(BodyView.FRONT, BodyView.BACK),
    ADDUCTORS(BodyView.FRONT, BodyView.BACK),
    QUADRICEPS(BodyView.FRONT),
    HAMSTRINGS(BodyView.BACK),
    CALVES(BodyView.FRONT, BodyView.BACK),
    ;

    val views: Set<BodyView> = views.toSet()

    /** Whether this region has a shape in [view]. */
    fun isVisibleIn(view: BodyView): Boolean = view in this.views
}

/**
 * The shape this muscle group is drawn as, or null if the illustration has none for it.
 *
 * Keyed on [MuscleGroup.name] rather than on the row id, for the reason
 * [com.opengym.core.ui.component.displayName] gives: the ids are local rowids the seed
 * generator assigns by list order, so a table keyed on them goes quietly wrong the first
 * time that list is reordered. The names are the taxonomy itself.
 *
 * Null is a normal answer, not a failure. A user who adds "rear delts" as their own group
 * gets no highlight rather than a wrong one, and every caller here is drawing an
 * illustration alongside a written list of the same muscles — so nothing is lost that the
 * user cannot read two inches away.
 */
fun MuscleGroup.bodyRegion(): BodyRegion? = SEEDED_REGIONS[name.uppercase(Locale.ROOT)]

private val SEEDED_REGIONS: Map<String, BodyRegion> = mapOf(
    "ABDOMINALS" to BodyRegion.ABDOMINALS,
    "ABDUCTORS" to BodyRegion.ABDUCTORS,
    "ADDUCTORS" to BodyRegion.ADDUCTORS,
    "BICEPS" to BodyRegion.BICEPS,
    "CALVES" to BodyRegion.CALVES,
    "CHEST" to BodyRegion.CHEST,
    "FOREARMS" to BodyRegion.FOREARMS,
    "GLUTES" to BodyRegion.GLUTES,
    "HAMSTRINGS" to BodyRegion.HAMSTRINGS,
    "LATS" to BodyRegion.LATS,
    "LOWER_BACK" to BodyRegion.LOWER_BACK,
    "MIDDLE_BACK" to BodyRegion.MIDDLE_BACK,
    "NECK" to BodyRegion.NECK,
    "QUADRICEPS" to BodyRegion.QUADRICEPS,
    "SHOULDERS" to BodyRegion.SHOULDERS,
    "TRAPS" to BodyRegion.TRAPS,
    "TRICEPS" to BodyRegion.TRICEPS,
)
