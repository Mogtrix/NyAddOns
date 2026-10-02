package dev.nytrix.nyaddons.config

import com.google.gson.annotations.Expose
import dev.nytrix.nyaddons.gui.PositionEditorScreen
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class GuiConfig {

    @ConfigOption(name = "Edit GUI Locations", desc = "Move every NyAddOns overlay at once. Also available with §e/ny gui§7.")
    @ConfigEditorButton(buttonText = "Edit")
    @JvmField
    var editLocations = Runnable { PositionEditorScreen.open() }

    @Expose
    @ConfigOption(name = "Global GUI Scale", desc = "Scale applied to every NyAddOns overlay, on top of each overlay's own scale.")
    @ConfigEditorSlider(minValue = 0.5f, maxValue = 3f, minStep = 0.05f)
    @JvmField
    var globalScale = 1f
}
