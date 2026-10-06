package dev.nytrix.nyaddons.features.greenhouse

/** One visible line of the Rose Dragon tree. [id] is the mutation id for a mutation, else null (a crop). */
class TreeRow(
    val path: String,
    val depth: Int,
    val id: String?,
    val name: String,
    val need: Int,
    val have: Int,
    val expandable: Boolean,
    val expanded: Boolean,
    val icon: String = "",
    /** Rarity name for a mutation, empty for a crop. */
    val rarity: String = "",
    /** What one of it is made of, like "5x Chocoberry, 3x Ashwreath"; empty for a crop. */
    val summary: String = "",
    /** Root pill only: all raw crops the five mutations need, and how many of them you hold (each crop counts up to its need). */
    val cropNeed: Int = 0,
    val cropHave: Int = 0,
    /** Under an intermediate you already hold enough of, so nothing here needs farming. */
    val greyed: Boolean = false,
    /** Crop row only: the one raw crop with the biggest shortfall, to farm next. */
    val farmNext: Boolean = false,
)

/** Builds the Rose Dragon tree: the five mutations, each opening into what it is made of, down to crops. */
object GreenhouseTree {

    private const val MAX_DEPTH = 6

    /** The pill tree's single root row, which the five mutations hang off. */
    const val ROOT_PATH = "rose-dragon-egg"
    const val ROOT_NAME = "Rose Dragon Egg"

    /** The visible rows given which row [expanded] paths are open. */
    fun rows(data: GhData, stock: GhStock, expanded: Set<String>, withRoot: Boolean = false): List<TreeRow> {
        val out = ArrayList<TreeRow>(24)
        val top = if (withRoot) 1 else 0
        val target = farmNext(data, stock)
        var marked = false
        fun add(path: String, depth: Int, id: String?, icon: String, name: String, need: Int, children: List<GhRequirement>, greyed: Boolean) {
            val open = children.isNotEmpty() && path in expanded
            val summary = children.joinToString(", ") { "${it.count}x ${data.nameOf(it.crop)}" }
            val have = stock.count(name) ?: 0
            val mark = !marked && !greyed && id == null && name == target
            if (mark) marked = true
            out.add(TreeRow(path, depth, id, name, need, have, children.isNotEmpty(), open, icon, id?.let { data.mutation(it)?.rarity } ?: "", summary, greyed = greyed, farmNext = mark))
            if (!open || depth >= MAX_DEPTH + top) return
            val childGrey = greyed || (id != null && have >= need)
            for (r in children) {
                val child = data.mutation(r.crop)
                add("$path/${r.crop}", depth + 1, child?.id, r.crop, data.nameOf(r.crop), r.count * need, child?.requirements ?: emptyList(), childGrey)
            }
        }
        val goals = GreenhouseGoals.roseDragonMutations.map { name -> name to findMutation(data, name) }
        val rootOpen = withRoot && ROOT_PATH in expanded
        if (withRoot) {
            val summary = goals.joinToString(", ") { "1x ${it.first}" }
            val (cropHave, cropNeed) = cropTotals(data, stock)
            out.add(TreeRow(ROOT_PATH, 0, null, ROOT_NAME, 1, stock.count(ROOT_NAME) ?: 0, true, rootOpen, ROOT_NAME, "", summary, cropNeed, cropHave))
        }
        if (withRoot && !rootOpen) return out
        for ((name, m) in goals) add(m?.id ?: name, top, m?.id, m?.id ?: name, name, 1, m?.requirements ?: emptyList(), false)
        return out
    }

    /**
     * The raw crop to farm next: the one with the largest (needed - held), needed summed over every place it appears outside
     * a mutation you already hold enough of. Ties go to the first in tree order; null when nothing is short.
     */
    fun farmNext(data: GhData, stock: GhStock): String? {
        val need = LinkedHashMap<String, Int>()
        fun add(crop: String, n: Int, depth: Int) {
            val m = data.mutation(crop)
            if (m == null) { need.merge(data.nameOf(crop), n, Int::plus); return }
            if (depth >= MAX_DEPTH || (stock.count(data.nameOf(crop)) ?: 0) >= n) return
            for (r in m.requirements) add(r.crop, r.count * n, depth + 1)
        }
        for (name in GreenhouseGoals.roseDragonMutations) findMutation(data, name)?.let { add(it.id, 1, 0) }
        var best: String? = null
        var bestShort = 0
        for ((name, n) in need) {
            val short = n - (stock.count(name) ?: 0)
            if (short > bestShort) { best = name; bestShort = short }
        }
        return best
    }

    /** Held and needed raw crops for the whole Rose Dragon plan, mutations expanded down to base crops. Held counts each crop up to its need. */
    fun cropTotals(data: GhData, stock: GhStock): Pair<Int, Int> {
        val need = LinkedHashMap<String, Int>()
        fun add(crop: String, n: Int, depth: Int) {
            val m = data.mutation(crop)
            if (m == null) { need.merge(crop, n, Int::plus); return }
            if (depth >= MAX_DEPTH || (stock.count(data.nameOf(crop)) ?: 0) >= n) return
            for (r in m.requirements) add(r.crop, r.count * n, depth + 1)
        }
        for (name in GreenhouseGoals.roseDragonMutations) findMutation(data, name)?.let { add(it.id, 1, 0) }
        var held = 0
        for ((id, n) in need) held += minOf(n, stock.count(data.nameOf(id)) ?: 0)
        return held to need.values.sum()
    }

    /**
     * A rough 0-100 figure: of all mutations the Rose Dragon plan needs (the five, plus mutations they are made of), how many
     * you hold. Each kind counts up to the amount needed.
     */
    fun percent(data: GhData, stock: GhStock): Int {
        val need = LinkedHashMap<String, Int>()
        fun add(m: GhMutation, n: Int, depth: Int) {
            need.merge(m.id, n, Int::plus)
            // Holding enough of a mutation means what it is made of needs no farming, as in cropTotals and the grey rows.
            if (depth >= MAX_DEPTH || (stock.count(data.nameOf(m.id)) ?: 0) >= n) return
            for (r in m.requirements) data.mutation(r.crop)?.let { add(it, r.count * n, depth + 1) }
        }
        for (name in GreenhouseGoals.roseDragonMutations) findMutation(data, name)?.let { add(it, 1, 0) }
        val total = need.values.sum()
        if (total <= 0) return 0
        var held = 0
        for ((id, n) in need) held += minOf(n, stock.count(data.nameOf(id)) ?: 0)
        return (held * 100 / total).coerceIn(0, 100)
    }
}
