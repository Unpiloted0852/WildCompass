package io.github.unpiloted0852.wildcompass

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

/**
 * GBIF occurrence API (techdocs.gbif.org/en/openapi). No key is needed for reading.
 * GBIF gathers records from many platforms (Observation.org, Pl@ntNet, national atlases...).
 * It also republishes iNaturalist's research-grade records, which are skipped here because
 * [INaturalistClient] already gets them first-hand.
 */
object GbifClient {
    private const val LIMIT = 300
    private const val MAX_PHOTOS = 12
    private const val INATURALIST_DATASET = "50c9509d-22c7-4a22-a47d-8c48425ef4a7"
    private const val MAX_DATASETS = 40
    private const val MAX_UNCERTAINTY_METERS = 500.0

    suspend fun search(
        lat: Double,
        lon: Double,
        radiusKm: Double,
        group: TaxonGroup,
        since: LocalDate?,
    ): Page {
        val taxonFilter = group.gbif ?: return Page(emptyList(), complete = true)

        fun query(): HttpUrl.Builder =
            "https://api.gbif.org/v1/occurrence/search".toHttpUrl().newBuilder().apply {
                addQueryParameter(
                    "geoDistance", String.format(Locale.US, "%.6f,%.6f,%.3fkm", lat, lon, radiusKm)
                )
                addQueryParameter("mediaType", "StillImage")
                addQueryParameter("hasGeospatialIssue", "false")
                addQueryParameter("occurrenceStatus", "PRESENT")
                // Sightings only: museum and herbarium specimens are not something to walk to.
                addQueryParameter("basisOfRecord", "HUMAN_OBSERVATION")
                addQueryParameter("basisOfRecord", "MACHINE_OBSERVATION")
                addQueryParameter("basisOfRecord", "OBSERVATION")
                taxonFilter.forEach { (name, value) -> addQueryParameter(name, value) }
                since?.let { addQueryParameter("eventDate", "$it,${LocalDate.now()}") }
                addQueryParameter("limit", LIMIT.toString())
            }

        val first = Http.getJson(
            query().addQueryParameter("facet", "datasetKey").addQueryParameter("facetLimit", "100").build()
        )
        if (first.optInt("count") <= LIMIT) return Page(parseAll(first), complete = true)

        // Too many to see at once, and usually most are iNaturalist's. The search cannot
        // exclude a dataset, so ask again for just the other datasets found in the circle.
        val others = ArrayList<String>()
        val counts = first.optJSONArray("facets")?.optJSONObject(0)?.optJSONArray("counts")
        if (counts != null) {
            for (i in 0 until counts.length()) {
                val name = counts.getJSONObject(i).optString("name")
                if (name.isNotEmpty() && name != INATURALIST_DATASET) others.add(name)
            }
        }
        if (others.isEmpty()) return Page(emptyList(), complete = true)

        val builder = query()
        others.take(MAX_DATASETS).forEach { builder.addQueryParameter("datasetKey", it) }
        val second = Http.getJson(builder.build())
        return Page(
            parseAll(second),
            complete = second.optInt("count") <= LIMIT && others.size <= MAX_DATASETS
        )
    }

    /** A common name for a species, in the phone's language if GBIF has one, else English. */
    suspend fun commonName(speciesKey: Long): String? {
        val json = Http.getJson(
            "https://api.gbif.org/v1/species/$speciesKey/vernacularNames?limit=200".toHttpUrl()
        )
        val results = json.optJSONArray("results") ?: return null
        // Publishers contribute names of mixed quality (banding codes such as "COST" for the
        // Common Starling among them), so take the name most of them agree on.
        val byLanguage = HashMap<String, HashMap<String, Int>>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val name = r.str("vernacularName") ?: continue
            if (name.length <= 6 && name == name.uppercase()) continue
            val names = byLanguage.getOrPut(r.optString("language")) { HashMap() }
            names[name.lowercase()] = (names[name.lowercase()] ?: 0) + 1
        }
        val names = byLanguage[Locale.getDefault().isO3Language] ?: byLanguage["eng"] ?: return null
        return names.maxByOrNull { it.value }?.key
    }

    private fun parseAll(json: JSONObject): List<Observation> {
        val results = json.optJSONArray("results") ?: return emptyList()
        val items = ArrayList<Observation>()
        for (i in 0 until results.length()) parse(results.getJSONObject(i))?.let { items.add(it) }
        return items
    }

    private fun parse(o: JSONObject): Observation? {
        if (o.optString("datasetKey") == INATURALIST_DATASET) return null
        val lat = o.optDouble("decimalLatitude")
        val lon = o.optDouble("decimalLongitude")
        if (lat.isNaN() || lon.isNaN()) return null
        // Vague or deliberately blurred positions would send the arrow to the wrong place.
        val uncertainty = o.optDouble("coordinateUncertaintyInMeters")
        if (!uncertainty.isNaN() && uncertainty > MAX_UNCERTAINTY_METERS) return null
        val withheld = o.str("informationWithheld")?.lowercase().orEmpty()
        if ("coordinate" in withheld || "obscur" in withheld || "location" in withheld) return null

        val key = o.optLong("key")
        val photos = ArrayList<Photo>()
        val media = o.optJSONArray("media") ?: return null
        for (i in 0 until media.length()) {
            if (photos.size >= MAX_PHOTOS) break
            val m = media.getJSONObject(i)
            val original = m.str("identifier") ?: continue
            if (m.optString("type") != "StillImage" || !original.startsWith("http")) continue
            // GBIF's image cache serves any publisher's photo at a sensible size.
            val cached = "https://api.gbif.org/v1/image/cache/800x/occurrence/$key/media/${md5(original)}"
            val holder = m.str("rightsHolder") ?: m.str("creator") ?: o.str("recordedBy")
            val license = licenseName(m.str("license") ?: o.str("license"))
            photos.add(
                Photo(
                    url = cached,
                    fullUrls = listOf(original, cached),
                    credit = listOfNotNull(holder?.let { "© $it" }, license).joinToString(", ").ifEmpty { null },
                )
            )
        }
        if (photos.isEmpty()) return null
        val gbifPage = "https://www.gbif.org/occurrence/$key"
        val publisher = (o.str("institutionCode") ?: o.str("datasetName"))?.takeIf { it.length <= 28 }

        return Observation(
            source = Source.GBIF,
            id = key.toString(),
            lat = lat,
            lon = lon,
            commonName = null,
            scientificName = o.str("species") ?: o.str("acceptedScientificName") ?: o.str("scientificName"),
            photos = photos,
            observedOn = o.str("eventDate")?.take(10)?.takeIf { DATE.matches(it) },
            observer = o.str("recordedBy"),
            place = listOfNotNull(o.str("locality"), o.str("stateProvince")).firstOrNull(),
            recordUrl = o.str("references")?.takeIf { it.startsWith("http") } ?: gbifPage,
            sourceLabel = if (publisher != null) "$publisher · GBIF" else "GBIF",
            gbifSpeciesKey = if (o.has("speciesKey")) o.optLong("speciesKey") else null,
        )
    }

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    /** "http://creativecommons.org/licenses/by-nc/4.0/" -> "CC BY-NC 4.0". */
    private fun licenseName(url: String?): String? {
        if (url == null) return null
        if ("publicdomain/zero" in url) return "CC0"
        val m = Regex("""creativecommons\.org/licenses/([a-z-]+)/(\d\.\d)""").find(url) ?: return null
        return "CC ${m.groupValues[1].uppercase()} ${m.groupValues[2]}"
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
