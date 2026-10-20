package com.indoorar.wayfinder

import android.content.Context
import com.indoorar.wayfinder.domain.venue.Venue
import com.indoorar.wayfinder.navigation.graph.GraphLoadResult
import com.indoorar.wayfinder.navigation.graph.RouteGraph
import com.indoorar.wayfinder.navigation.graph.VenueGraphJson
import java.io.IOException

sealed interface GraphStatus {
    data class Ready(val graph: RouteGraph) : GraphStatus
    /** File present but contains no usable paths. */
    data object NotConfigured : GraphStatus
    data class Invalid(val message: String) : GraphStatus
    /** Graph coordinates were measured for another venue or marker placement. */
    data class FrameMismatch(val graphVersion: Int, val venueVersion: Int) : GraphStatus

    val graphOrNull: RouteGraph? get() = (this as? Ready)?.graph
}

/** Loads the developer-authored route graph from `assets/venue_graph.json` (see docs/ROUTE_GRAPH.md). */
object VenueGraphLoader {
    const val ASSET = "venue_graph.json"

    fun load(context: Context, venue: Venue): GraphStatus {
        val text = try {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            return GraphStatus.NotConfigured
        }
        return evaluate(VenueGraphJson.parse(text), venue)
    }

    fun evaluate(result: GraphLoadResult, venue: Venue): GraphStatus = when (result) {
        is GraphLoadResult.ParseError -> GraphStatus.Invalid("Not valid graph JSON: ${result.message.lineSequence().first()}")
        is GraphLoadResult.UnsupportedFormat -> GraphStatus.Invalid("Unsupported formatVersion ${result.version}")
        is GraphLoadResult.InvalidGraph -> GraphStatus.Invalid(
            "${result.errors.size} error(s), first: ${result.errors.first()}",
        )
        is GraphLoadResult.Loaded -> when {
            result.graph.venueId != venue.id -> GraphStatus.Invalid("Graph is for venue \"${result.graph.venueId.value}\"")
            result.graph.frameVersion != venue.frameVersion ->
                GraphStatus.FrameMismatch(result.graph.frameVersion, venue.frameVersion)
            result.graph.isEmpty -> GraphStatus.NotConfigured
            else -> GraphStatus.Ready(result.graph)
        }
    }
}
