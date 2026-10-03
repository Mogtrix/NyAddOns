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
)

/** Builds the Rose Dragon tree: the five mutations, each opening into what it is made of, down to crops. */
object GreenhouseTree {

    private const val MAX_DEPTH = 6

    /** The visible rows given which row [expanded] paths are open. */
    fun rows(data: GhData, stock: GhStock, expanded: Set<String>): List<TreeRow> {
        val out = ArrayList<TreeRow>(24)
        fun add(path: String, depth: Int, id: String?, icon: String, name: String, need: Int, children: List<GhRequirement>) {
            val open = children.isNotEmpty() && path in expanded
            out.add(TreeRow(path, depth, id, name, need, stock.count(name) ?: 0, children.isNotEmpty(), open, icon))
            if (!open || depth >= MAX_DEPTH) return
            for (r in children) {
                val child = data.mutation(r.crop)
                add("$path/${r.crop}", depth + 1, child?.id, r.crop, data.nameOf(r.crop), r.count * need, child?.requirements ?: emptyList())
            }
        }
        for (name in GreenhouseGoals.roseDragonMutations) {
            val m = findMutation(data, name)
            add(m?.id ?: name, 0, m?.id, m?.id ?: name, name, 1, m?.requirements ?: emptyList())
        }
        return out
    }

    /**
     * A rough 0-100 figure: of all mutations the Rose Dragon plan needs (the five, plus mutations they are made of), how many
     * you hold. Each kind counts up to the amount needed.
     */
    fun percent(data: GhData, stock: GhStock): Int {
        val need = LinkedHashMap<String, Int>()
        fun add(m: GhMutation, n: Int, depth: Int) {
            need.merge(m.id, n, Int::plus)
            if (depth >= MAX_DEPTH) return
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
