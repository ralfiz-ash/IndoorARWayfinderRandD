package com.indoorar.wayfinder

import com.indoorar.wayfinder.navigation.graph.VenueGraphJson
import com.indoorar.wayfinder.navigation.route.RouteFailure
import com.indoorar.wayfinder.ui.StatusLevel
import com.indoorar.wayfinder.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GraphStatusTest {
    private val venue = DefaultVenue.venue

    private fun evaluate(json: String) = VenueGraphLoader.evaluate(VenueGraphJson.parse(json), venue)

    private val twoNodes = """
        {"venueId":"default-venue","frameVersion":1,
         "nodes":[{"id":"a","floor":"ground","x":0,"z":1},{"id":"b","floor":"ground","x":0,"z":9}],
         "edges":[{"from":"a","to":"b"}]}
    """.trimIndent()

    @Test
    fun shippedAssetIsAnHonestEmptyGraph() {
        // The bundled graph must not contain fictional geometry for an unknown building.
        val asset = File("src/main/assets/venue_graph.json").readText()
        assertEquals(GraphStatus.NotConfigured, evaluate(asset))
    }

    @Test
    fun readyInvalidAndMismatchedGraphs() {
        assertTrue(evaluate(twoNodes) is GraphStatus.Ready)
        assertEquals(GraphStatus.FrameMismatch(1, 2), VenueGraphLoader.evaluate(VenueGraphJson.parse(twoNodes), venue.copy(frameVersion = 2)))
        assertTrue(evaluate(twoNodes.replace("default-venue", "other")) is GraphStatus.Invalid)
        assertTrue(evaluate("{broken") is GraphStatus.Invalid)
        assertTrue(evaluate(twoNodes.replace("\"to\":\"b\"", "\"to\":\"zz\"")) is GraphStatus.Invalid)
    }

    @Test
    fun statusTexts() {
        assertEquals(StatusLevel.GOOD, UiText.graphStatus(evaluate(twoNodes)).level)
        assertEquals(StatusLevel.WARN, UiText.graphStatus(GraphStatus.NotConfigured).level)
        RouteFailure.entries.forEach { assertTrue(UiText.routeFailure(it).isNotBlank()) }
        assertTrue(UiText.routeFailure(RouteFailure.START_OFF_GRAPH).contains("corridor"))
    }
}
