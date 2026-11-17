package com.titan.core.domain.gamification

import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.model.UnlockCondition
import com.titan.core.domain.model.UnlockProgress
import com.titan.core.domain.model.Unlockable

/**
 * Everything that unlocks with the user's training, and which of it a given progress has earned.
 *
 * The places today; gear joins this list (#291). The keys share one stored table, so they must
 * not collide — a test holds that.
 */
object Unlocks {

    /** Every unlockable this build knows about. */
    val ALL: List<Unlockable> = TitanPlace.entries

    /**
     * The keys [progress] has earned that are worth storing: met, and not owned from hatch
     * (those are always owned and never written).
     */
    fun earned(progress: UnlockProgress): Set<String> =
        ALL.filter { it.condition != UnlockCondition.FromHatch && it.condition.isMetBy(progress) }
            .mapTo(mutableSetOf()) { it.key }
}
