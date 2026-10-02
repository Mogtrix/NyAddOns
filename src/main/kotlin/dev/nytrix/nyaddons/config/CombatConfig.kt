package dev.nytrix.nyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.Accordion
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class CombatConfig {

    @Expose
    @ConfigOption(name = "Magic Find", desc = "")
    @Accordion
    @JvmField
    var magicFind = MagicFindConfig()
}

/** How much of the Magic Find breakdown the chat line carries. */
enum class MfBreakdown(private val displayName: String) {
    OFF("Off"),
    HOVER("On hover"),
    INLINE("In the line"),
    ;

    override fun toString() = displayName
}

class MagicFindConfig {

    @Expose
    @ConfigOption(name = "Enabled", desc = "Print the Magic Find that applies when you kill a mob chosen in §e/mf§7, and the odds for mobs from §e/ny trackmob§7.")
    @ConfigEditorBoolean
    @JvmField
    var enabled = true

    @Expose
    @ConfigOption(name = "Report Every (seconds)", desc = "Kills are collected for this long and printed together; kills with the same Magic Find are merged as ×N.")
    @ConfigEditorSlider(minValue = 1f, maxValue = 10f, minStep = 1f)
    @JvmField
    var windowSeconds = 4f

    @Expose
    @ConfigOption(name = "Breakdown", desc = "Show how the Magic Find is made up (general + this mob's bonus).")
    @ConfigEditorDropdown
    @JvmField
    var breakdown = MfBreakdown.HOVER

    @Expose
    @ConfigOption(name = "/ny trackmob Odds", desc = "Print the odds of each Magic Find drop for mobs you track with §e/ny trackmob§7.")
    @ConfigEditorBoolean
    @JvmField
    var trackOdds = true

    /** Menu choices, as [dev.nytrix.nyaddons.features.magicfind.MfMob.id]s. Edited in the §e/mf§7 window. */
    @Expose
    @JvmField
    var enabledMobs = mutableSetOf<String>()

    /** True once the default selection (King Minos, Minos Inquisitor, max-tier slayers) has been applied. */
    @Expose
    @JvmField
    var defaultsApplied = false

    /** Mobs followed with /ny trackmob. */
    @Expose
    @JvmField
    var trackedMobs = mutableSetOf<String>()

    /** For a tracked mob, the one drop (item name) its odds are shown for; no entry means every drop. */
    @Expose
    @JvmField
    var trackedDrops = mutableMapOf<String, String>()
}
