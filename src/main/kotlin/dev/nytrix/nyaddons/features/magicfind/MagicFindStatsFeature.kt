package dev.nytrix.nyaddons.features.magicfind

import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.features.Feature

/** Reads Magic Find, Pet Luck and Looting, and learns per-mob bonuses from rare-drop chat lines. */
object MagicFindStatsFeature : Feature {
    override fun init() {
        NyEvents.second += MagicFindStatsImpl::onSecond
        NyEvents.tick += MagicFindStatsImpl::onTick
        NyEvents.chat += MagicFindStatsImpl::onChat
    }
}
