package dev.nytrix.nyaddons.features.greenhouse

/**
 * What it costs to analyse each mutation at the Crop Analyzer, from the Hypixel SkyBlock wiki's Crop Analyzer page
 * (hypixelskyblock.minecraft.wiki/w/Crop_Analyzer). Keyed by the data file's mutation ids.
 * Coins are 2000 x copper per analysis except where the wiki lists something else (Cheesebite: 1000 x).
 */
object AnalysisCosts {

    /** [copper] is the copper one analysis costs, [coins] its coin cost. They only decide the order of the checklist. */
    class Cost(val copper: Int, val coins: Long)

    private fun row(copper: Int, coins: Long) = Cost(copper, coins)

    private val table: Map<String, Cost> = mapOf(
        "ashwreath" to row(5, 10000L),
        "choconut" to row(5, 10000L),
        "dustgrain" to row(5, 10000L),
        "gloomgourd" to row(5, 10000L),
        "lonelily" to row(25, 50000L),
        "scourroot" to row(5, 10000L),
        "shadevine" to row(5, 10000L),
        "veilshroom" to row(5, 10000L),
        "witherbloom" to row(20, 40000L),
        "chocoberry" to row(30, 60000L),
        "cindershade" to row(40, 80000L),
        "coalroot" to row(40, 80000L),
        "creambloom" to row(30, 60000L),
        "duskbloom" to row(40, 80000L),
        "thornshade" to row(40, 80000L),
        "blastberry" to row(120, 240000L),
        "cheesebite" to row(80, 80000L),
        "chloronite" to row(20, 40000L),
        "do_not_eat_shroom" to row(120, 240000L),
        "fleshtrap" to row(180, 360000L),
        "magic_jellybean" to row(80, 160000L),
        "noctilume" to row(150, 300000L),
        "snoozling" to row(300, 600000L),
        "soggybud" to row(30, 60000L),
        "chorus_fruit" to row(300, 600000L),
        "plantboy_advance" to row(350, 700000L),
        "puffercloud" to row(500, 1000000L),
        "shellfruit" to row(250, 500000L),
        "startlevine" to row(250, 500000L),
        "stoplight_petal" to row(2000, 4000000L),
        "thunderling" to row(400, 800000L),
        "turtlellini" to row(120, 240000L),
        "zombud" to row(500, 1000000L),
        "all_in_aloe" to row(2300, 4600000L),
        "devourer" to row(5000, 10000000L),
        "glasscorn" to row(2000, 4000000L),
        "godseed" to row(500, 1000000L),
        "jerryflower" to row(10, 20000L),
        "phantomleaf" to row(1500, 3000000L),
        "timestalk" to row(9500, 19000000L),
    )

    /** The cost of [mutationId], or null when the table lacks it. */
    operator fun get(mutationId: String): Cost? = table[mutationId]
}
