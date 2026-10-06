package dev.nytrix.nyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.Accordion
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseOverlay
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class GardenConfig {

    @Expose
    @ConfigOption(name = "Greenhouse Helper", desc = "")
    @Accordion
    @JvmField
    var greenhouse = GreenhouseConfig()
}

/** Which view the Greenhouse window shows. */
enum class GreenhouseView(private val displayName: String) {
    UNIQUE_MUTATIONS("Unique Mutations"),
    ROSE_DRAGON("Rose Dragon"),
    ALL_MUTATIONS("All Mutations"),
    PLANNER("Planner"),
    ;

    override fun toString() = displayName
}

/** How the Planner tab is drawn. */
enum class PlannerStyle(private val displayName: String) {
    CLASSIC("Classic"),
    COMPACT("Compact (SkyHanni-style)"),
    ;

    override fun toString() = displayName
}

/** How the Rose Dragon tab is drawn. */
enum class RoseTreeStyle(private val displayName: String) {
    CLASSIC("Classic"),
    PILLS("Pills (SkyMutations-style)"),
    ;

    override fun toString() = displayName
}

class GreenhouseConfig {

    @Expose
    @ConfigOption(name = "Enabled", desc = "The Greenhouse helper window (§e/gh§7) and reading your sacks on the Garden.")
    @ConfigEditorBoolean
    @JvmField
    var enabled = true

    @Expose
    @ConfigOption(name = "Open Key", desc = "Opens the Greenhouse window. Not bound by default.")
    @ConfigEditorKeybind(defaultKey = 0)
    @JvmField
    var openKey = 0

    @Expose
    @ConfigOption(name = "/gh Command", desc = "Register §e/gh§7 as well as §e/ny greenhouse§7. Applies after restarting the game.")
    @ConfigEditorBoolean
    @JvmField
    var shortCommand = true

    /** The view last used, remembered between sessions. */
    @Expose
    @JvmField
    var view = GreenhouseView.UNIQUE_MUTATIONS

    @Expose
    @ConfigOption(name = "Planner Style", desc = "Classic is the original Planner look. Compact is a flat dark list with small soil tags and tooltips.")
    @ConfigEditorDropdown
    @JvmField
    var plannerStyle = PlannerStyle.CLASSIC

    @Expose
    @ConfigOption(name = "Rose Dragon Tree Style", desc = "Classic is the original list. Pills is a nested tree of rounded pills with a count badge, an ingredient summary and a collapse button.")
    @ConfigEditorDropdown
    @JvmField
    var roseTreeStyle = RoseTreeStyle.CLASSIC

    @Expose
    @ConfigOption(
        name = "Greenhouse Overlay",
        desc = "Draws the plan from the Greenhouse window as see-through blocks over your Greenhouse, on empty squares only. Purely visual. Stand on the plan's top-left square and press §eAlign Overlay§7 first.",
    )
    @ConfigEditorBoolean
    @JvmField
    var overlayEnabled = false

    @Expose
    @ConfigOption(name = "Overlay Key", desc = "Turns the Greenhouse overlay on or off. Also in Minecraft's Controls. Not bound by default.")
    @ConfigEditorKeybind(defaultKey = 0)
    @JvmField
    var overlayKey = 0

    @ConfigOption(name = "Align Overlay", desc = "Puts the plan's top-left square on the block you are standing in. Columns run east, rows run south. Stand there, then press this.")
    @ConfigEditorButton(buttonText = "Align")
    @JvmField
    var overlayAlign = Runnable { GreenhouseOverlay.align() }

    @Expose
    @ConfigOption(name = "Align Key", desc = "Same as the Align button, without opening the settings. Also in Minecraft's Controls. Not bound by default.")
    @ConfigEditorKeybind(defaultKey = 0)
    @JvmField
    var overlayAlignKey = 0

    @Expose
    @ConfigOption(name = "Overlay Distance", desc = "Blocks further away than this get no overlay.")
    @ConfigEditorSlider(minValue = 8f, maxValue = 64f, minStep = 1f)
    @JvmField
    var overlayDistance = 24f
}
