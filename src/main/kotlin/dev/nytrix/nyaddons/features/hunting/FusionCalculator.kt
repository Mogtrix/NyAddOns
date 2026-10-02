/*
 * The fusion calculation in this file is a Kotlin port of the calculator in SkyShards
 * (https://github.com/Campionnn/SkyShards), Copyright (c) 2026 Campion, used under the MIT
 * licence. Only the ironman path is ported: shards are costed by the time it takes to hunt
 * them, never by Bazaar price.
 */
package dev.nytrix.nyaddons.features.hunting

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.Reader
import java.io.StringReader
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * SkyShards' fusion data: every shard, and every pair of shards that fuses into it.
 *
 * The recipe file is about 3 MB and holds some 300,000 recipes, so it is read as a stream
 * straight into compact arrays: nothing of the JSON itself stays in memory.
 *
 * @param open opens the recipe file; it is read twice, once for the shards and once for the recipes
 */
class FusionData(open: () -> Reader, ratesJson: String) {

    constructor(json: String, ratesJson: String) : this({ StringReader(json) }, ratesJson)

    /** Shard codes like `R34`, in file order. Everything else is indexed by position in this list. */
    val ids: List<String>
    val indexOf: Map<String, Int>
    val rarity: List<String>
    val fuseAmount: IntArray
    val reptile: BooleanArray
    val defaultRate: DoubleArray

    /** The recipes that make one shard: two ingredients and how many come out. */
    class Recipes(
        private val first: ShortArray,
        private val second: ShortArray,
        private val amounts: ByteArray,
        private val reptileShard: BooleanArray,
    ) {
        val size get() = first.size
        fun input1(recipe: Int) = first[recipe].toInt()
        fun input2(recipe: Int) = second[recipe].toInt()
        fun output(recipe: Int) = amounts[recipe].toInt()
        fun reptile(recipe: Int) = reptileShard[first[recipe].toInt()] || reptileShard[second[recipe].toInt()]
    }

    val recipes: List<Recipes>

    /** For each shard, the shards it is an ingredient of, in the order the recipes list them. */
    val dependents: List<IntArray>

    init {
        val names = ArrayList<String>()
        val rarities = ArrayList<String>()
        val fuseAmounts = ArrayList<Int>()
        val reptiles = ArrayList<Boolean>()
        section(open, "shards") { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                names += reader.nextName()
                var shardRarity = ""
                var amount = 0
                var isReptile = false
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "rarity" -> shardRarity = reader.nextString().lowercase()
                        "fuse_amount" -> amount = reader.nextInt()
                        "family" -> isReptile = "Reptile" in reader.nextString()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                rarities += shardRarity
                fuseAmounts += amount
                reptiles += isReptile
            }
            reader.endObject()
        }
        ids = names
        indexOf = HashMap<String, Int>(names.size * 2).apply { names.forEachIndexed { index, id -> put(id, index) } }
        rarity = rarities
        fuseAmount = fuseAmounts.toIntArray()
        reptile = reptiles.toBooleanArray()

        val rates = JsonParser.parseString(ratesJson).asJsonObject
        defaultRate = DoubleArray(ids.size) { rates[ids[it]]?.asDouble ?: 0.0 }

        val none = Recipes(ShortArray(0), ShortArray(0), ByteArray(0), reptile)
        val byShard = MutableList(ids.size) { none }
        section(open, "recipes") { reader ->
            reader.beginObject()
            while (reader.hasNext()) byShard[indexOf.getValue(reader.nextName())] = readRecipes(reader)
            reader.endObject()
        }
        recipes = byShard

        val lists = List(ids.size) { IntList() }
        for (output in ids.indices) {
            val made = recipes[output]
            for (recipe in 0 until made.size) {
                // Outputs are visited in order, so a repeat can only be the entry just added.
                lists[made.input1(recipe)].addIfNew(output)
                lists[made.input2(recipe)].addIfNew(output)
            }
        }
        dependents = lists.map { it.toArray() }
    }

    /** Runs [read] on the value of one top-level key of the recipe file, skipping the rest. */
    private fun section(open: () -> Reader, name: String, read: (JsonReader) -> Unit) {
        open().use { source ->
            val reader = JsonReader(source)
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == name) read(reader) else reader.skipValue()
            }
            reader.endObject()
        }
    }

    private fun readRecipes(reader: JsonReader): Recipes {
        // Lowest output quantity first, the order the original calculator sees them in.
        val byQuantity = TreeMap<Int, IntList>()
        reader.beginObject()
        while (reader.hasNext()) {
            val pairs = byQuantity.getOrPut(reader.nextName().toInt()) { IntList() }
            reader.beginArray()
            while (reader.hasNext()) {
                reader.beginArray()
                pairs.add(indexOf.getValue(reader.nextString()))
                pairs.add(indexOf.getValue(reader.nextString()))
                reader.endArray()
            }
            reader.endArray()
        }
        reader.endObject()

        val total = byQuantity.values.sumOf { it.size } / 2
        val first = ShortArray(total)
        val second = ShortArray(total)
        val amounts = ByteArray(total)
        var next = 0
        for ((quantity, pairs) in byQuantity) {
            check(quantity in 1..Byte.MAX_VALUE) { "Unexpected fusion output quantity $quantity" }
            for (pair in 0 until pairs.size / 2) {
                first[next] = pairs[pair * 2].toShort()
                second[next] = pairs[pair * 2 + 1].toShort()
                amounts[next] = quantity.toByte()
                next++
            }
        }
        return Recipes(first, second, amounts, reptile)
    }

    /** A growable int array, to avoid boxing hundreds of thousands of numbers while reading. */
    private class IntList {
        private var values = IntArray(16)
        var size = 0
            private set

        operator fun get(index: Int) = values[index]

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun addIfNew(value: Int) {
            if (size == 0 || values[size - 1] != value) add(value)
        }

        fun toArray() = values.copyOf(size)
    }
}

/** The player's settings that change which fusions are worth doing. */
data class FusionParams(
    val hunterFortune: Double = 0.0,
    val newtLevel: Int = 0,
    val salamanderLevel: Int = 0,
    val lizardKingLevel: Int = 0,
    val leviathanLevel: Int = 0,
    val pythonLevel: Int = 0,
    val kingCobraLevel: Int = 0,
    val seaSerpentLevel: Int = 0,
    val tiamatLevel: Int = 0,
    val crocodileLevel: Int = 0,
    /** `none`, or `t1` to `t5`. */
    val kuudraTier: String = "none",
    val excludeChameleon: Boolean = false,
    val noWoodenBait: Boolean = false,
    /** Seconds each fusion is counted as costing. */
    val craftPenalty: Double = 0.0,
)

/**
 * One shard in a fusion tree.
 *
 * @param shard the shard's code, like `R34`
 * @param quantity how many of it this step calls for
 * @param crafts how many fusions make that quantity; zero for a shard that is hunted
 * @param output how many one fusion makes
 * @param fuseAmount how many of it one fusion uses up when it is an ingredient
 * @param inputs the ingredients; empty for a shard that is hunted
 */
class FusionNode(
    val shard: String,
    val quantity: Double,
    val crafts: Long,
    val fuseAmount: Int,
    /** How many one fusion makes; zero for a shard that is hunted or made in a loop. */
    val output: Double,
    val inputs: List<FusionNode>,
)

/**
 * What it takes to make some quantity of one shard.
 *
 * @param crafts how many fusions in total
 * @param materials how many of each shard has to be hunted, by shard code
 * @param direct true when hunting the shard itself is quicker than any fusion
 */
class FusionPlan(val crafts: Long, val materials: Map<String, Double>, val direct: Boolean, val root: FusionNode)

class FusionCalculator(private val data: FusionData, private val params: FusionParams) {

    private class Solution(val cost: DoubleArray, val choice: IntArray)

    private class Step(val output: Int, val recipe: Int)

    private sealed class Tree(val shard: Int) {
        var quantity = 0.0

        class Direct(shard: Int) : Tree(shard)

        class Fused(shard: Int, val recipe: Int, val input1: Tree, val input2: Tree) : Tree(shard) {
            var crafts = 0.0
        }

        class Cycle(shard: Int, val steps: List<Step>, val inputRecipe: Tree, val cycleInputs: List<Tree>) : Tree(shard) {
            var crafts = 0.0
        }
    }

    private val count = data.ids.size
    private val crocodileMultiplier = 1 + (2.0 * params.crocodileLevel) / 100
    private val craftPenalty = params.craftPenalty / 3600
    private val rates = DoubleArray(count) { rateOf(it) }
    private val solution = solve(crocodileMultiplier)
    private val cycles = if (params.crocodileLevel > 0) findCycles(solution.choice) else emptyList()

    // The same problem without the Crocodile bonus, which the original uses to enter and leave loops.
    private val plainSolution by lazy { solve(1.0) }

    fun plan(shardId: String, quantity: Double): FusionPlan? {
        val shard = data.indexOf[shardId] ?: return null
        val tree = build(shard, solution.choice, cycles, 0)
        assign(tree, quantity, crocodileMultiplier)
        val materials = LinkedHashMap<Int, Double>()
        val crafts = collect(tree, materials)
        return FusionPlan(crafts.roundToLong(), materials.mapKeys { data.ids[it.key] }, tree is Tree.Direct, nodeOf(tree))
    }

    private fun nodeOf(tree: Tree): FusionNode {
        val (crafts, inputs) = when (tree) {
            is Tree.Direct -> 0.0 to emptyList()
            is Tree.Fused -> tree.crafts to listOf(tree.input1, tree.input2)
            is Tree.Cycle -> tree.crafts to tree.cycleInputs + tree.inputRecipe
        }
        val output = (tree as? Tree.Fused)?.let { effectiveOutput(data.recipes[it.shard], it.recipe, crocodileMultiplier) } ?: 0.0
        return FusionNode(data.ids[tree.shard], tree.quantity, crafts.roundToLong(), data.fuseAmount[tree.shard], output, inputs.map(::nodeOf))
    }

    // Hunting rates

    private fun rateOf(shard: Int): Double {
        val id = data.ids[shard]
        var rate = data.defaultRate[shard]
        if (id == KUUDRA_SHARD && rate == 0.0) rate = kuudraRate()
        if (rate > 0) {
            if (params.noWoodenBait && id in WOODEN_BAIT_SHARDS) rate *= if (id == SHINY_FISH) 0.1 else 0.05
            if (id !in NO_FORTUNE_SHARDS) rate = withFortune(rate, shard)
        }
        if (params.excludeChameleon && id == CHAMELEON) rate = 0.0
        return rate
    }

    private fun kuudraRate(): Double {
        val (baseTime, multiplier) = when (params.kuudraTier) {
            "t1", "t2", "t3" -> 60 to 1.0
            "t4" -> 60 to 1.25
            "t5" -> 100 to 1.5
            else -> return 0.0
        }
        // Key cost is ignored: an ironman's coins per hour are not part of this.
        return multiplier * (3600.0 / (baseTime + KUUDRA_DOWNTIME))
    }

    private fun withFortune(baseRate: Double, shard: Int): Double {
        var rate = baseRate
        var fortune = params.hunterFortune + when (data.rarity[shard]) {
            "common" -> 2 * params.newtLevel
            "uncommon" -> 2 * params.salamanderLevel
            "rare" -> params.lizardKingLevel
            "epic" -> params.leviathanLevel
            else -> 0
        }
        val blackHole = BLACK_HOLE_SHARDS[data.ids[shard]]
        if (blackHole != null) {
            val tiamat = 1 + (5.0 * params.tiamatLevel) / 100
            val seaSerpent = 1 + ((2.0 * params.seaSerpentLevel) / 100) * tiamat
            if (blackHole) rate *= 1 + ((5.0 * params.pythonLevel) / 100) * seaSerpent
            fortune *= 1 + (params.kingCobraLevel / 100.0) * seaSerpent
        }
        return rate * (1 + fortune / 100)
    }

    // Cheapest way to get each shard

    private fun effectiveOutput(recipes: FusionData.Recipes, recipe: Int, crocodile: Double) =
        if (recipes.reptile(recipe)) recipes.output(recipe) * crocodile else recipes.output(recipe).toDouble()

    /** Relaxes costs until nothing gets cheaper: each shard is either hunted or made by its cheapest fusion. */
    private fun solve(crocodile: Double): Solution {
        val cost = DoubleArray(count) { if (rates[it] <= 0) Double.POSITIVE_INFINITY else 1 / rates[it] }
        val choice = IntArray(count) { DIRECT }

        val queue = ArrayDeque((0 until count).toList())
        val queued = BooleanArray(count) { true }
        while (queue.isNotEmpty()) {
            val output = queue.removeFirst()
            queued[output] = false
            val recipes = data.recipes[output]
            val current = cost[output]
            var best = current
            var bestRecipe = choice[output]
            for (recipe in 0 until recipes.size) {
                val input1 = recipes.input1(recipe)
                val input2 = recipes.input2(recipe)
                val total = cost[input1] * data.fuseAmount[input1] + cost[input2] * data.fuseAmount[input2] + craftPenalty
                val perShard = total / effectiveOutput(recipes, recipe, crocodile)
                if (perShard < best - TOLERANCE) {
                    best = perShard
                    bestRecipe = recipe
                }
            }
            if (best < current - TOLERANCE || bestRecipe != choice[output]) {
                cost[output] = best
                choice[output] = bestRecipe
                for (dependent in data.dependents[output]) {
                    if (!queued[dependent]) {
                        queue.addLast(dependent)
                        queued[dependent] = true
                    }
                }
            }
        }
        return Solution(cost, choice)
    }

    /** Groups of shards whose chosen fusions feed back into each other (Tarjan's algorithm). */
    private fun findCycles(choice: IntArray): List<List<Int>> {
        val indices = IntArray(count) { -1 }
        val lowLinks = IntArray(count)
        val onStack = BooleanArray(count)
        val stack = ArrayList<Int>()
        val cycles = ArrayList<List<Int>>()
        var next = 0

        fun inputsOf(node: Int) = data.recipes[node].let { intArrayOf(it.input1(choice[node]), it.input2(choice[node])) }

        fun connect(node: Int) {
            indices[node] = next
            lowLinks[node] = next
            next++
            stack += node
            onStack[node] = true
            for (neighbor in inputsOf(node)) {
                if (choice[neighbor] == DIRECT) continue
                if (indices[neighbor] == -1) {
                    connect(neighbor)
                    lowLinks[node] = minOf(lowLinks[node], lowLinks[neighbor])
                } else if (onStack[neighbor]) {
                    lowLinks[node] = minOf(lowLinks[node], indices[neighbor])
                }
            }
            if (lowLinks[node] == indices[node]) {
                val group = ArrayList<Int>()
                do {
                    val member = stack.removeAt(stack.lastIndex)
                    onStack[member] = false
                    group += member
                } while (member != node)
                if (group.size > 1 || node in inputsOf(node)) cycles += group
            }
        }

        for (node in 0 until count) {
            if (choice[node] != DIRECT && indices[node] == -1) connect(node)
        }
        return cycles
    }

    // Building the tree for one shard

    private fun build(shard: Int, choice: IntArray, cycles: List<List<Int>>, depth: Int): Tree {
        check(depth < MAX_DEPTH) { "Fusion tree for ${data.ids[shard]} does not end" }
        val cycle = cycles.firstOrNull { shard in it }
        if (cycle != null) {
            val plain = plainSolution
            val steps = cycle.filter { choice[it] != DIRECT }.map { Step(it, choice[it]) }
            fun plainCost(node: Int) = plain.cost[node].let { if (it == 0.0) Double.POSITIVE_INFINITY else it }
            var entry = shard
            for (step in steps) {
                if (plainCost(step.output) < plainCost(entry)) entry = step.output
            }
            val inputRecipe = build(entry, plain.choice, emptyList(), depth + 1)
            assign(inputRecipe, data.fuseAmount[entry].toDouble(), 1.0)

            val produced = steps.map { it.output }.toSet()
            val external = LinkedHashSet<Int>()
            for (step in steps) {
                val recipes = data.recipes[step.output]
                for (input in intArrayOf(recipes.input1(step.recipe), recipes.input2(step.recipe))) {
                    if (input !in produced) external += input
                }
            }
            return Tree.Cycle(shard, steps, inputRecipe, external.map { build(it, plain.choice, emptyList(), depth + 1) })
        }

        val recipe = choice[shard]
        if (recipe == DIRECT) return Tree.Direct(shard)
        val recipes = data.recipes[shard]
        return Tree.Fused(
            shard, recipe,
            build(recipes.input1(recipe), choice, cycles, depth + 1),
            build(recipes.input2(recipe), choice, cycles, depth + 1),
        )
    }

    private fun assign(tree: Tree, required: Double, crocodile: Double) {
        tree.quantity = required
        when (tree) {
            is Tree.Direct -> {}

            is Tree.Fused -> {
                val recipes = data.recipes[tree.shard]
                val crafts = craftsFor(required, effectiveOutput(recipes, tree.recipe, crocodile))
                tree.crafts = crafts
                assign(tree.input1, crafts * data.fuseAmount[recipes.input1(tree.recipe)], crocodile)
                assign(tree.input2, crafts * data.fuseAmount[recipes.input2(tree.recipe)], crocodile)
            }

            is Tree.Cycle -> {
                val outputStep = tree.steps.firstOrNull { it.output == tree.shard } ?: return
                val expectedOutput = effectiveOutput(data.recipes[tree.shard], outputStep.recipe, crocodile)

                // How much of the shard the loop eats on its way round, and what it needs from outside.
                var consumed = 0
                val produced = tree.steps.map { it.output }.toSet()
                val fromOutside = HashMap<Int, Int>()
                for (step in tree.steps) {
                    val recipes = data.recipes[step.output]
                    for (input in intArrayOf(recipes.input1(step.recipe), recipes.input2(step.recipe))) {
                        if (input == tree.shard) consumed += data.fuseAmount[input]
                        if (input !in produced) fromOutside.merge(input, data.fuseAmount[input], Int::plus)
                    }
                }
                val net = snap(expectedOutput - consumed)
                val expectedCrafts = if (net > 0) craftsFor(required, net) else craftsFor(required, expectedOutput)
                val laps = ceil(expectedCrafts / tree.steps.size)
                tree.crafts = laps * tree.steps.size
                for (input in tree.cycleInputs) assign(input, (fromOutside[input.shard] ?: 0) * laps, crocodile)
            }
        }
    }

    private fun collect(tree: Tree, materials: MutableMap<Int, Double>): Double = when (tree) {
        is Tree.Direct -> {
            materials.merge(tree.shard, tree.quantity, Double::plus)
            0.0
        }

        is Tree.Fused -> tree.crafts + collect(tree.input1, materials) + collect(tree.input2, materials)
        is Tree.Cycle -> tree.crafts + collect(tree.inputRecipe, materials) + tree.cycleInputs.sumOf { collect(it, materials) }
    }

    private companion object {
        const val DIRECT = -1
        const val TOLERANCE = 1e-10
        const val INTEGER_EPSILON = 1e-12
        const val MAX_DEPTH = 400
        const val KUUDRA_DOWNTIME = 25

        const val KUUDRA_SHARD = "L15"
        const val CHAMELEON = "L4"
        const val SHINY_FISH = "L23"
        val WOODEN_BAIT_SHARDS = setOf("R29", "L23", "R59", "R23", "R49")
        val NO_FORTUNE_SHARDS =
            setOf("C19", "U4", "U16", "U28", "R24", "R25", "R27", "R60", "R64", "L4", "L15", "L30", "L33", "L48", "L51")

        // Shards from Black Hole. True for the ones Python's bonus applies to as well as King Cobra's.
        val BLACK_HOLE_SHARDS = mapOf(
            "L47" to false, "L27" to false, "L26" to false, "L17" to false, "E33" to true, "E29" to false, "E20" to false,
            "E18" to true, "E17" to false, "E14" to false, "R56" to false, "R49" to false, "R42" to false, "R39" to true,
            "R38" to false, "R36" to true, "R31" to true, "R21" to false, "R18" to false, "R6" to true, "U38" to true,
            "U36" to true, "U33" to true, "U32" to true, "U30" to false, "U29" to false, "U27" to false, "U18" to true,
            "U15" to true, "U12" to true, "C36" to true, "C33" to true, "C30" to true, "C27" to false, "C21" to true,
            "C20" to false, "C15" to true, "C14" to false, "C12" to true, "C9" to true, "C8" to false,
        )

        /** Rounds away floating-point noise, so 249.99999999999997 crafts does not become 250.x. */
        fun snap(value: Double): Double {
            if (!value.isFinite()) return value
            val rounded = Math.rint(value).let { if (abs(value - it) == 0.5) Math.floor(value + 0.5) else it }
            return if (abs(value - rounded) <= INTEGER_EPSILON * max(1.0, abs(value))) rounded else value
        }

        fun craftsFor(quantity: Double, outputQuantity: Double) = ceil(snap(quantity / outputQuantity))
    }
}
