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
    private const val MAX_REQUESTS = 9

    class Result(
        /** Sorted nearest first, as seen from the search point. */
        val observations: List<Observation>,
        /**
         * Everything within this distance of the search point is in [observations], so the
         * nearest record to any spot can be told for certain while that spot's distance from
         * the search point plus its distance to the record stays inside it. Zero when unknown.
         */
        val exactRadiusMeters: Float,
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
        /** Where to begin; a refresh passes a circle a little wider than the last answer. */
        startRadiusKm: Double = START_RADIUS_KM,
    ): Result = coroutineScope {
        val since = recency.days?.let { LocalDate.now().minusDays(it) }
        val jobs = sources.map { source ->
            source to async {
                try {
                    narrow(lat, lon, startRadiusKm) { radiusKm ->
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
        var exactKm = Double.MAX_VALUE
        for ((source, job) in jobs) {
            val answer = job.await()
            if (answer == null) {
                failed.add(source)
            } else {
                found.addAll(answer.observations)
                exactKm = minOf(exactKm, answer.exactRadiusKm)
            }
        }
        if (exactKm == Double.MAX_VALUE) exactKm = 0.0
        Result(found.sortedBy { it.distanceFrom(lat, lon) }, (exactKm * 1000).toFloat(), failed, sources.size)
    }

    private suspend fun narrow(
        lat: Double,
        lon: Double,
        startRadiusKm: Double,
        fetch: suspend (radiusKm: Double) -> Page,
    ): Narrowed {
        val pool = LinkedHashMap<String, Observation>()
        var radiusKm = startRadiusKm.coerceIn(MIN_RADIUS_KM, MAX_RADIUS_KM)
        var shrinks = 0
        repeat(MAX_REQUESTS) {
            val page = fetch(radiusKm)
            page.items.forEach { pool[it.key] = it }
            if (page.complete) {
                if (pool.isNotEmpty() || radiusKm >= MAX_RADIUS_KM) return Narrowed(pool.values.toList(), radiusKm)
                radiusKm = (radiusKm * WIDEN_FACTOR).coerceAtMost(MAX_RADIUS_KM)
            } else {
                val nearestKm = page.items.minOfOrNull { it.distanceFrom(lat, lon) }?.div(1000.0)
                if (nearestKm == null || shrinks >= MAX_SHRINKS || nearestKm >= radiusKm * 0.95) {
                    return Narrowed(pool.values.toList(), 0.0)
                }
                radiusKm = (nearestKm * 1.02 + 0.005).coerceAtLeast(MIN_RADIUS_KM)
                shrinks++
            }
        }
        return Narrowed(pool.values.toList(), 0.0)
    }

    /** One source's answer: what it found, and the radius inside which nothing was left out. */
    private class Narrowed(val observations: List<Observation>, val exactRadiusKm: Double)
}
