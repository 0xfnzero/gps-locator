package com.gpsupdater.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinatesTest {
    @Test
    fun parse_acceptsBoundaryCoordinates() {
        assertEquals(Coordinates(-90.0, 180.0), Coordinates.parse("-90", "180"))
        assertEquals(Coordinates(90.0, -180.0), Coordinates.parse("90", "-180"))
    }

    @Test
    fun parse_rejectsValuesOutsideGpsRanges() {
        assertNull(Coordinates.parse("90.0001", "120"))
        assertNull(Coordinates.parse("30", "-180.001"))
    }

    @Test
    fun parse_rejectsNonFiniteAndMalformedValues() {
        assertNull(Coordinates.parse("NaN", "120"))
        assertNull(Coordinates.parse("30", "Infinity"))
        assertNull(Coordinates.parse("north", "east"))
    }

    @Test
    fun formattedCoordinates_useSixDecimalPlaces() {
        val coordinates = Coordinates(31.2304162, 121.4737011)

        assertEquals("31.230416", coordinates.formattedLatitude())
        assertEquals("121.473701", coordinates.formattedLongitude())
    }
}
