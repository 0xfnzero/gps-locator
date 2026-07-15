package com.gpsupdater.app.geocoding

import com.gpsupdater.app.BuildConfig
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

object MapServiceConfiguration {
    fun tileSource(): OnlineTileSourceBase = object : OnlineTileSourceBase(
        "GPS Locator",
        3,
        20,
        256,
        ".png",
        arrayOf(BuildConfig.TILE_URL_TEMPLATE),
        BuildConfig.MAP_ATTRIBUTION,
    ) {
        override fun getTileURLString(mapTileIndex: Long): String = BuildConfig.TILE_URL_TEMPLATE
            .replace("{z}", MapTileIndex.getZoom(mapTileIndex).toString())
            .replace("{x}", MapTileIndex.getX(mapTileIndex).toString())
            .replace("{y}", MapTileIndex.getY(mapTileIndex).toString())
    }
}
