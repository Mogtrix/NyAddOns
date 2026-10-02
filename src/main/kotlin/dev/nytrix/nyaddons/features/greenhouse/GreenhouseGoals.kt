package dev.nytrix.nyaddons.features.greenhouse

/** An item the player is short of: [name] (display name), how many are [need]ed and how many are [have]. */
class GhMissing(val name: String, val have: Int, val need: Int) {
    val missing get() = (need - have).coerceAtLeast(0)
}

/** A mutation still to analyse. [canStartNow] is true when nothing is missing. */
class GhUniqueEntry(val mutation: GhMutation, val canStartNow: Boolean, val missing: List<GhMissing>)

class GhMilestoneTier(val tier: Int, val roman: String, val threshold: Int, val reward: String)

/** [current] is the highest tier reached (null if none), [next] the one after (null when all are done). */
class GhMilestoneState(
    val analysed: Int,
    val current: GhMilestoneTier?,
    val next: GhMilestoneTier?,
    val moreNeeded: Int,
)

/** One line of the Rose Dragon shopping list. [parts] are what that item itself is made from (empty for plain crops). */
class GhNeed(val name: String, val have: Int, val need: Int, val parts: List<GhMissing>) {
    val missing get() = (need - have).coerceAtLeast(0)
}

object GreenhouseGoals {

    private const val EACH = "+10 Crop Growth, +5 Farming Fortune, +15 SkyBlock XP"

    val tiers: List<GhMilestoneTier> = listOf(
        GhMilestoneTier(1, "I", 1, "Bioanalysis Talisman, Small Mutations Sack; $EACH"),
        GhMilestoneTier(2, "II", 10, "Overgrown Grass, upgraded HydroCan; $EACH"),
        GhMilestoneTier(3, "III", 15, "Medium Mutations Sack recipe; $EACH"),
        GhMilestoneTier(4, "IV", 20, "Bioanalysis Ring upgrade; $EACH"),
        GhMilestoneTier(5, "V", 30, "Large Mutations Sack recipe, Estate Greenhouse Skin; $EACH"),
        GhMilestoneTier(6, "VI", 40, "Bioanalysis Artifact upgrade; $EACH"),
    )

    /** Crop Condensed Helianthus is made of, and how many Helianthus one takes (the wiki says 9). */
    const val HELIANTHUS_PER_CONDENSED = 9
    const val CONDENSED_NEEDED = 5

    /** Mutations left out of the checklist: Shellfruit is a secret explosion event and Jerryflower needs quest items. */
    val skippedMutations = setOf("shellfruit", "jerryflower")

    val roseDragonMutations = listOf("Glasscorn", "Devourer", "All-in Aloe", "Phantomleaf", "Timestalk")

    /**
     * The mutations not yet in [analysed], cheapest to analyse first (coins, then copper, then name).
     * [have] gives how many of an item the player holds by display name, or null if unknown.
     * A requirement whose item the player is not known to hold (null) counts as satisfied: there is no way
     * to tell "none" from "never seen", and a crop id with no sack/inventory item (like `fire`) must not block.
     * Requirements are adjacency counts of crops placed in the greenhouse, so the item needed is one per placed crop.
     */
    fun uniqueOrder(data: GhData, have: (String) -> Int?, analysed: Set<String>): List<GhUniqueEntry> {
        val remaining = data.mutations.filter { it.id !in analysed && it.id !in skippedMutations }
            .sortedWith(compareBy<GhMutation>({ it.analysisCoins }, { it.analysisCopper }, { it.name }))
        val result = ArrayList<GhUniqueEntry>(remaining.size)
        for (mutation in remaining) {
            var missing: ArrayList<GhMissing>? = null
            for (requirement in mutation.requirements) {
                val name = data.nameOf(requirement.crop)
                val owned = have(name) ?: continue
                if (owned < requirement.count) (missing ?: ArrayList<GhMissing>().also { missing = it }).add(GhMissing(name, owned, requirement.count))
            }
            result.add(GhUniqueEntry(mutation, missing == null, missing ?: emptyList()))
        }
        return result
    }

    fun milestones(analysedCount: Int): GhMilestoneState {
        val current = tiers.lastOrNull { analysedCount >= it.threshold }
        val next = tiers.firstOrNull { analysedCount < it.threshold }
        return GhMilestoneState(analysedCount, current, next, if (next == null) 0 else next.threshold - analysedCount)
    }

    /**
     * What the Rose Dragon needs: 5 Condensed Helianthus (also shown as 45 Helianthus) and one of each of five mutations,
     * each mutation with its own requirements from the data file. No costs.
     */
    fun roseDragonNeeds(data: GhData, have: (String) -> Int?): List<GhNeed> {
        val result = ArrayList<GhNeed>(8)
        val condensed = have("Condensed Helianthus") ?: 0
        val helianthus = have("Helianthus") ?: 0
        result.add(GhNeed("Condensed Helianthus", condensed, CONDENSED_NEEDED, emptyList()))
        result.add(GhNeed("Helianthus", helianthus, CONDENSED_NEEDED * HELIANTHUS_PER_CONDENSED, emptyList()))
        for (name in roseDragonMutations) {
            val mutation = data.mutations.firstOrNull { it.name == name }
            val parts = mutation?.requirements?.map { r ->
                val partName = data.nameOf(r.crop)
                GhMissing(partName, have(partName) ?: 0, r.count)
            } ?: emptyList()
            result.add(GhNeed(name, have(name) ?: 0, 1, parts))
        }
        return result
    }
}
