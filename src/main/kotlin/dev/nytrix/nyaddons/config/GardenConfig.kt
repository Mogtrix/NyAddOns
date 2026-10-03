package dev.nytrix.nyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.Accordion
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind
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

/** Where the pinned Greenhouse plot is allowed to show. */
enum class PinArea(private val displayName: String) {
    GARDEN("Garden only"),
    ALL_ISLANDS("All islands"),
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
    @ConfigOption(name = "Pinned Plot", desc = "Show the plot you pinned with §ePin to screen§7 in the Greenhouse window. Move it with §e/ny gui§7.")
    @ConfigEditorBoolean
    @JvmField
    var pinnedEnabled = true

    @Expose
    @ConfigOption(name = "Pinned Plot Shows On", desc = "Where the pinned plot is shown.")
    @ConfigEditorDropdown
    @JvmField
    var pinnedArea = PinArea.GARDEN

    @Expose
    @JvmField
    var pinnedPosition = Position(250, 24, 1f)
}
