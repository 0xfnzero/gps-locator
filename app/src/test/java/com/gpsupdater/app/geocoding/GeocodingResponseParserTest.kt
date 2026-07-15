package com.gpsupdater.app.geocoding

import org.junit.Assert.assertEquals
import org.junit.Test

class GeocodingResponseParserTest {
    @Test
    fun parse_keepsLocalizedNamesAndAssignsUsefulZoomLevels() {
        val results = GeocodingResponseParser.parse(
            """
            [
              {
                "lat": "31.230416",
                "lon": "121.473701",
                "name": "上海市",
                "display_name": "上海市, 中国",
                "addresstype": "city"
              },
              {
                "lat": "31.230416",
                "lon": "121.473701",
                "name": "duplicate",
                "display_name": "duplicate coordinate",
                "addresstype": "district"
              },
              {
                "lat": "35.6762",
                "lon": "139.6503",
                "name": "",
                "display_name": "東京都, 日本",
                "type": "region"
              }
            ]
            """.trimIndent(),
        )

        assertEquals(2, results.size)
        assertEquals("上海市", results[0].name)
        assertEquals("上海市, 中国", results[0].displayName)
        assertEquals(12.0, results[0].zoomLevel, 0.0)
        assertEquals("東京都", results[1].name)
        assertEquals(7.0, results[1].zoomLevel, 0.0)
    }

    @Test
    fun parse_skipsInvalidCoordinatesAndBlankDisplayNames() {
        val results = GeocodingResponseParser.parse(
            """
            [
              {"lat": "invalid", "lon": "2", "display_name": "Bad latitude"},
              {"lat": "91", "lon": "2", "display_name": "Out of range"},
              {"lat": "1", "lon": "2", "display_name": ""},
              {
                "lat": "52.520008",
                "lon": "13.404954",
                "name": "Berlin",
                "display_name": "Berlin, Deutschland",
                "addresstype": "suburb"
              }
            ]
            """.trimIndent(),
        )

        assertEquals(1, results.size)
        assertEquals("Berlin", results.single().name)
        assertEquals(14.0, results.single().zoomLevel, 0.0)
    }
}
