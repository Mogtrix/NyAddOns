package dev.nytrix.nyaddons.features.greenhouse

/*
 * Pure helpers for the Greenhouse window, kept in one file with plain data classes. The numbers shared with GreenhouseGoals
 * (milestones, Rose Dragon amounts) are read from there.
 */

/** DNA Analysis milestone thresholds (distinct analysed mutations). */
val MILESTONE_THRESHOLDS = IntArray(GreenhouseGoals.tiers.size) { GreenhouseGoals.tiers[it].threshold }

private val ROMAN = arrayOf("I", "II", "III", "IV", "V", "VI")

/** The next milestone tier: [tier] 1 to 6, at [threshold] mutations, with [remaining] still to analyse. */
data class Milestone(val tier: Int, val threshold: Int, val remaining: Int) {
    val roman get() = ROMAN[tier - 1]
}

fun nextMilestone(analysedCount: Int): Milestone? {
    for ((i, threshold) in MILESTONE_THRESHOLDS.withIndex()) {
        if (analysedCount < threshold) return Milestone(i + 1, threshold, threshold - analysedCount)
    }
    return null
}

/** Cheapest to analyse first (the order the checklist uses; the costs themselves are not shown), then by name. */
fun uniqueOrder(mutations: List<GhMutation>): List<GhMutation> =
    mutations.filter { it.id !in GreenhouseGoals.skippedMutations }.sortedWith(compareBy<GhMutation> { it.analysisCoins }.thenBy { it.analysisCopper }.thenBy { it.name })

/** 10_000_000 becomes `10M`, 5_000 `5k`, 1_500 `1.5k`. */
fun formatAmount(value: Long): String {
    fun scaled(unit: Long, suffix: String): String {
        val tenths = value * 10 / unit
        return if (tenths % 10 == 0L) "${tenths / 10}$suffix" else "${tenths / 10}.${tenths % 10}$suffix"
    }
    return when {
        value >= 1_000_000_000L -> scaled(1_000_000_000L, "B")
        value >= 1_000_000L -> scaled(1_000_000L, "M")
        value >= 1_000L -> scaled(1_000L, "k")
        else -> value.toString()
    }
}

/** Minecraft colour code for a rarity name. */
fun rarityCode(rarity: String): String = when (rarity.lowercase()) {
    "common" -> "§f"
    "uncommon" -> "§a"
    "rare" -> "§9"
    "epic" -> "§5"
    "legendary" -> "§6"
    "mythic" -> "§d"
    "divine" -> "§b"
    else -> "§7"
}

fun prettify(id: String): String = id.split('_', ' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

/** 0 = nothing known about the ingredients (or [amount] is 0), 1 = at least one is short for [amount] of them, 2 = all are in stock. */
fun ingredientState(m: GhMutation, data: GhData, stock: GhStock, amount: Int = 1): Int {
    if (amount <= 0) return 0
    var known = false
    var missing = false
    for (r in m.requirements) {
        val have = stock.count(data.nameOf(r.crop))
        if (have != null) known = true
        if (have != null && have < r.count * amount) missing = true
    }
    return if (missing) 1 else if (known) 2 else 0
}

private fun squash(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

fun findMutation(data: GhData, name: String): GhMutation? {
    val key = squash(name)
    return data.mutations.firstOrNull { squash(it.name) == key }
}

/** One line of the browser's detail pane: an optional [icon] id, then the text (with colour codes). */
data class DetailLine(val icon: String?, val text: String)

/** The lines describing a mutation for the browser's detail pane: facts, then what must grow next to it with what is held. */
fun detailLines(m: GhMutation, data: GhData, stock: GhStock): List<DetailLine> = buildList {
    add(DetailLine(null, "§7Size §f${m.size}x${m.size}§7, soil §f${prettify(m.soil)}"))
    add(DetailLine(null, "§7Growth stages §f${m.growthStages}"))
    add(DetailLine(null, "§7Watering §f${if (m.requiresWatering) "required" else "not needed"}"))
    add(DetailLine(null, "§7Requires nearby:"))
    for (r in m.requirements) {
        val name = data.nameOf(r.crop)
        val have = stock.count(name)
        val tail = if (have == null) "" else if (have > 0) " §a($have)" else " §c(0)"
        add(DetailLine(r.crop, "§f${r.count}x $name$tail"))
    }
}
