package dev.nytrix.nyaddons.test.magicfind

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.features.magicfind.MagicFindDataImpl
import dev.nytrix.nyaddons.features.magicfind.MfMath
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import kotlin.math.abs

/** Parses copies of the real mobs.json (SkyblockAPI Repo, with the NPCs cut) and bestiary.json (NEU-REPO) and checks the data and the odds math. No world needed. */
@Suppress("UnstableApiUsage")
class MagicFindDataTest : FabricClientGameTest {

    private fun check(ok: Boolean, message: () -> String) {
        if (!ok) throw AssertionError("MagicFindData: ${message()}")
    }

    private fun near(a: Double, b: Double) = abs(a - b) < 1e-9

    private fun usedHeap(): Long {
        repeat(3) { System.gc() }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }

    override fun runTest(context: ClientGameTestContext) {
        val bestiary = javaClass.getResourceAsStream("/magicfind/bestiary.json")!!.bufferedReader().readText()
        val mobs = javaClass.getResourceAsStream("/magicfind/mobs.json")!!.bufferedReader().readText()
        val data = MagicFindDataImpl
        data.clear()
        check(!data.ready && data.categories.isEmpty() && data.mob("zealot") == null) { "data before load" }

        val before = usedHeap()
        val start = System.nanoTime()
        data.loadFrom(bestiary.reader(), mobs.reader())
        val parseMs = (System.nanoTime() - start) / 1_000_000
        val retained = usedHeap() - before
        val categories = data.categories
        val listed = categories.flatMap { it.mobs }.distinct()
        NyAddOns.logger.info(
            "[NyBench] magic find test: parsed in $parseMs ms, retains about ${retained / 1024} KB, ${categories.size} categories, " +
                "${listed.size} menu mobs, ${listed.sumOf { it.drops.size }} drops, ${data.mobNames().size} lookup names",
        )

        // Categories: only curated ones, none empty, slayers last.
        val curated = setOf(
            "Spider's Den", "The End", "Crimson Isle", "Crystal Hollows", "Dwarven Mines", "Mythological Creatures", "Fishing", "Kuudra",
            "Moonglade Marsh", "Torrhus Canyon", "Slayer Bosses",
        )
        check(categories.isNotEmpty() && categories.last().name == "Slayer Bosses") { "categories ${categories.map { it.name }}" }
        for (c in categories) {
            check(c.name in curated) { "junk category ${c.name}" }
            check(c.mobs.isNotEmpty()) { "empty category ${c.name}" }
        }
        for (must in listOf("Spider's Den", "The End", "Crimson Isle", "Crystal Hollows", "Dwarven Mines", "Mythological Creatures", "Fishing")) {
            check(categories.any { it.name == must }) { "missing category $must" }
        }
        for (junk in listOf("Hub", "Private Island", "Garden", "Catacombs", "Deep Caverns", "The Park")) {
            check(categories.none { it.name == junk }) { "$junk must not be a category" }
        }
        check(categories.first { it.name == "Fishing" }.mobs.size > 20) { "fishing subcategories not flattened" }

        // Every listed drop is below 5% or special; every listed mob has at least one real (non-special) drop below 5% or a special one.
        for (mob in listed) {
            check(mob.drops.isNotEmpty()) { "${mob.name} has no drops" }
            for (d in mob.drops) check(d.special || (d.chance > 0 && d.chance < MfMath.MF_THRESHOLD)) { "${mob.name}: ${d.item} chance ${d.chance}" }
            check(mob.id == mob.name.filter { it.isLetterOrDigit() }.lowercase()) { "id of ${mob.name} is ${mob.id}" }
        }

        // Slayer bosses.
        val slayers = categories.first { it.name == "Slayer Bosses" }.mobs
        check(slayers.all { it.slayer } && slayers.map { it.slayerTier }.all { it in 1..5 }) { "slayer tiers" }
        for (boss in listOf("Revenant Horror", "Tarantula Broodfather", "Sven Packmaster", "Voidgloom Seraph", "Inferno Demonlord", "Riftstalker Bloodfiend")) {
            val tiers = slayers.filter { it.name.startsWith(boss) }
            check(tiers.size >= 4) { "$boss tiers ${tiers.map { it.name }}" }
            val top = tiers.maxBy { it.slayerTier }
            check(tiers.filter { it.defaultOn } == listOf(top)) { "$boss default-on is ${tiers.filter { it.defaultOn }.map { it.name }}, top ${top.name}" }
        }
        check(data.mob("Revenant Horror V")!!.slayerTier == 5 && data.mob("Revenant Horror V")!!.defaultOn) { "Revenant Horror V" }
        check(data.mob("Revenant Horror IV")!!.slayerTier == 4 && !data.mob("☠ Revenant Horror IV 1.5M❤")!!.defaultOn) { "Revenant Horror IV by name tag" }
        check(data.mob("Revenant Horror")!!.slayerTier == 5) { "slayer without a tier finds the top tier" }
        check(data.mob("Voidgloom Seraph")!!.slayerTier == 4) { "Voidgloom tops out at 4" }

        // Defaults.
        val defaultOn = listed.filter { it.defaultOn }.map { it.name }.toSet()
        check("King Minos" in defaultOn && "Minos Inquisitor" in defaultOn) { "default on $defaultOn" }
        check(listed.filter { it.defaultOn && !it.slayer }.map { it.name }.toSet() == setOf("King Minos", "Minos Inquisitor")) { "only the two minos default on" }

        // Lookups.
        val minotaur = data.mob("[Lv100] Minotaur") ?: throw AssertionError("no minotaur")
        check(minotaur === data.mob("minotaur") && minotaur === data.mob("§a[Lv100] Minotaur 1.5M/1.5M❤") && minotaur === data.mob("MINOTAUR 5")) { "minotaur lookups" }
        check(data.mob("Do-not-eat") == null) { "unknown name" }
        check(data.mob("Zealot")!!.drops.any { it.item == "Summoning Eye" && near(it.chance, 1.0 / 420) }) { "zealot summoning eye override" }
        check(data.mob("Arachne") != null) { "arachne" }
        val apostrophe = data.mobNames().filter { '\'' in it }
        check(apostrophe.isNotEmpty() && apostrophe.all { data.mob(it.replace("'", "")) === data.mob(it) && data.mob(it.uppercase()) === data.mob(it) }) { "apostrophe names" }
        val dragonPet = listed.firstOrNull { it.name == "Dragon" }?.drops?.firstOrNull { it.pet }
        check(dragonPet != null && dragonPet.special) { "dragon pet drops are special (per Summoning Eye)" }
        val petMob = listed.firstOrNull { m -> m.drops.any { it.pet && !it.special } } ?: throw AssertionError("no mob with a plain pet drop")
        // A mob outside the menu is still found.
        val menuIds = listed.map { it.id }.toSet()
        val outside = data.mobNames().map { data.mob(it)!! }.firstOrNull { it.id !in menuIds && !it.slayer }
        check(outside != null && outside.drops.isNotEmpty() && data.mob(outside.name) === outside) { "no non-menu mob found by name" }
        check(data.mobNames() == data.mobNames().sorted() && "Zealot" in data.mobNames() && "King Minos" in data.mobNames()) { "mobNames" }

        // Math.
        check(near(MfMath.chance(0.01, 0.0, 0, false, 0.0), 0.01)) { "MF 0 gives the base chance" }
        check(near(MfMath.chance(0.01, 100.0, 0, false, 50.0), 0.02)) { "MF 100 doubles" }
        check(near(MfMath.chance(0.001, 100.0, 0, true, 50.0), 0.0025)) { "pet drop uses MF + pet luck" }
        check(near(MfMath.chance(0.001, 100.0, 5, true, 0.0), 0.002)) { "pet drops ignore Looting" }
        check(near(MfMath.chance(0.03, 100.0, 4, false, 0.0), 0.03 * 1.6 * 2)) { "Looting IV then MF" }
        check(near(MfMath.chance(0.03, 100.0, 5, false, 0.0), 0.03 * 1.75)) { "5.25% after Looting V is not boosted" }
        check(near(MfMath.chance(0.05, 500.0, 0, false, 0.0), 0.05)) { "5% exactly is not boosted" }
        check(near(MfMath.chance(0.001, 2000.0, 0, false, 0.0), 0.001 * 10)) { "MF is capped at 900" }
        check(near(MfMath.chance(0.04, 900.0, 0, false, 0.0), 0.4) && MfMath.chance(0.08, 900.0, 0, false, 0.0) <= 1.0) { "never above 1" }
        check(near(MfMath.chance(0.5, 0.0, 10, false, 0.0), 1.0)) { "Looting cannot exceed 1" }
        check(MfMath.oneIn(0.0001) == "1 in 10,000" && MfMath.oneIn(1.0) == "always" && MfMath.oneIn(0.0) == "never") { "oneIn" }
        check(petMob.drops.isNotEmpty()) { "pet mob" }

        // Freed after idle and reloaded.
        check(data.ready) { "ready before expiry" }
        data.expireNow()
        check(!data.ready && data.categories.isEmpty() && data.mob("zealot") == null) { "not freed after idle" }
        data.loadFrom(bestiary.reader(), mobs.reader())
        check(data.ready && data.categories.size == categories.size && data.mob("Minotaur") != null) { "not reloaded" }
        data.clear()
    }
}
