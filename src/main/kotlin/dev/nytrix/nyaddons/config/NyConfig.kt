package dev.nytrix.nyaddons.config

import com.google.gson.annotations.Expose
import dev.nytrix.nyaddons.NyAddOns
import io.github.notenoughupdates.moulconfig.Config
import io.github.notenoughupdates.moulconfig.annotations.Category
import dev.nytrix.nyaddons.gui.ConfigTheme
import io.github.notenoughupdates.moulconfig.common.text.StructuredText
import io.github.notenoughupdates.moulconfig.processor.ProcessedCategory

/**
 * Root of the config screen. Each field is one category in the left-hand list.
 * To add an area (Mining, Garden, ...), add a config class and a @Category field here.
 */
class NyConfig : Config() {

    override fun getTitle(): StructuredText = StructuredText.of("NyAddOns ${NyAddOns.VERSION}")

    override fun formatCategoryName(category: ProcessedCategory, isSelected: Boolean): StructuredText =
        if (isSelected) {
            category.displayName.copyShallow().underlined().withColour(ConfigTheme.ACCENT)
        } else {
            super.formatCategoryName(category, isSelected)
        }

    @Expose
    @Category(name = "GUI", desc = "Move and resize every NyAddOns overlay.")
    @JvmField
    var gui = GuiConfig()

    @Expose
    @Category(name = "Foraging", desc = "Features for Moonglade Marsh and Torrhus Canyon.")
    @JvmField
    var foraging = ForagingConfig()

    @Expose
    @Category(name = "Garden", desc = "Features for the Garden and the Greenhouse.")
    @JvmField
    var garden = GardenConfig()

    @Expose
    @Category(name = "Hunting", desc = "Features for shards and attributes.")
    @JvmField
    var hunting = HuntingConfig()
}
