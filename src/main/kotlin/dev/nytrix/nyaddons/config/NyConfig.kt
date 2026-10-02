package dev.nytrix.nyaddons.config

import com.google.gson.annotations.Expose
import dev.nytrix.nyaddons.NyAddOns
import io.github.notenoughupdates.moulconfig.Config
import io.github.notenoughupdates.moulconfig.annotations.Category
import io.github.notenoughupdates.moulconfig.common.text.StructuredText

/**
 * Root of the config screen. Each field is one category in the left-hand list.
 * To add an area (Mining, Garden, ...), add a config class and a @Category field here.
 */
class NyConfig : Config() {

    override fun getTitle(): StructuredText = StructuredText.of("NyAddOns ${NyAddOns.VERSION} by Nytrix")

    @Expose
    @Category(name = "GUI", desc = "Move and resize every NyAddOns overlay.")
    @JvmField
    var gui = GuiConfig()

    @Expose
    @Category(name = "Foraging", desc = "Features for Moonglade Marsh and Torrhus Canyon.")
    @JvmField
    var foraging = ForagingConfig()
}
