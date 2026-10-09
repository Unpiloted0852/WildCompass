package io.github.unpiloted0852.wildcompass

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * iNaturalist API v1 (api.inaturalist.org/v1/docs). No key is needed for reading.
 * The API cannot sort by distance, so [search] returns the most recent records inside a
 * circle and [NearestFinder] narrows the circle until it holds few enough to see them all.
 */
object INaturalistClient {
    private const val PER_PAGE = 200
    private const val MAX_PHOTOS = 12

    /** Records with a stated position error above this are too vague to point at. */
    private const val MAX_ACCURACY_METERS = 500

    suspend fun search(
        lat: Double,
        lon: Double,
        radiusKm: Double,
        group: TaxonGroup,
        since: LocalDate?,
        researchOnly: Boolean,
    ): Page {
        val url = "https://api.inaturalist.org/v1/observations".toHttpUrl().newBuilder().apply {
            addQueryParameter("lat", lat.toString())
            addQueryParameter("lng", lon.toString())
            addQueryParameter("radius", String.format(Locale.US, "%.3f", radiusKm))
            addQueryParameter("photos", "true")
            addQueryParameter("identified", "true")
            // Wild things only: no zoo animals or garden plants.
            addQueryParameter("captive", "false")
            // Leave out records whose true location is hidden (threatened species, or the
            // observer's choice): their public coordinates can be kilometres off.
            addQueryParameter("geoprivacy", "open")
            addQueryParameter("taxon_geoprivacy", "open")
            addQueryParameter("acc_below_or_unknown", MAX_ACCURACY_METERS.toString())
            addQueryParameter("quality_grade", if (researchOnly) "research" else "research,needs_id")
            group.iNatIconic?.let { addQueryParameter("iconic_taxa", it) }
            since?.let { addQueryParameter("d1", it.toString()) }
            addQueryParameter("order_by", "observed_on")
            addQueryParameter("order", "desc")
            addQueryParameter("per_page", PER_PAGE.toString())
            addQueryParameter("locale", Locale.getDefault().toLanguageTag())
        }.build()

        val json = Http.getJson(url)
        val results = json.optJSONArray("results")
        val items = ArrayList<Observation>()
        if (results != null) {
            for (i in 0 until results.length()) parse(results.getJSONObject(i))?.let { items.add(it) }
        }
        return Page(items, complete = json.optInt("total_results") <= (results?.length() ?: 0))
    }

    private fun parse(o: JSONObject): Observation? {
        if (o.optBoolean("obscured")) return null
        val coords = o.optJSONObject("geojson")?.optJSONArray("coordinates") ?: return null
        val lon = coords.optDouble(0)
        val lat = coords.optDouble(1)
        if (lat.isNaN() || lon.isNaN()) return null

        val photos = ArrayList<Photo>()
        val photoArray = o.optJSONArray("photos")
        if (photoArray != null) {
            for (i in 0 until minOf(photoArray.length(), MAX_PHOTOS)) {
                val p = photoArray.optJSONObject(i) ?: continue
                // The API hands out the 75 px "square" rendition; the others share its path.
                val square = p.str("url") ?: continue
                val medium = square.replace("/square.", "/medium.")
                photos.add(
                    Photo(
                        url = medium,
                        fullUrls = listOf(
                            square.replace("/square.", "/original."), square.replace("/square.", "/large."), medium
                        ),
                        credit = p.str("attribution"),
                    )
                )
            }
        }
        if (photos.isEmpty()) return null
        val taxon = o.optJSONObject("taxon")
        val user = o.optJSONObject("user")
        val id = o.optLong("id")

        return Observation(
            source = Source.INATURALIST,
            id = id.toString(),
            lat = lat,
            lon = lon,
            commonName = taxon?.str("preferred_common_name") ?: o.str("species_guess"),
            scientificName = taxon?.str("name"),
            photos = photos,
            observedOn = o.str("observed_on"),
            observer = user?.str("name") ?: user?.str("login"),
            place = o.str("place_guess"),
            recordUrl = o.str("uri") ?: "https://www.inaturalist.org/observations/$id",
            sourceLabel = "iNaturalist",
            researchGrade = o.optString("quality_grade") == "research",
        )
    }
}
