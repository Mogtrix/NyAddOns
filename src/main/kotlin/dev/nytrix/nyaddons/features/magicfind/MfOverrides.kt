package dev.nytrix.nyaddons.features.magicfind

/**
 * Hand-written fixes for gaps in the downloaded data. Keys are normalised mob names (see [MagicFindDataImpl.normalize]).
 * Keep this table tiny: anything the data gets right belongs in the data.
 */
internal object MfOverrides {

    /** Drops the data lacks, added to the mob's own list (nothing happens if the data already lists the same item and chance). */
    val extraDrops: Map<String, List<MfDrop>> = mapOf(
        // Zealot's Summoning Eye is missing from SkyblockAPI's mobs.json. 1/420 is the commonly quoted figure; the wiki's Zealot page does
        // not state it in its raw text (it says Special Zealots have a guaranteed-style odds template), so this is unverified.
        "zealot" to listOf(MfDrop("Summoning Eye", 1.0 / 420, pet = false, special = false, note = "unverified")),
    )

    /** Bestiary family name (normalised) to the mob names in the data whose drops it uses. */
    val aliases: Map<String, List<String>> = mapOf(
        "dragon" to listOf("strongenderdragon"),
    )
}
