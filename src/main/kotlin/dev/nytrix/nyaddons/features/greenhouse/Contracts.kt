package dev.nytrix.nyaddons.features.greenhouse

/** What a mutation needs next to it: [count] of [crop] (a crop id or a mutation id from the data file). */
class GhRequirement(val crop: String, val count: Int)

/** A plantable crop. [id] is the data file's key, like `nether_wart`. */
class GhCrop(val id: String, val name: String, val size: Int, val soil: String)

/**
 * One of the 40 mutations. [id] is the data file's key (`ashwreath`), [soil] is the data file's `ground` (`soul_sand`).
 * Analysis costs come from the wiki's Crop Analyzer table; zero when unknown.
 */
class GhMutation(
    val id: String,
    val name: String,
    val rarity: String,
    val size: Int,
    val soil: String,
    val requirements: List<GhRequirement>,
    val growthStages: Int,
    val requiresWatering: Boolean,
    val analysisCoins: Long,
    val analysisCopper: Int,
)

/** Everything known about the Greenhouse, loaded on demand and freed when idle. */
interface GhData {
    /** True once crops and mutations are in memory. */
    val ready: Boolean
    val crops: List<GhCrop>
    val mutations: List<GhMutation>
    fun mutation(id: String): GhMutation?

    /** The display name of a crop or mutation id; the id itself if unknown. */
    fun nameOf(id: String): String

    /**
     * Starts loading (and a once-a-day refresh) in the background if that has not happened. Called when the window opens
     * and while the player is on the Garden. Cheap to call repeatedly.
     */
    fun request()
}

/** How many of an item the player has, from sack menus, `[Sacks]` chat lines and the player's own inventory. */
interface GhStock {
    /** Total in sacks and inventory by in-game item name (case-insensitive, no colour codes); null when never seen. */
    fun count(itemName: String): Int?

    /** Just the sacks part, and the inventory part. Null when never seen. */
    fun inSacks(itemName: String): Int?
    fun inInventory(itemName: String): Int

    /** Epoch millis of the last time a sack menu was read, or 0 if never. */
    val sacksUpdatedAt: Long
}

/** A 10x10 layout: [cells][row][column] holds a crop or mutation id, or null for an empty cell. */
class GhLayout(val size: Int, val cells: Array<Array<String?>>, val target: String)

/** Works out where to plant things so a mutation can spawn. Blocks (it calls SkyShards), so use it off the render thread. */
interface GhPlanner {
    /** A layout in which [target] spawns by the Greenhouse rules, or null if none fits. Throws [SkyShardsException] when the solver cannot be reached. */
    fun plan(target: GhMutation): GhLayout?

    /** Like [plan] but only uses the cells set in [unlocked] (row-major, 100 entries); null allows every cell. */
    fun plan(target: GhMutation, unlocked: BooleanArray?): GhLayout? = plan(target)
}

/** The single place the window and the readers meet. The real implementations are assigned here. */
object Greenhouse {
    var data: GhData = GreenhouseDataImpl
    var stock: GhStock = GreenhouseStockImpl
    var planner: GhPlanner = GreenhousePlannerImpl
}
