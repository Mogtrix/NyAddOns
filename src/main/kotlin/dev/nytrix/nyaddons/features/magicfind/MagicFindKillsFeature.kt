package dev.nytrix.nyaddons.features.magicfind

import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.features.Command
import dev.nytrix.nyaddons.features.Feature
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.minecraft.world.InteractionResult

/** Detects kills of enabled/tracked mobs, prints the Magic Find report and handles `/trackmob`. */
object MagicFindKillsFeature : Feature {
    override fun init() {
        MagicFind.kills = MfKillTracker
        NyEvents.tick += MfKillTracker::onTick
        NyEvents.tick += MfReporter::onTick
        AttackEntityCallback.EVENT.register { _, world, _, entity, _ ->
            if (world.isClientSide) MfKillTracker.onAttack(entity)
            InteractionResult.PASS
        }
    }

    override fun commands(): List<Command> = listOf(MfTrackCommand.command())
}
