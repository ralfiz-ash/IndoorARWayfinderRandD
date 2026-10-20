package com.indoorar.wayfinder.navigation

import com.indoorar.wayfinder.navigation.calibration.ReferencePointsJson
import com.indoorar.wayfinder.navigation.calibration.ReferencePointsResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePointsJsonTest {
    @Test
    fun parsesAndValidates() {
        val ok = ReferencePointsJson.parse("""{"venueId":"v","frameVersion":2,"points":[{"name":"T1","x":0,"z":5},{"name":"T2","x":-1.5,"z":10,"y":-1.3}]}""")
        ok as ReferencePointsResult.Loaded
        assertEquals(2, ok.points.size)
        assertEquals(-1.3, ok.points[1].positionInVenue.y, 0.0)
        assertTrue(ReferencePointsJson.parse("""{"venueId":"v","frameVersion":1,"points":[{"name":"T","x":0,"z":1},{"name":"T","x":1,"z":1}]}""") is ReferencePointsResult.Invalid)
        assertTrue(ReferencePointsJson.parse("nope") is ReferencePointsResult.Invalid)
    }
}
