package io.github.unpiloted0852.wildcompass

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

/**
 * Finds the observations nearest to a point.
 *
 * Neither database can sort by distance; each returns up to a few hundred records from
 * inside a circle. So the circle is adjusted until it is known to hold everything:
 *  - empty circle: widen it (1 km, 5, 25, 125, 300);
 *  - overfull circle: the true nearest record is no farther than the nearest one in the
 *    sample, so shrink the circle to that distance and ask again.
 * In a city that is typically two requests per source; in the wilderness, a few more.
 */
object NearestFinder {
    private const val START_RADIUS_KM = 1.0
    private const val MAX_RADIUS_KM = 300.0
    private const val WIDEN_FACTOR = 5.0
    private const val MIN_RADIUS_KM = 0.02
    private const val MAX_SHRINKS = 3
    private const val MAX_REQUESTS = 8

    class Result(
        /** Sorted nearest first, as seen from the search point. */
        val observations: List<Observation>,
        /** Sources that could not be reached. */
        val failed: List<Source>,
        val asked: Int,
    ) {
        val allFailed: Boolean get() = asked > 0 && failed.size == asked
    }

    suspend fun find(
        lat: Double,
        lon: Double,
        group: TaxonGroup,
        recency: Recency,
        sources: Set<Source>,
        researchOnly: Boolean,
    ): Result = coroutineScope {
        val since = recency.days?.let { LocalDate.now().minusDays(it) }
        val jobs = sources.map { source ->
            source to async {
                try {
                    narrow(lat, lon) { radiusKm ->
                        when (source) {
                            Source.INATURALIST ->
                                INaturalistClient.search(lat, lon, radiusKm, group, since, researchOnly)
                            Source.GBIF -> GbifClient.search(lat, lon, radiusKm, group, since)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
        val found = ArrayList<Observation>()
        val failed = ArrayList<Source>()
        for ((source, job) in jobs) {
            val list = job.await()
            if (list == null) failed.add(source) else found.addAll(list)
        }
        Result(found.sortedBy { it.distanceFrom(lat, lon) }, failed, sources.size)
    }

    private suspend fun narrow(
        lat: Double,
        lon: Double,
        fetch: suspend (radiusKm: Double) -> Page,
    ): List<Observation> {
        val pool = LinkedHashMap<String, Observation>()
        var radiusKm = START_RADIUS_KM
        var shrinks = 0
        repeat(MAX_REQUESTS) {
            val page = fetch(radiusKm)
            page.items.forEach { pool[it.key] = it }
            if (page.complete) {
                if (pool.isNotEmpty() || radiusKm >= MAX_RADIUS_KM) return pool.values.toList()
                radiusKm = (radiusKm * WIDEN_FACTOR).coerceAtMost(MAX_RADIUS_KM)
            } else {
                val nearestKm = page.items.minOfOrNull { it.distanceFrom(lat, lon) }?.div(1000.0)
                if (nearestKm == null || shrinks >= MAX_SHRINKS || nearestKm >= radiusKm * 0.95) {
                    return pool.values.toList()
                }
                radiusKm = (nearestKm * 1.02 + 0.005).coerceAtLeast(MIN_RADIUS_KM)
                shrinks++
            }
        }
        return pool.values.toList()
    }
}
