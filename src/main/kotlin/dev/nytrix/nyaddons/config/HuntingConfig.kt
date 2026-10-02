package dev.nytrix.nyaddons.config

import com.google.gson.annotations.Expose
import dev.nytrix.nyaddons.features.hunting.ShardPickerScreen
import io.github.notenoughupdates.moulconfig.annotations.Accordion
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class HuntingConfig {

    @Expose
    @ConfigOption(name = "Shard Tracker", desc = "")
    @Accordion
    @JvmField
    var shardTracker = ShardTrackerConfig()
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
