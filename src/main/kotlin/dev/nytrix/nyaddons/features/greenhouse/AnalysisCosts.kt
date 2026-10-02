package dev.nytrix.nyaddons.features.greenhouse

/**
 * What it costs to analyse each mutation at the Crop Analyzer, from the Hypixel SkyBlock wiki's Crop Analyzer page
 * (hypixelskyblock.minecraft.wiki/w/Crop_Analyzer). Keyed by the data file's mutation ids.
 * Coins are 2000 x copper per analysis except where the wiki lists something else (Cheesebite: 1000 x).
 */
object AnalysisCosts {

    /** [firstCopper] is the copper for the first analysis, [copper] for each one after, [coins] the coin cost of one analysis. */
    class Cost(val firstCopper: Int, val copper: Int, val coins: Long)

    private fun row(first: Int, copper: Int, coins: Long) = Cost(first, copper, coins)

    private val table: Map<String, Cost> = mapOf(
        "ashwreath" to row(250, 5, 10000L),
        "choconut" to row(250, 5, 10000L),
        "dustgrain" to row(250, 5, 10000L),
        "gloomgourd" to row(250, 5, 10000L),
        "lonelily" to row(250, 25, 50000L),
        "scourroot" to row(250, 5, 10000L),
        "shadevine" to row(250, 5, 10000L),
        "veilshroom" to row(250, 5, 10000L),
        "witherbloom" to row(250, 20, 40000L),
        "chocoberry" to row(500, 30, 60000L),
        "cindershade" to row(500, 40, 80000L),
        "coalroot" to row(500, 40, 80000L),
        "creambloom" to row(500, 30, 60000L),
        "duskbloom" to row(500, 40, 80000L),
        "thornshade" to row(500, 40, 80000L),
        "blastberry" to row(750, 120, 240000L),
        "cheesebite" to row(750, 80, 80000L),
        "chloronite" to row(750, 20, 40000L),
        "do_not_eat_shroom" to row(750, 120, 240000L),
        "fleshtrap" to row(750, 180, 360000L),
        "magic_jellybean" to row(750, 80, 160000L),
        "noctilume" to row(750, 150, 300000L),
        "snoozling" to row(750, 300, 600000L),
        "soggybud" to row(750, 30, 60000L),
        "chorus_fruit" to row(1000, 300, 600000L),
        "plantboy_advance" to row(1250, 350, 700000L),
        "puffercloud" to row(1250, 500, 1000000L),
        "shellfruit" to row(1000, 250, 500000L),
        "startlevine" to row(1000, 250, 500000L),
        "stoplight_petal" to row(1250, 2000, 4000000L),
        "thunderling" to row(1000, 400, 800000L),
        "turtlellini" to row(750, 120, 240000L),
        "zombud" to row(1250, 500, 1000000L),
        "all_in_aloe" to row(1500, 2300, 4600000L),
        "devourer" to row(3000, 5000, 10000000L),
        "glasscorn" to row(1500, 2000, 4000000L),
        "godseed" to row(500, 500, 1000000L),
        "jerryflower" to row(250, 10, 20000L),
        "phantomleaf" to row(1500, 1500, 3000000L),
        "timestalk" to row(4000, 9500, 19000000L),
    )

    val size get() = table.size

    /** The cost of [mutationId], or null when the table lacks it. */
    operator fun get(mutationId: String): Cost? = table[mutationId]
}
