package dev.nytrix.nyaddons.features.hunting

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.Downloads
import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.core.Safe
import dev.nytrix.nyaddons.features.Feature
import dev.nytrix.nyaddons.gui.Overlay
import dev.nytrix.nyaddons.gui.OverlayManager
import net.minecraft.client.Minecraft
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.roundToInt

/** SkyShards' recipe list and hunting rates, downloaded from its GitHub repository and kept on disk. */
object FusionRepo {

    private const val BASE_URL = "https://raw.githubusercontent.com/Campionnn/SkyShards/master/public/"

    @Volatile
    var data: FusionData? = null
        private set

    private var loading = false

    /** Starts loading the data if that has not happened yet. It is only needed once a shard is tracked. */
    @Synchronized
    fun request() {
        if (loading || data != null) return
        loading = true
        val recipesFile = File(NyAddOns.directory, "fusion-data.json")
        val ratesFile = File(NyAddOns.directory, "fusion-rates.json")
        Safe.background("fusion data") {
            try {
                read(recipesFile, ratesFile)
                val newRecipes = Downloads.refresh(BASE_URL + "fusion-data.json", recipesFile)
                val newRates = Downloads.refresh(BASE_URL + "rates.json", ratesFile)
                if (newRecipes || newRates || data == null) read(recipesFile, ratesFile)
            } finally {
                synchronized(this) { loading = false }
            }
        }
    }

    /** Drops the data to free its memory. [request] loads it again. */
    @Synchronized
    fun release() {
        if (!loading) data = null
    }

    private fun read(recipesFile: File, ratesFile: File) {
        if (!recipesFile.exists() || !ratesFile.exists()) return
        try {
            data = FusionData({ recipesFile.bufferedReader() }, ratesFile.readText())
        } catch (e: Throwable) {
            NyAddOns.logger.error("Could not read the fusion data", e)
        }
    }
}

/**
 * For every tracked shard that still needs levelling, works out the quickest way to fuse it and
 * lists the shards that have to be hunted for that, with how many are already in the Hunting Box.
 */
object FusionTracker : Feature {

    class Target(val shard: Shard, val quantity: Int, val plan: FusionPlan)

    private val config get() = NyAddOns.config.hunting.fusionTracker
    private val treeConfig get() = NyAddOns.config.hunting.fusionTree
    private val worker by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "NyAddOns fusion calculator").apply { isDaemon = true } }
    }

    /** The tracked shards that still need levelling, each with its worked-out fusion tree. */
    @Volatile
    var targets: List<Target> = emptyList()
        private set

    @Volatile
    private var busy = false

    // Set when a refresh was asked for while another was still being worked out.
    @Volatile
    private var pending = false
    private var lastRequest: Any? = null
    private var idleSince = 0L

    // Solving is the slow part, so the solved calculator is reused until the settings change.
    @Volatile
    private var calculator: Triple<FusionData, FusionParams, FusionCalculator>? = null

    /**
     * One step of a tree with what is still left to do, given what the player already holds.
     *
     * @param required how many of this shard the tree calls for
     * @param have how many are in the Hunting Box; null for the shard being levelled and when not known yet
     * @param left fusions still to do for this step
     * @param done true when what is held already covers the step, so nothing below it is needed
     */
    class Remaining(
        val node: FusionNode,
        val required: Double,
        val have: Int?,
        val left: Long,
        val done: Boolean,
        val inputs: List<Remaining>,
    ) {
        val need get() = ceil(required - EPSILON).toInt()

        /** False for a shard that is hunted. */
        val fusion get() = node.inputs.isNotEmpty()
    }

    /**
     * Works out what is still left of a tree. A step in the middle that you already hold enough of
     * is finished and everything below it drops out; one you hold part of needs fewer fusions, and
     * so fewer of the shards under it.
     */
    fun remaining(node: FusionNode, required: Double = node.quantity, root: Boolean = true): Remaining {
        val have = if (root) null else ShardRepo.byCode(node.shard)?.let { ShardTracker.progress(it).owned }
        if (node.inputs.isEmpty()) return Remaining(node, required, have, 0, false, emptyList())
        // The shard being levelled already has what you own taken off, so only the steps below it count what is held.
        val outstanding = required - (have ?: 0)
        if (!root && outstanding <= EPSILON) return Remaining(node, required, have, 0, true, emptyList())
        val crafts = if (node.output > 0) ceil(outstanding / node.output - EPSILON).toLong() else node.crafts
        val share = if (required > 0) (outstanding / required).coerceAtMost(1.0) else 1.0
        val inputs = node.inputs.map { child ->
            // A fusion uses up fuseAmount of each ingredient; fusions in a loop keep their original sizes, scaled down.
            val needed = if (node.output > 0) (crafts * child.fuseAmount).toDouble() else child.quantity * share
            remaining(child, needed, false)
        }
        return Remaining(node, required, have, crafts, false, inputs)
    }

    /** Fusions still to do for a whole tree. */
    fun totalFusionsLeft(step: Remaining): Long = step.left + step.inputs.sumOf(::totalFusionsLeft)

    fun totalFusionsLeft(node: FusionNode): Long = totalFusionsLeft(remaining(node))

    /** The shards that still have to be hunted for a tree, by shard code. */
    fun materialsOf(step: Remaining, into: MutableMap<String, Double> = LinkedHashMap()): Map<String, Double> {
        when {
            step.done -> {}
            !step.fusion -> into.merge(step.node.shard, step.required, Double::plus)
            else -> step.inputs.forEach { materialsOf(it, into) }
        }
        return into
    }

    private const val EPSILON = 1e-9
    private const val IDLE_RELEASE_MILLIS = 60_000L

    /** Works the trees out again now, so the counters follow a fusion as it happens. */
    fun requestRefresh() {
        OverlayManager.invalidate()
        refresh()
    }

    /** True once the tree for the current tracked shards has been worked out. */
    val upToDate get() = lastRequest != null && !busy

    override fun init() {
        NyEvents.second += ::refresh
        OverlayManager.register(
            Overlay(
                "Fusion Materials", { config.position },
                listOf("§6§lFusion Materials", " §9Hideonring §7x16§8: §712 fusions", "  §9Bitbug§7: §c34§7/§f60", "  §5Sun Fish§7: §a25§7/§f20"),
                ::lines,
            ),
        )
    }

    private fun currentParams(): FusionParams {
        fun level(code: String): Int {
            val shard = ShardRepo.byCode(code) ?: return 0
            return ShardRepo.levelOf(shard, ShardTracker.progress(shard).syphoned ?: 0)
        }
        return FusionParams(
            hunterFortune = config.hunterFortune.roundToInt().toDouble(),
            newtLevel = level("C35"),
            salamanderLevel = level("U8"),
            lizardKingLevel = level("R8"),
            leviathanLevel = level("E5"),
            pythonLevel = level("R9"),
            kingCobraLevel = level("R54"),
            seaSerpentLevel = level("E32"),
            tiamatLevel = level("L6"),
            crocodileLevel = level("R45"),
            kuudraTier = config.kuudraTier.id,
            excludeChameleon = config.excludeChameleon,
            noWoodenBait = config.noWoodenBait,
            craftPenalty = (config.craftPenalty * 10).roundToInt() / 10.0,
        )
    }

    private fun refresh() {
        // The materials overlay and the tree in the fusion menus both run on these results.
        if (!config.enabled && !treeConfig.enabled) {
            if (lastRequest != null && !busy) {
                targets = emptyList()
                lastRequest = null
                calculator = null
                FusionRepo.release()
            }
            return
        }
        if (!ShardRepo.loaded) return
        if (busy) {
            pending = true
            return
        }

        val wanted = ShardTracker.trackedShards().filter { it.consumable }.mapNotNull { shard ->
            val owned = ShardTracker.progress(shard).owned ?: 0
            val needed = ShardTracker.neededToMax(shard) ?: (ShardRepo.totalToMax(shard) - owned)
            if (needed > 0) shard to needed else null
        }
        // The recipe data is only loaded once something needs a tree, and let go of again a minute after nothing does.
        if (wanted.isEmpty()) {
            if (FusionRepo.data == null) {
                targets = emptyList()
                return
            }
            val now = System.currentTimeMillis()
            if (idleSince == 0L) idleSince = now
            if (now - idleSince >= IDLE_RELEASE_MILLIS) {
                idleSince = 0
                targets = emptyList()
                calculator = null
                FusionRepo.release()
                return
            }
        } else {
            idleSince = 0
        }
        FusionRepo.request()
        val data = FusionRepo.data ?: return
        val params = currentParams()
        val request = Triple(data, params, wanted.map { it.first.id to it.second })
        if (request == lastRequest) return
        lastRequest = request
        if (wanted.isEmpty()) {
            targets = emptyList()
            return
        }

        busy = true
        worker.execute {
            try {
                val solved = calculator?.takeIf { it.first === data && it.second == params }
                    ?: Triple(data, params, FusionCalculator(data, params)).also { calculator = it }
                targets = wanted.mapNotNull { (shard, needed) ->
                    solved.third.plan(shard.code, needed.toDouble())?.let { Target(shard, needed, it) }
                }
            } catch (e: Throwable) {
                NyAddOns.logger.error("Could not work out the fusion tree", e)
                targets = emptyList()
            } finally {
                busy = false
                // Back on the game thread: redraw with the new trees, and catch up if something changed meanwhile.
                Minecraft.getInstance().execute {
                    OverlayManager.invalidate()
                    if (pending) {
                        pending = false
                        refresh()
                    }
                }
            }
        }
    }

    /**
     * The overlay's lines. Each tracked shard has its own materials listed under it, so two shards
     * that need the same hunted shard each show it, with what that shard's tree calls for.
     */
    fun lines(): List<String> {
        if (!config.enabled) return emptyList()
        val current = targets
        if (current.isEmpty()) return emptyList()
        return buildList {
            add("§6§lFusion Materials")
            for (target in current) {
                val steps = remaining(target.plan.root)
                val left = if (target.plan.direct) 0 else totalFusionsLeft(steps)
                val how = when {
                    target.plan.direct -> "§7hunt it, no fusion is quicker"
                    left == 1L -> "§71 fusion left"
                    else -> "§7$left fusions left"
                }
                add(" ${target.shard.coloredName} §7x${target.quantity}§8: $how")
                if (target.plan.direct) continue
                val materials = materialsOf(steps).mapNotNull { (code, amount) ->
                    ShardRepo.byCode(code)?.let { it to ceil(amount - EPSILON).toInt() }
                }
                for ((shard, need) in materials.sortedWith(compareBy({ it.first.rarity }, { it.first.name }))) {
                    val owned = ShardTracker.progress(shard).owned
                    val have = when {
                        owned == null -> "§7?"
                        owned >= need -> "§a$owned"
                        else -> "§c$owned"
                    }
                    add("  ${shard.coloredName}§7: $have§7/§f$need")
                }
            }
        }
    }
}
