package dev.nytrix.nyaddons.features.greenhouse

/** How much of a [base] picture a new layout keeps: [reused] squares hold the same crop in both, [added] are new squares to plant. */
class GhReuse(val reused: Int, val added: Int) {
    /** The one-line readout for the planner side panel. */
    fun text() = "§7Reuses §f$reused §7placed ${if (reused == 1) "square" else "squares"}, §f$added §7new"

    companion object {
        /** Compares [next] with [base] cell by cell; null when there is no base picture or it holds nothing. */
        fun of(base: GhLayout?, next: GhLayout): GhReuse? {
            if (base == null || base.size != next.size) return null
            var reused = 0
            var added = 0
            var baseCells = 0
            for (r in 0 until next.size) for (c in 0 until next.size) {
                val b = base.cells[r][c]
                if (b != null) baseCells++
                val n = next.cells[r][c] ?: continue
                if (b == n) reused++ else added++
            }
            return if (baseCells == 0) null else GhReuse(reused, added)
        }
    }
}

/**
 * Plans a single mutation through SkyShards ([SkyShards]): one block of it on the unlocked squares. Blocks, so call from a background
 * thread; throws [SkyShardsException] when the server cannot be used, returns null when no layout fits.
 */
object GreenhousePlannerImpl : GhPlanner {

    override fun plan(target: GhMutation): GhLayout? = plan(target, null)

    override fun plan(target: GhMutation, unlocked: BooleanArray?): GhLayout? {
        if (target.id in GreenhouseGoals.skippedMutations) return null
        return SkyShards.solve(listOf(SkyGoal(target.id, 1)), unlocked)?.layout(target.id)
    }
}
