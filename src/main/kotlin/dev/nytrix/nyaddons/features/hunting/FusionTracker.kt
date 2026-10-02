package dev.nytrix.nyaddons.features.hunting

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.Downloads
import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.features.Feature
import dev.nytrix.nyaddons.gui.Overlay
import dev.nytrix.nyaddons.gui.OverlayManager
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

    fun load(directory: File) {
        val recipesFile = File(directory, "fusion-data.json")
        val ratesFile = File(directory, "fusion-rates.json")
        Thread({
            read(recipesFile, ratesFile)
            val newRecipes = Downloads.json(BASE_URL + "fusion-data.json", recipesFile)
            val newRates = Downloads.json(BASE_URL + "rates.json", ratesFile)
            if (newRecipes || newRates) read(recipesFile, ratesFile)
        }, "NyAddOns fusion data").apply { isDaemon = true }.start()
    }

    private fun read(recipesFile: File, ratesFile: File) {
        if (!recipesFile.exists() || !ratesFile.exists()) return
        try {
            data = FusionData(recipesFile.readText(), ratesFile.readText())
        } catch (e: Exception) {
            NyAddOns.logger.error("Could not read the fusion data", e)
        }
    }
}

/**
 * For every tracked shard that still needs levelling, works out the quickest way to fuse it and
 * lists the shards that have to be hunted for that, with how many are already in the Hunting Box.
 */
object FusionTracker : Feature {

    private class Target(val shard: Shard, val quantity: Int, val plan: FusionPlan)

    private val config get() = NyAddOns.config.hunting.fusionTracker
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "NyAddOns fusion calculator").apply { isDaemon = true } }

    @Volatile
    private var targets: List<Target> = emptyList()

    @Volatile
    private var busy = false
    private var lastRequest: Any? = null

    // Only touched on the worker thread. Solving is the slow part, so it is reused until the settings change.
    private var calculator: Triple<FusionData, FusionParams, FusionCalculator>? = null

    /** True once the tree for the current tracked shards has been worked out. */
    val upToDate get() = lastRequest != null && !busy

    override fun init() {
        FusionRepo.load(NyAddOns.directory)
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
        if (!config.enabled) return
        val data = FusionRepo.data ?: return
        if (!ShardRepo.loaded || busy) return

        val wanted = ShardTracker.trackedShards().filter { it.consumable }.mapNotNull { shard ->
            val owned = ShardTracker.progress(shard).owned ?: 0
            val needed = ShardTracker.neededToMax(shard) ?: (ShardRepo.totalToMax(shard) - owned)
            if (needed > 0) shard to needed else null
        }
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
            } catch (e: Exception) {
                NyAddOns.logger.error("Could not work out the fusion tree", e)
                targets = emptyList()
            } finally {
                busy = false
            }
        }
    }

    private fun lines(): List<String> {
        if (!config.enabled) return emptyList()
        val current = targets
        if (current.isEmpty()) return emptyList()

        val materials = LinkedHashMap<String, Double>()
        for (target in current) {
            if (target.plan.direct) continue
            for ((code, amount) in target.plan.materials) materials.merge(code, amount, Double::plus)
        }
        return buildList {
            add("§6§lFusion Materials")
            for (target in current) {
                val how = if (target.plan.direct) "§7hunt it, no fusion is quicker" else "§7${target.plan.crafts} fusions"
                add(" ${target.shard.coloredName} §7x${target.quantity}§8: $how")
            }
            val shards = materials.mapNotNull { (code, amount) -> ShardRepo.byCode(code)?.let { it to ceil(amount).toInt() } }
            for ((shard, need) in shards.sortedWith(compareBy({ it.first.rarity }, { it.first.name }))) {
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
