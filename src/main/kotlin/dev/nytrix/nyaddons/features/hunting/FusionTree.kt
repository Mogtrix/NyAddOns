package dev.nytrix.nyaddons.features.hunting

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.config.FusionTreeStyle
import dev.nytrix.nyaddons.config.Position
import dev.nytrix.nyaddons.core.ChatUtils
import dev.nytrix.nyaddons.core.SkyBlockData
import dev.nytrix.nyaddons.features.Feature
import dev.nytrix.nyaddons.gui.Overlay
import dev.nytrix.nyaddons.gui.OverlayContent
import dev.nytrix.nyaddons.gui.OverlayManager
import dev.nytrix.nyaddons.gui.TextContent
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import kotlin.math.ceil

/**
 * Inside the fusion menus, shows how to fuse each tracked shard: every step, how many of each
 * shard it calls for against how many are in the Hunting Box, and which step can be done now.
 * The two shards of that step are outlined in the menu.
 */
object FusionTree : Feature {

    /**
     * One shard in a tree, with everything needed to draw it.
     *
     * A step is done once the player holds as many of its shard as the tree calls for; what it
     * would have been made from is then left out.
     */
    private class Entry(
        val name: String,
        val color: String,
        val shard: Shard?,
        val have: Int?,
        val need: Int,
        val crafts: Long,
        val fuseAmount: Int,
        val root: Boolean,
        ingredients: List<Entry>,
    ) {
        val done = !root && have != null && have >= need
        val ingredients = if (done) emptyList() else ingredients
        val fused get() = crafts > 0

        /** True when at least one fusion of this step can be done with what is in the box. */
        val doable get() = ingredients.isNotEmpty() && ingredients.all { (it.have ?: 0) >= it.fuseAmount }
        val coloredName get() = color + name
        val icon: ItemStack get() = shard?.let { ShardIcons.of(it) } ?: ShardIcons.generic

        val haveText
            get() = when {
                have == null -> "§7?"
                have >= need -> "§a$have"
                else -> "§c$have"
            }
    }

    private const val MENU_WIDTH = 176
    private const val MENU_HEIGHT = 222
    private const val HIGHLIGHT = 0xFF55FF55.toInt()

    private val config get() = NyAddOns.config.hunting.fusionTree

    override fun init() {
        // Never on the HUD: drawInMenu puts it on the fusion screens. The position editor shows the example.
        OverlayManager.register(Overlay("Fusion Tree", ::position, { content(example(), null) }, { null }, onHud = false))
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is AbstractContainerScreen<*>) {
                ScreenEvents.afterExtract(screen).register { _, graphics, _, _, _ -> drawInMenu(screen, graphics) }
            }
        }
    }

    /** Beside the menu until the player moves it. */
    private fun position(): Position {
        val position = config.position
        if (!config.positioned) {
            val window = Minecraft.getInstance().window
            position.x = (window.guiScaledWidth + MENU_WIDTH) / 2 + 6
            position.y = ((window.guiScaledHeight - MENU_HEIGHT) / 2).coerceAtLeast(4)
            config.positioned = true
        }
        return position
    }

    private fun trees(): List<Entry> = FusionTracker.targets.filter { !it.plan.direct }.map { entryOf(it.plan.root, true) }

    private fun entryOf(node: FusionNode, root: Boolean): Entry {
        val shard = ShardRepo.byCode(node.shard)
        return Entry(
            shard?.name ?: node.shard, shard?.rarity?.color ?: "§f", shard,
            if (root) null else shard?.let { ShardTracker.progress(it).owned },
            ceil(node.quantity).toInt(), node.crafts, node.fuseAmount, root,
            node.inputs.map { entryOf(it, false) },
        )
    }

    /** The step to do next: the deepest one that can be done now, in the first tree that has one. */
    private fun nextStep(trees: List<Entry>): Entry? {
        fun deepest(entry: Entry, depth: Int): Pair<Entry, Int>? {
            var best = if (entry.doable) entry to depth else null
            for (ingredient in entry.ingredients) {
                val found = deepest(ingredient, depth + 1) ?: continue
                if (best == null || found.second > best.second) best = found
            }
            return best
        }
        return trees.firstNotNullOfOrNull { deepest(it, 0)?.first }
    }

    /** The two shards to click for the next fusion, if one can be done. */
    fun nextIngredients(): List<Shard> = nextStep(trees())?.ingredients?.mapNotNull { it.shard }.orEmpty()

    private fun drawInMenu(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor) {
        if (!config.enabled || !SkyBlockData.onSkyBlock) return
        val title = ChatUtils.stripColor(screen.title.string)
        if (!ShardTracker.isFusionMenu(title)) return
        val trees = trees()
        if (trees.isEmpty()) return
        val next = nextStep(trees)
        val content = content(trees, next)
        val position = position()
        // Pulled back on screen if it would run off the right or bottom edge, without moving where it is saved.
        val scale = OverlayManager.scaleOf(position)
        val shown = Position(
            position.x.coerceIn(0, (screen.width - (content.width * scale).toInt()).coerceAtLeast(0)),
            position.y.coerceIn(0, (screen.height - (content.height * scale).toInt()).coerceAtLeast(0)),
            position.scale,
        )
        OverlayManager.draw(graphics, shown, content)

        if (!config.highlightSlots || next == null || !ShardTracker.isFusionPicker(title)) return
        val wanted = next.ingredients.mapNotNull { it.shard }
        for (slot in screen.menu.slots) {
            if (slot.container is Inventory || slot.item.isEmpty) continue
            if (ShardTracker.shardOf(slot.item) in wanted) {
                graphics.outline(screen.leftPos + slot.x - 1, screen.topPos + slot.y - 1, 18, 18, HIGHLIGHT)
            }
        }
    }

    private fun content(trees: List<Entry>, next: Entry?): OverlayContent = when (config.style) {
        FusionTreeStyle.TREE -> TextContent(listOf(HEADER) + trees.flatMap { treeLines(it, 0, next) })
        FusionTreeStyle.LIST -> TextContent(listOf(HEADER) + trees.flatMap { listLines(it, next) })
        FusionTreeStyle.DIAGRAM -> Diagram(trees, next)
    }

    private const val HEADER = "§6§lFusion Tree"
    private const val NEXT_MARKER = "§a▶ "

    private fun fusions(count: Long) = if (count == 1L) "1 fusion" else "$count fusions"

    // Indented tree

    private fun treeLines(entry: Entry, depth: Int, next: Entry?): List<String> {
        val marker = if (entry === next) NEXT_MARKER else ""
        val name = if (entry.doable) "§a${entry.name}" else entry.coloredName
        val line = when {
            entry.root -> "$marker$name §7x${entry.need} §8(${fusions(entry.crafts)})"
            entry.done -> "§8✔ ${entry.name}: ${entry.have}/${entry.need}"
            entry.fused -> "$marker$name§7: ${entry.haveText}§7/§f${entry.need} §8(${fusions(entry.crafts)})"
            else -> "${entry.coloredName}§7: ${entry.haveText}§7/§f${entry.need}"
        }
        return listOf("  ".repeat(depth) + line) + entry.ingredients.flatMap { treeLines(it, depth + 1, next) }
    }

    // To-do list, deepest steps first

    private fun listLines(root: Entry, next: Entry?): List<String> {
        val steps = ArrayList<Entry>()
        fun visit(entry: Entry) {
            entry.ingredients.forEach(::visit)
            if (entry.fused) steps += entry
        }
        visit(root)
        return steps.mapIndexed { index, step ->
            val number = index + 1
            if (step.done) return@mapIndexed "§8✔ $number. ${step.name}: ${step.have}/${step.need}"
            val marker = if (step === next) NEXT_MARKER else ""
            val numberColor = if (step.doable) "§a" else "§7"
            val ingredients = step.ingredients.joinToString(" §7+ ") { "${it.coloredName} ${it.haveText}§7/§f${it.need}" }
            "$marker$numberColor$number. $ingredients §7→ ${step.coloredName} §8(${step.crafts}x)"
        }
    }

    // Diagram, left to right

    private class Diagram(trees: List<Entry>, private val next: Entry?) : OverlayContent {

        private class Box(val entry: Entry, val x: Int, val y: Int, val ingredients: List<Box>)

        private val boxes = ArrayList<Box>()
        private var cursor = HEADER_HEIGHT
        private var deepest = 0

        init {
            for (tree in trees) {
                place(tree, 0)
                cursor += TREE_GAP
            }
        }

        override val width = (deepest + 1) * (BOX_WIDTH + GAP_X) - GAP_X
        override val height = cursor - TREE_GAP

        /** Hunted shards stack down the right; each fused shard sits level with the middle of its ingredients. */
        private fun place(entry: Entry, depth: Int): Box {
            deepest = maxOf(deepest, depth)
            val ingredients = entry.ingredients.map { place(it, depth + 1) }
            val y = if (ingredients.isEmpty()) cursor.also { cursor += BOX_HEIGHT + GAP_Y } else (ingredients.first().y + ingredients.last().y) / 2
            return Box(entry, depth * (BOX_WIDTH + GAP_X), y, ingredients).also { boxes += it }
        }

        override fun draw(graphics: GuiGraphicsExtractor) {
            val font = Minecraft.getInstance().font
            graphics.text(font, HEADER, 1, 1, WHITE, true)
            for (box in boxes) {
                val middle = box.y + BOX_HEIGHT / 2
                val turn = box.x + BOX_WIDTH + GAP_X / 2
                for (ingredient in box.ingredients) {
                    val target = ingredient.y + BOX_HEIGHT / 2
                    graphics.fill(box.x + BOX_WIDTH, middle, turn + 1, middle + 1, LINE)
                    graphics.fill(turn, minOf(middle, target), turn + 1, maxOf(middle, target) + 1, LINE)
                    graphics.fill(turn, target, ingredient.x, target + 1, LINE)
                }
            }
            for (box in boxes) {
                val entry = box.entry
                val border = when {
                    entry === next -> BORDER_NEXT
                    entry.doable -> BORDER_DOABLE
                    entry.done -> BORDER_DONE
                    else -> BORDER
                }
                graphics.fill(box.x, box.y, box.x + BOX_WIDTH, box.y + BOX_HEIGHT, border)
                graphics.fill(box.x + 1, box.y + 1, box.x + BOX_WIDTH - 1, box.y + BOX_HEIGHT - 1, BACKGROUND)
                graphics.item(entry.icon, box.x + 3, box.y + 3)

                val name = font.plainSubstrByWidth(entry.name, BOX_WIDTH - TEXT_X - 3, false)
                val detail = when {
                    entry.root -> "§7x${entry.need} §8(${entry.crafts}x)"
                    entry.done -> "§8✔ ${entry.have}/${entry.need}"
                    entry.fused -> "${entry.haveText}§7/§f${entry.need} §8(${entry.crafts}x)"
                    else -> "${entry.haveText}§7/§f${entry.need}"
                }
                graphics.text(font, (if (entry.done) "§8" else entry.color) + name, box.x + TEXT_X, box.y + 3, WHITE, true)
                graphics.text(font, detail, box.x + TEXT_X, box.y + 13, WHITE, true)
            }
        }

        private companion object {
            const val HEADER_HEIGHT = 12
            const val BOX_WIDTH = 100
            const val BOX_HEIGHT = 24
            const val GAP_X = 12
            const val GAP_Y = 3
            const val TREE_GAP = 6
            const val TEXT_X = 22

            const val WHITE = -1
            const val BACKGROUND = 0xE61E1E1E.toInt()
            const val BORDER = 0xFF323232.toInt()
            const val BORDER_DONE = 0xFF555555.toInt()
            const val BORDER_DOABLE = 0xFF2E8B2E.toInt()
            const val BORDER_NEXT = 0xFF55FF55.toInt()
            const val LINE = 0xFF808080.toInt()
        }
    }

    /** A made-up tree for the position editor, which is opened outside the fusion menus. */
    private fun example(): List<Entry> {
        fun hunted(name: String, color: String, have: Int, need: Int) = Entry(name, color, null, have, need, 0, 5, false, emptyList())
        val sunFish = Entry("Sun Fish", "§5", null, 3, 20, 4, 5, false, listOf(hunted("Azure", "§f", 25, 20), hunted("Verdant", "§f", 8, 20)))
        return listOf(Entry("Hideonring", "§9", null, null, 16, 8, 5, true, listOf(hunted("Bitbug", "§9", 34, 40), sunFish)))
    }
}
