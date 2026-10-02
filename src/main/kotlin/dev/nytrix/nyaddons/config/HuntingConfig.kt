package dev.nytrix.nyaddons.config

import com.google.gson.annotations.Expose
import dev.nytrix.nyaddons.features.hunting.ShardPickerScreen
import io.github.notenoughupdates.moulconfig.annotations.Accordion
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class HuntingConfig {

    @Expose
    @ConfigOption(name = "Shard Tracker", desc = "")
    @Accordion
    @JvmField
    var shardTracker = ShardTrackerConfig()

    @Expose
    @ConfigOption(name = "Fusion Tracker", desc = "")
    @Accordion
    @JvmField
    var fusionTracker = FusionTrackerConfig()

    @Expose
    @ConfigOption(name = "Fusion Tree", desc = "")
    @Accordion
    @JvmField
    var fusionTree = FusionTreeConfig()
}

enum class FusionTreeStyle(private val displayName: String) {
    TREE("Tree"),
    LIST("To-do list"),
    DIAGRAM("Diagram"),
    ;

    override fun toString() = displayName
}

class FusionTreeConfig {

    @Expose
    @ConfigOption(name = "Enabled", desc = "Show the fusion tree of your tracked shards beside the Fusion Box, Shard Fusion and Confirm Fusion menus.")
    @ConfigEditorBoolean
    @JvmField
    var enabled = true

    @Expose
    @ConfigOption(name = "Style", desc = "An indented tree, a to-do list in the order you fuse, or a diagram with icons.")
    @ConfigEditorDropdown
    @JvmField
    var style = FusionTreeStyle.TREE

    @Expose
    @ConfigOption(name = "Highlight Slots", desc = "Outline the two shards of the next fusion you can do in the fusion menus.")
    @ConfigEditorBoolean
    @JvmField
    var highlightSlots = true

    @Expose
    @JvmField
    var position = Position(0, 0, 1f)

    /** False until the tree has been given its default place beside the menu. */
    @Expose
    @JvmField
    var positioned = false
}

enum class KuudraTier(private val displayName: String, val id: String) {
    NONE("None", "none"),
    BASIC("Basic", "t1"),
    HOT("Hot", "t2"),
    BURNING("Burning", "t3"),
    FIERY("Fiery", "t4"),
    INFERNAL("Infernal", "t5"),
    ;

    override fun toString() = displayName
}

class FusionTrackerConfig {

    @Expose
    @ConfigOption(
        name = "Enabled",
        desc = "List the shards to hunt to fuse the shards you are tracking, worked out the way SkyShards does for ironman. " +
            "Your Newt, Salamander, Lizard King, Leviathan, Python, King Cobra, Sea Serpent, Tiamat and Crocodile levels are read from your Hunting Box.",
    )
    @ConfigEditorBoolean
    @JvmField
    var enabled = true

    @Expose
    @ConfigOption(name = "Hunter Fortune", desc = "Your Hunter Fortune. More fortune makes hunting quicker compared with fusing.")
    @ConfigEditorSlider(minValue = 0f, maxValue = 300f, minStep = 1f)
    @JvmField
    var hunterFortune = 0f

    @Expose
    @ConfigOption(name = "Kuudra Tier", desc = "The Kuudra tier you run for Kraken shards. None leaves Kuudra out.")
    @ConfigEditorDropdown
    @JvmField
    var kuudraTier = KuudraTier.NONE

    @Expose
    @ConfigOption(name = "Exclude Chameleon", desc = "Never use Chameleon shards in a fusion.")
    @ConfigEditorBoolean
    @JvmField
    var excludeChameleon = false

    @Expose
    @ConfigOption(name = "Exclude Wooden Bait", desc = "Work out fishing shards as if you fish without Wooden Bait.")
    @ConfigEditorBoolean
    @JvmField
    var noWoodenBait = false

    @Expose
    @ConfigOption(name = "Craft Penalty", desc = "Seconds each fusion is counted as costing. Higher values favour trees with fewer fusions.")
    @ConfigEditorSlider(minValue = 0f, maxValue = 10f, minStep = 0.1f)
    @JvmField
    var craftPenalty = 0.8f

    @Expose
    @JvmField
    var position = Position(220, 40, 1f)
}

class ShardTrackerConfig {

    @Expose
    @ConfigOption(name = "Enabled", desc = "Show how many of each tracked shard are in your Hunting Box and how many more you need to max its attribute.")
    @ConfigEditorBoolean
    @JvmField
    var enabled = true

    @ConfigOption(name = "Choose Shards", desc = "Pick the shards to track. Also §e/hunt§7, or §e/hunt <shard>§7 to track one by name.")
    @ConfigEditorButton(buttonText = "Open")
    @JvmField
    var openPicker = Runnable { ShardPickerScreen.open() }

    @Expose
    @ConfigOption(name = "Track Key", desc = "Press while hovering a shard in the Hunting Box or Attribute Menu to track or untrack it.")
    @ConfigEditorKeybind(defaultKey = KEY_H)
    @JvmField
    var trackKey = KEY_H

    @Expose
    @ConfigOption(name = "/hunt Command", desc = "Register §e/hunt§7 as well as §e/ny hunt§7. Turn off if it ever clashes with a Hypixel command. Applies after restarting the game.")
    @ConfigEditorBoolean
    @JvmField
    var shortCommand = true

    @Expose
    @ConfigOption(name = "Chat Message", desc = "Send a chat message when you own enough of a tracked shard to max its attribute.")
    @ConfigEditorBoolean
    @JvmField
    var alertChat = true

    @Expose
    @ConfigOption(name = "Sound", desc = "Play a sound when you own enough of a tracked shard to max its attribute.")
    @ConfigEditorBoolean
    @JvmField
    var alertSound = true

    @Expose
    @JvmField
    var position = Position(10, 40, 1f)

    private companion object {
        // GLFW key code
        const val KEY_H = 72
    }
}
