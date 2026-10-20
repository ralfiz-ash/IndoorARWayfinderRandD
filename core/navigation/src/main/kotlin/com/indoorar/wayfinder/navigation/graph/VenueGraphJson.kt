package com.indoorar.wayfinder.navigation.graph

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.venue.FloorId
import com.indoorar.wayfinder.domain.venue.VenueId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Developer-editable venue graph file (see docs/ROUTE_GRAPH.md). The same JSON can be decoded with
 * Swift `Codable` on iOS. Coordinates are metres in the venue frame (relative to the marker).
 */
@Serializable
data class VenueGraphDocument(
    val formatVersion: Int = 1,
    val venueId: String,
    val frameVersion: Int,
    val nodes: List<NodeDto> = emptyList(),
    val edges: List<EdgeDto> = emptyList(),
) {
    @Serializable
    data class NodeDto(
        val id: String,
        val type: NodeType = NodeType.JUNCTION,
        val floor: String,
        val x: Double,
        val z: Double,
        val y: Double = 0.0,
        val name: String? = null,
    )

    @Serializable
    data class EdgeDto(
        val from: String,
        val to: String,
        val bidirectional: Boolean = true,
        val attachable: Boolean = true,
        val blocked: Boolean = false,
        @SerialName("length") val lengthMeters: Double? = null,
    )
}

sealed interface GraphLoadResult {
    data class Loaded(val graph: RouteGraph) : GraphLoadResult
    data class ParseError(val message: String) : GraphLoadResult
    data class UnsupportedFormat(val version: Int) : GraphLoadResult
    data class InvalidGraph(val errors: List<GraphError>) : GraphLoadResult
}

object VenueGraphJson {
    const val SUPPORTED_FORMAT = 1
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): GraphLoadResult {
        val doc = try {
            json.decodeFromString(VenueGraphDocument.serializer(), text)
        } catch (e: SerializationException) {
            return GraphLoadResult.ParseError(e.message ?: "Invalid JSON")
        } catch (e: IllegalArgumentException) {
            return GraphLoadResult.ParseError(e.message ?: "Invalid JSON")
        }
        if (doc.formatVersion != SUPPORTED_FORMAT) return GraphLoadResult.UnsupportedFormat(doc.formatVersion)
        val result = RouteGraph.build(
            venueId = VenueId(doc.venueId),
            frameVersion = doc.frameVersion,
            nodes = doc.nodes.map { RouteNode(NodeId(it.id), it.type, FloorId(it.floor), Vec3(it.x, it.y, it.z), it.name) },
            edges = doc.edges.map { RouteEdge(NodeId(it.from), NodeId(it.to), it.bidirectional, it.attachable, it.blocked, it.lengthMeters) },
        )
        return when (result) {
            is GraphBuildResult.Valid -> GraphLoadResult.Loaded(result.graph)
            is GraphBuildResult.Invalid -> GraphLoadResult.InvalidGraph(result.errors)
        }
    }
}
