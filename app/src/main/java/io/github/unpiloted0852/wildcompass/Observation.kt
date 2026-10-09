package io.github.unpiloted0852.wildcompass

import android.location.Location

enum class Source { INATURALIST, GBIF }

/** One photographed wildlife record with a public, precise location. */
data class Observation(
    val source: Source,
    val id: String,
    val lat: Double,
    val lon: Double,
    val commonName: String?,
    val scientificName: String?,
    /** Card-sized photo. */
    val photoUrl: String,
    /** Larger photo for the full-screen view; also the fallback when [photoUrl] fails. */
    val photoLargeUrl: String,
    val photoCredit: String?,
    /** ISO date (yyyy-MM-dd) when known. */
    val observedOn: String?,
    val observer: String?,
    val place: String?,
    /** Where the record lives: the iNaturalist page, or the publisher's page for GBIF records. */
    val recordUrl: String,
    /** Short label of where the record comes from, e.g. "iNaturalist" or "Observation.org · GBIF". */
    val sourceLabel: String,
    val researchGrade: Boolean = false,
    /** GBIF species key, used to look up a common name. */
    val gbifSpeciesKey: Long? = null,
) {
    val key: String get() = "${source.name}/$id"

    fun distanceFrom(here: Location): Float = distanceFrom(here.latitude, here.longitude)

    fun distanceFrom(fromLat: Double, fromLon: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(fromLat, fromLon, lat, lon, out)
        return out[0]
    }

    fun bearingFrom(here: Location): Float {
        val out = FloatArray(2)
        Location.distanceBetween(here.latitude, here.longitude, lat, lon, out)
        return out[1]
    }
}

/** One answer from a source: [complete] means nothing inside the asked radius was left out. */
class Page(val items: List<Observation>, val complete: Boolean)
