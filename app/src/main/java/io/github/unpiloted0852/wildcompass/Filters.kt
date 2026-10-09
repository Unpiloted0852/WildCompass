package io.github.unpiloted0852.wildcompass

/**
 * The basic "what kind of thing" filters. [iNatIconic] is iNaturalist's iconic taxon name;
 * [gbif] is the equivalent filter in the GBIF backbone taxonomy (null: GBIF has no single
 * group that matches, so only iNaturalist is asked).
 */
enum class TaxonGroup(
    val label: String,
    val emoji: String,
    val iNatIconic: String?,
    val gbif: List<Pair<String, String>>?,
) {
    ANY("Anything", "🌍", null, emptyList()),
    BIRDS("Birds", "🐦", "Aves", listOf("classKey" to "212")),
    MAMMALS("Mammals", "🦊", "Mammalia", listOf("classKey" to "359")),
    // GBIF's backbone has no class Reptilia; it lists the reptile orders as classes.
    REPTILES(
        "Reptiles", "🦎", "Reptilia",
        listOf("classKey" to "11592253", "classKey" to "11418114", "classKey" to "11493978")
    ),
    AMPHIBIANS("Amphibians", "🐸", "Amphibia", listOf("classKey" to "131")),
    FISH("Fish", "🐟", "Actinopterygii", null),
    INSECTS("Insects", "🦋", "Insecta", listOf("classKey" to "216")),
    SPIDERS("Spiders", "🕷️", "Arachnida", listOf("classKey" to "367")),
    MOLLUSCS("Snails & shells", "🐌", "Mollusca", listOf("phylumKey" to "52")),
    PLANTS("Plants", "🌿", "Plantae", listOf("kingdomKey" to "6")),
    FUNGI("Fungi", "🍄", "Fungi", listOf("kingdomKey" to "5"));

    /** Lower-case noun for status messages: "Looking for birds…". */
    val noun: String get() = if (this == ANY) "observations" else label.lowercase()
}

enum class Recency(val label: String, val days: Long?) {
    ANY("Any time", null),
    YEAR("Past year", 365),
    MONTH("Past month", 30),
    WEEK("Past week", 7),
}
