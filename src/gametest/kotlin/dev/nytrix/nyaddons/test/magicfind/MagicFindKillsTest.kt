package dev.nytrix.nyaddons.test.magicfind

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.config.MfBreakdown
import dev.nytrix.nyaddons.core.ChatUtils
import dev.nytrix.nyaddons.features.magicfind.MagicFind
import dev.nytrix.nyaddons.features.magicfind.MfCategory
import dev.nytrix.nyaddons.features.magicfind.MfData
import dev.nytrix.nyaddons.features.magicfind.MfDrop
import dev.nytrix.nyaddons.features.magicfind.MfKillTracker
import dev.nytrix.nyaddons.features.magicfind.MfMath
import dev.nytrix.nyaddons.features.magicfind.MfMob
import dev.nytrix.nyaddons.features.magicfind.MfReporter
import dev.nytrix.nyaddons.features.magicfind.MfStats
import dev.nytrix.nyaddons.features.magicfind.MfTrackCommand
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.decoration.ArmorStand

/** In-game checks for the Magic Find kill detector, the batched report and /trackmob. */
@Suppress("UnstableApiUsage")
class MagicFindKillsTest : FabricClientGameTest {

    private val minotaur = MfMob("minotaur", "Minotaur", "x", listOf(MfDrop("Stick", 0.00006, false, false, null), MfDrop("Bone", 0.01, false, false, null)), 0, false)
    private val revenant = MfMob("revenanthorror", "Revenant Horror", "slayer", listOf(MfDrop("Scythe Blade", 0.001, false, false, null)), 5, true)
    private val yeti = MfMob("yeti", "Yeti", "x", listOf(
        MfDrop("Baby Yeti", 0.002, true, false, null),
        MfDrop("Eyes", 0.0, false, true, "per Summoning Eye"),
        MfDrop("Cap", 0.03, false, false, null),
    ), 0, false)
    private val zombie = MfMob("zombie", "Zombie", "x", listOf(MfDrop("Rotten", 0.01, false, false, null)), 0, false)
    private val all = listOf(minotaur, revenant, yeti, zombie)

    private class FakeData(val mobs: List<MfMob>) : MfData {
        override val ready = true
        override val categories = emptyList<MfCategory>()
        override fun mob(name: String) = mobs.firstOrNull { it.id == name.lowercase().filter(Char::isLetterOrDigit) }
        override fun mobNames() = mobs.map { it.name }
        override fun request() {}
    }

    private class FakeStats(var mf: Double?, var bonus: Double?, override val looting: Int) : MfStats {
        override val general get() = mf
        override val petLuck: Double? = 20.0
        override fun bonusFor(mob: MfMob) = bonus
    }

    private val out = ArrayList<Component>()
    private fun lines() = out.map { ChatUtils.stripColor(it.string) }
    private fun cfg() = NyAddOns.config.combat.magicFind

    override fun runTest(context: ClientGameTestContext) {
        System.setProperty("nyaddons.devArea", "Hub")
        val stats = FakeStats(250.0, null, 3)
        val oldData = MagicFind.data
        val oldStats = MagicFind.stats
        try {
            context.worldBuilder().create().use { world ->
                context.runOnClient<RuntimeException> {
                    MagicFind.data = FakeData(all)
                    MagicFind.stats = stats
                    MfKillTracker.reset(); MfReporter.reset()
                    MfReporter.sink = { out += it }
                    MfTrackCommand.say = { out += Component.literal(it) }
                    MfTrackCommand.sayComponent = { out += it }
                    cfg().enabled = true
                    cfg().windowSeconds = 1f
                    cfg().breakdown = MfBreakdown.HOVER
                    cfg().trackOdds = true
                    cfg().enabledMobs = mutableSetOf("minotaur", "revenanthorror")
                    cfg().trackedMobs = mutableSetOf()
                }

                // Batching: three identical kills and one other mob, nothing before the window ends.
                context.runOnClient<RuntimeException> {
                    repeat(3) { MfKillTracker.registerKill(minotaur) }
                    MfKillTracker.registerKill(revenant)
                }
                context.waitTicks(5)
                check(out.isEmpty()) { "printed before the window: ${lines()}" }
                context.waitTicks(40)
                check(lines() == listOf("Minotaur x3: 250% Magic Find (general only)", "Revenant Horror: 250% Magic Find (general only)")) { "batch: ${lines()}" }
                val hover = (out[0].style.hoverEvent as? HoverEvent.ShowText)?.value()?.string?.let(ChatUtils::stripColor)
                check(hover == "General 250% (no Minotaur bonus learned yet)") { "hover: $hover" }

                // A learned bonus splits the pair; the inline breakdown carries the parts.
                out.clear()
                stats.bonus = 62.0
                cfg().breakdown = MfBreakdown.INLINE
                context.runOnClient<RuntimeException> { MfKillTracker.registerKill(minotaur) }
                context.waitTicks(40)
                check(lines() == listOf("Minotaur: 312% Magic Find (General 250% + Minotaur 62% (learned))")) { "inline: ${lines()}" }
                out.clear()
                cfg().breakdown = MfBreakdown.OFF
                context.runOnClient<RuntimeException> {
                    MfKillTracker.registerKill(minotaur)
                    stats.bonus = null
                    MfKillTracker.registerKill(minotaur)
                    stats.bonus = 62.0
                    MfKillTracker.registerKill(minotaur)
                }
                context.waitTicks(40)
                check(lines() == listOf("Minotaur x2: 312% Magic Find", "Minotaur: 250% Magic Find (general only)")) { "split by MF: ${lines()}" }

                // Unknown general MF: said once.
                out.clear()
                stats.mf = null
                repeat(2) {
                    context.runOnClient<RuntimeException> { MfKillTracker.registerKill(minotaur) }
                    context.waitTicks(40)
                }
                check(lines() == listOf("Magic Find unknown (open the SkyBlock menu or show the Stats tab widget)")) { "unknown: ${lines()}" }
                stats.mf = 250.0

                // Name tag shapes seen on Hypixel (levels with a space, a leading symbol, health with suffixes).
                for ((tag, name) in listOf(
                    "[Lv100] Minotaur 1.5M/1.5M❤" to "Minotaur", "[Lv 60] Minos Champion 12.5k/12.5k❤" to "Minos Champion",
                    "§8[§7Lv10§8] §cGaia Construct §a2,500§f/§a2,500§c❤" to "Gaia Construct", "✯ [Lv100] Minotaur 5,000❤" to "Minotaur",
                    "☠ Revenant Horror IV 1.5M❤" to "Revenant Horror IV",
                )) check(MfKillTracker.parseTag(tag)?.name == name) { "tag \"$tag\" read as ${MfKillTracker.parseTag(tag)?.name}" }
                check(MfKillTracker.parseTag("Minotaur 50% charged") == null) { "non-mob tag read as a mob" }

                // /trackmob.
                out.clear()
                context.runOnClient<RuntimeException> {
                    MfTrackCommand.run("")
                    MfTrackCommand.run("minotaur")
                    check(cfg().trackedMobs.contains("minotaur") && cfg().trackedDrops.isEmpty()) { "tracking starts at once with all drops" }
                    MfTrackCommand.run("minotaur #9")
                    MfTrackCommand.run("minotaur #1")
                    check(cfg().trackedDrops["minotaur"] == "Stick") { "picked drop: ${cfg().trackedDrops}" }
                    MfTrackCommand.run("Revenant Horror")
                    MfTrackCommand.run("nothing")
                    MfTrackCommand.run("")
                    MfTrackCommand.run("minotaur")
                    check(cfg().trackedDrops.isEmpty()) { "untrack clears the drop: ${cfg().trackedDrops}" }
                    MfTrackCommand.run("minotaur #all")
                    check(cfg().trackedMobs.contains("minotaur") && cfg().trackedDrops.isEmpty()) { "all drops" }
                    MfTrackCommand.run("clear")
                    MfTrackCommand.run("")
                }
                check(lines() == listOf(
                    "Not tracking any mob. Use /ny trackmob <mob>.",
                    "Tracking Minotaur: all drops. Warning: this can spam chat.",
                    "Only want one drop? Click it (or type /ny trackmob Minotaur #<number>):",
                    "1. Stick ${MfMath.oneIn(minotaur.drops[0].chance)}",
                    "2. Bone ${MfMath.oneIn(minotaur.drops[1].chance)}",
                    "Pick a number from 1 to 2, or #all.",
                    "Tracking Minotaur: Stick. Warning: this can spam chat.",
                    "Tracking Revenant Horror. Warning: this can spam chat.",
                    "No mob with Magic Find drops is called \"nothing\".",
                    "Tracking: Minotaur, Revenant Horror",
                    "Stopped tracking Minotaur.",
                    "Tracking Minotaur: all drops. Warning: this can spam chat.",
                    "Stopped tracking all mobs.",
                    "Not tracking any mob. Use /ny trackmob <mob>.",
                )) { "trackmob: ${lines()}" }

                out.clear()
                context.runOnClient<RuntimeException> { MfTrackCommand.run("debug") }
                check(lines().firstOrNull()?.startsWith("Mob data loaded: true.") == true) { "debug: ${lines()}" }

                // Odds: tracked mob that is not enabled in the menu still reports. Looting 3, MF 312.
                out.clear()
                cfg().enabledMobs = mutableSetOf()
                cfg().trackedMobs = mutableSetOf("minotaur", "revenanthorror", "yeti")
                context.runOnClient<RuntimeException> {
                    MfKillTracker.registerKill(minotaur)
                    MfKillTracker.registerKill(revenant)
                    MfKillTracker.registerKill(revenant)
                    MfKillTracker.registerKill(yeti)
                }
                context.waitTicks(40)
                fun odds(d: MfDrop, mf: Double) = "${MfMath.oneIn(d.chance)} base → ${MfMath.oneIn(MfMath.chance(d.chance, mf, 3, d.pet, 20.0))} with Magic Find"
                val expected = listOf(
                    "Minotaur: 312% Magic Find",
                    "Stick: ${odds(minotaur.drops[0], 312.0)} (+Looting III)",
                    "Bone: ${odds(minotaur.drops[1], 312.0)} (+Looting III)",
                    "Revenant Horror x2: 312% Magic Find",
                    "Odds are not shown for slayer bosses (weighted loot pools).",
                    "Yeti: 312% Magic Find",
                    "Baby Yeti: ${odds(yeti.drops[0], 312.0)} (+Pet Luck)",
                    "Cap: ${odds(yeti.drops[2], 312.0)} (+Looting III)",
                    "Eyes: special (per Summoning Eye)",
                )
                check(lines() == expected) { "odds:\n${lines().joinToString("\n")}\nexpected:\n${expected.joinToString("\n")}" }
                check(lines()[1].startsWith("Stick: 1 in 16,667 base → ")) { "base first: ${lines()[1]}" }
                // Second slayer kill: the note is not repeated.
                out.clear()
                context.runOnClient<RuntimeException> { MfKillTracker.registerKill(revenant) }
                context.waitTicks(40)
                check(lines() == listOf("Revenant Horror: 312% Magic Find")) { "slayer note repeated: ${lines()}" }

                // One chosen drop: only its odds are shown.
                out.clear()
                cfg().trackedDrops["minotaur"] = "Bone"
                context.runOnClient<RuntimeException> { MfKillTracker.registerKill(minotaur) }
                context.waitTicks(40)
                check(lines() == listOf("Minotaur: 312% Magic Find", "Bone: ${odds(minotaur.drops[1], 312.0)} (+Looting III)")) { "chosen drop: ${lines()}" }
                cfg().trackedDrops.clear()

                // Odds off.
                out.clear()
                cfg().trackOdds = false
                context.runOnClient<RuntimeException> { MfKillTracker.registerKill(minotaur) }
                context.waitTicks(40)
                check(lines() == listOf("Minotaur: 312% Magic Find")) { "trackOdds off: ${lines()}" }
                cfg().trackOdds = true

                // Real detection with name-tag armor stands.
                out.clear()
                cfg().enabledMobs = mutableSetOf("minotaur")
                cfg().trackedMobs = mutableSetOf()
                fun summon(tag: String) = world.server.runCommand(
                    "execute at @p run summon armor_stand ~ ~ ~2 {CustomName:\"$tag\",CustomNameVisible:1b,Invisible:1b,NoGravity:1b,Marker:1b}",
                )
                fun hitNearestStand() = context.runOnClient<RuntimeException> { mc ->
                    val stand = mc.level!!.entitiesForRendering().filterIsInstance<ArmorStand>().first()
                    MfKillTracker.onAttack(stand)
                }
                // Killed without a hit: not counted.
                summon("[Lv100] Minotaur 100/100❤")
                context.waitTicks(20)
                world.server.runCommand("kill @e[type=armor_stand]")
                context.waitTicks(60)
                check(out.isEmpty()) { "kill without a hit counted: ${lines()}" }
                // Hit, then it vanishes.
                summon("[Lv100] Minotaur 100/100❤")
                context.waitTicks(15)
                hitNearestStand()
                context.waitTicks(10)
                world.server.runCommand("kill @e[type=armor_stand]")
                context.waitTicks(60)
                check(lines() == listOf("Minotaur: 312% Magic Find")) { "real kill: ${lines()}" }
                var recent: MfMob? = null
                context.runOnClient<RuntimeException> { recent = MagicFind.kills.recent(60_000) }
                check(recent === minotaur) { "MagicFind.kills" }
                // Hit, then the tag shows 0 health while the stand stays.
                out.clear()
                summon("[Lv100] Minotaur 100/100❤")
                context.waitTicks(15)
                hitNearestStand()
                world.server.runCommand("data modify entity @e[type=armor_stand,limit=1] CustomName set value \"[Lv100] Minotaur 0/100❤\"")
                context.waitTicks(60)
                check(lines() == listOf("Minotaur: 312% Magic Find")) { "0 health tag: ${lines()}" }
                world.server.runCommand("kill @e[type=armor_stand]")
                context.waitTicks(30)
                check(lines().size == 1) { "double counted: ${lines()}" }
                // A mob that is neither enabled nor tracked is ignored.
                out.clear()
                summon("[Lv10] Zombie 10/10❤")
                context.waitTicks(15)
                hitNearestStand()
                world.server.runCommand("kill @e[type=armor_stand]")
                context.waitTicks(60)
                check(out.isEmpty()) { "zombie reported: ${lines()}" }

                // Real chat output, looked at in a screenshot.
                cfg().trackedMobs = mutableSetOf("yeti")
                cfg().enabledMobs = mutableSetOf("minotaur")
                context.runOnClient<RuntimeException> {
                    MfReporter.sink = { ChatUtils.chat(it) }
                    repeat(3) { MfKillTracker.registerKill(minotaur) }
                    MfKillTracker.registerKill(yeti)
                }
                context.waitTicks(40)
                context.takeScreenshot("magicfind-report")

                // Cost per tick.
                context.runOnClient<RuntimeException> {
                    cfg().enabledMobs = mutableSetOf(); cfg().trackedMobs = mutableSetOf()
                    val runs = 200_000
                    repeat(runs / 10) { MfKillTracker.onTick() }
                    var start = System.nanoTime()
                    repeat(runs) { MfKillTracker.onTick() }
                    NyAddOnsBench.log("magic find kills, one tick with nothing enabled", (System.nanoTime() - start) / runs)
                    cfg().enabledMobs = mutableSetOf("minotaur")
                    summonClientStand(it)
                    repeat(runs / 100) { MfKillTracker.onTick() }
                    val n = 20_000
                    start = System.nanoTime()
                    repeat(n) { MfKillTracker.onTick() }
                    NyAddOnsBench.log("magic find kills, one tick with one enabled mob nearby (scan every 5th tick)", (System.nanoTime() - start) / n)
                }
            }
        } finally {
            MagicFind.data = oldData
            MagicFind.stats = oldStats
            MfReporter.sink = { ChatUtils.chat(it) }
            MfTrackCommand.say = { ChatUtils.chat(it) }
            MfTrackCommand.sayComponent = { ChatUtils.chat(it) }
            System.clearProperty("nyaddons.devArea")
        }
    }

    private fun summonClientStand(mc: Minecraft) {
        // The stand from the earlier checks is gone; a client-only stand is enough for timing the scan.
        val level = mc.level!!
        val stand = ArmorStand(net.minecraft.world.entity.EntityType.ARMOR_STAND, level)
        stand.customName = Component.literal("[Lv100] Minotaur 100/100❤")
        stand.setPos(mc.player!!.x + 2, mc.player!!.y, mc.player!!.z)
        level.addEntity(stand)
    }

    private object NyAddOnsBench {
        fun log(name: String, nanos: Long) = NyAddOns.logger.info("[NyBench] $name: $nanos ns")
    }
}
