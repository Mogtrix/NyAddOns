package dev.nytrix.nyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.Accordion
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

/** Where an overlay or alert is allowed to show. */
enum class AreaMode(private val displayName: String) {
    ALL_ISLANDS("All islands"),
    FORAGING_ISLANDS("Foraging only"),
    ;

    override fun toString() = displayName
}

enum class BeamMode(private val displayName: String) {
    ALL_TREES("All trees"),
    READY_TREES("Ready only"),
    OFF("Off"),
    ;

    override fun toString() = displayName
}

class ForagingConfig {

    @Expose
    @ConfigOption(name = "Honeycomb Tree Timer", desc = "")
    @Accordion
    @JvmField
    var honeycombTrees = HoneycombTreeConfig()

    @Expose
    @ConfigOption(name = "Honeyhive Timer", desc = "")
    @Accordion
    @JvmField
    var honeyhives = HoneyhiveConfig()
}

class HoneycombTreeConfig {

    @Expose
    @ConfigOption(name = "Enabled", desc = "Track how long until a Critter arrives at each tree you used a Pot of Honeycomb on.")
    @ConfigEditorBoolean
    @JvmField
    var enabled = true

    @Expose
    @ConfigOption(name = "Show Overlay", desc = "Where the timer list is shown.")
    @ConfigEditorDropdown
    @JvmField
    var overlayArea = AreaMode.ALL_ISLANDS

    @Expose
    @ConfigOption(name = "Beacon Beams", desc = "Which trees get a beam. §eYellow §7while waiting, §agreen §7when ready.")
    @ConfigEditorDropdown
    @JvmField
    var beams = BeamMode.ALL_TREES

    @Expose
    @ConfigOption(name = "Floating Time", desc = "Show the time left as floating text at each tree that has a beam.")
    @ConfigEditorBoolean
    @JvmField
    var floatingText = true

    @Expose
    @ConfigOption(name = "Ready Alerts", desc = "Where the ready alert is allowed to fire. Alerts held back are sent when you next arrive.")
    @ConfigEditorDropdown
    @JvmField
    var alertArea = AreaMode.ALL_ISLANDS

    @Expose
    @ConfigOption(name = "Chat Message", desc = "Send a chat message when a tree is ready.")
    @ConfigEditorBoolean
    @JvmField
    var alertChat = true

    @Expose
    @ConfigOption(name = "Sound", desc = "Play a sound when a tree is ready.")
    @ConfigEditorBoolean
    @JvmField
    var alertSound = true

    @Expose
    @ConfigOption(name = "Title", desc = "Show a title on screen when a tree is ready.")
    @ConfigEditorBoolean
    @JvmField
    var alertTitle = false

    @Expose
    @JvmField
    var position = Position(10, 100, 1f)
}

class HoneyhiveConfig {

    @Expose
    @ConfigOption(name = "Enabled", desc = "Show a countdown until the Honeyhives in Torrhus Canyon can be looted again.")
    @ConfigEditorBoolean
    @JvmField
    var enabled = true

    @Expose
    @ConfigOption(name = "Show Overlay", desc = "Where the countdown is shown.")
    @ConfigEditorDropdown
    @JvmField
    var overlayArea = AreaMode.ALL_ISLANDS

    @Expose
    @ConfigOption(name = "Ready Alerts", desc = "Where the ready alert is allowed to fire. Alerts held back are sent when you next arrive.")
    @ConfigEditorDropdown
    @JvmField
    var alertArea = AreaMode.ALL_ISLANDS

    @Expose
    @ConfigOption(name = "Chat Message", desc = "Send a chat message when the Honeyhives are ready. Turn off if SkyHanni's reminder is enough.")
    @ConfigEditorBoolean
    @JvmField
    var alertChat = true

    @Expose
    @ConfigOption(name = "Sound", desc = "Play a sound when the Honeyhives are ready.")
    @ConfigEditorBoolean
    @JvmField
    var alertSound = true

    @Expose
    @ConfigOption(name = "Title", desc = "Show a title on screen when the Honeyhives are ready.")
    @ConfigEditorBoolean
    @JvmField
    var alertTitle = false

    @Expose
    @JvmField
    var position = Position(10, 160, 1f)
}
