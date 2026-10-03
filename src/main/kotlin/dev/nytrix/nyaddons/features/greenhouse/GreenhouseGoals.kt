package dev.nytrix.nyaddons.features.greenhouse

object GreenhouseGoals {

    /** Mutations left out of the checklist: Shellfruit is a secret explosion event and Jerryflower needs quest items. */
    val skippedMutations = setOf("shellfruit", "jerryflower")

    /** Mutations tracked by the checklist (40 minus the two skipped ones). */
    const val TRACKED_MUTATIONS = 38

    val roseDragonMutations = listOf("Glasscorn", "Devourer", "All-in Aloe", "Phantomleaf", "Timestalk")
}
