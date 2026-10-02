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

/** Cheapest to analyse first: coins, then copper, then name. */
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

fun costText(m: GhMutation): String = when {
    m.analysisCoins <= 0 && m.analysisCopper <= 0 -> "unknown"
    else -> "${formatAmount(m.analysisCoins)} coins, ${formatAmount(m.analysisCopper.toLong())} copper"
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

/** One line of the Rose Dragon needs list. [need] 0 marks an informational line (no colouring). */
data class NeedLine(val label: String, val have: Int?, val need: Int, val indent: Int, val heading: Boolean)

const val HELIANTHUS_PER_CONDENSED = GreenhouseGoals.HELIANTHUS_PER_CONDENSED
val ROSE_DRAGON_MUTATIONS get() = GreenhouseGoals.roseDragonMutations

private fun squash(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

fun findMutation(data: GhData, name: String): GhMutation? {
    val key = squash(name)
    return data.mutations.firstOrNull { squash(it.name) == key }
}

fun requirementLines(m: GhMutation, data: GhData, stock: GhStock, indent: Int): List<NeedLine> =
    m.requirements.map { r ->
        val name = data.nameOf(r.crop)
        NeedLine("${r.count}x $name", stock.count(name), 0, indent, false)
    }

fun roseDragonNeeds(data: GhData, stock: GhStock): List<NeedLine> = buildList {
    add(NeedLine("Condensed Helianthus", stock.count("Condensed Helianthus"), GreenhouseGoals.CONDENSED_NEEDED, 0, true))
    add(NeedLine("Helianthus (${GreenhouseGoals.CONDENSED_NEEDED} x $HELIANTHUS_PER_CONDENSED)", stock.count("Helianthus"), GreenhouseGoals.CONDENSED_NEEDED * HELIANTHUS_PER_CONDENSED, 1, false))
    for (name in ROSE_DRAGON_MUTATIONS) {
        add(NeedLine(name, stock.count(name), 1, 0, true))
        findMutation(data, name)?.let { addAll(requirementLines(it, data, stock, 1)) }
    }
}

/** Short lines describing a mutation for the browser's detail pane. */
fun detailLines(m: GhMutation, data: GhData, stock: GhStock): List<String> = buildList {
    add("${rarityCode(m.rarity)}${prettify(m.rarity)} ${m.name}")
    add("§7Size §f${m.size}x${m.size}§7, soil §f${prettify(m.soil)}")
    add("§7Growth stages §f${m.growthStages}")
    add("§7Watering §f${if (m.requiresWatering) "required" else "not needed"}")
    add("§7Analysis §e${formatAmount(m.analysisCoins)} §7coins")
    add("§7         §e${formatAmount(m.analysisCopper.toLong())} §7copper")
    if (m.firstAnalysisCopper > 0) add("§7First time §e${formatAmount(m.firstAnalysisCopper.toLong())} §7copper")
    add("§7Requires nearby:")
    for (r in m.requirements) {
        val name = data.nameOf(r.crop)
        val have = stock.count(name)
        val tail = if (have == null) "" else if (have > 0) " §a($have)" else " §c(0)"
        add("§f ${r.count}x $name$tail")
    }
}
