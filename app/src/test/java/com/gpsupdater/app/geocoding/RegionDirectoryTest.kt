package com.gpsupdater.app.geocoding

import org.junit.Assert.*
import org.junit.Test

class RegionDirectoryTest {
    @Test fun filtersNativeNamesAndAccentsWithoutChangingTheDisplayedName() {
        assertTrue(RegionDirectory.matches("München", "munchen"))
        assertTrue(RegionDirectory.matches("安徽省 · Anhui", "安徽"))
        assertTrue(RegionDirectory.matches("California", "  CALI "))
        assertFalse(RegionDirectory.matches("California", "Berlin"))
    }

    @Test fun parsesMissingCoordinatesAndUnlinkedCitiesWithoutInventingPositions() {
        val region = RegionDirectory.parseRegions("""[{"id":1,"name":"Region","native":"",
            "point":null,"districts":[],"cities":[{"id":2,"name":"Town","native":"",
            "point":[34.05223,-118.24368],"district":null}]}]""").single()
        assertNull(region.place.coordinates)
        assertEquals(-118.24368, region.cities.single().coordinates!!.longitude, 0.00001)
        assertEquals(region.cities, region.citiesIn(null))
    }

    @Test fun countySelectionIncludesNestedCountiesButExcludesUnrelatedCities() {
        fun item(id: Int, parent: Int? = null) = RegionPlace(id, "$id", "", null, parent)
        val parent = item(1)
        val nested = item(2, 1)
        val region = RegionSubdivision(item(0), listOf(parent, nested, item(3)),
            listOf(item(10, 1), item(11, 2), item(12, 3), item(13)))
        assertEquals(listOf(10, 11), region.citiesIn(parent).map { it.id })
        assertEquals(listOf(11), region.citiesIn(nested).map { it.id })
        assertEquals(4, region.citiesIn(null).size)
    }
}
