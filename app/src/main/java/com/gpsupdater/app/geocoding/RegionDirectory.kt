package com.gpsupdater.app.geocoding

import android.content.Context
import com.gpsupdater.app.model.Coordinates
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

data class RegionPlace(
    val id: Int,
    val name: String,
    val nativeName: String,
    val coordinates: Coordinates?,
    val parentId: Int? = null,
) {
    val label: String get() = if (nativeName.isBlank() || nativeName == name) name else "$nativeName · $name"
}

data class RegionCountry(val code: String, val name: String, val coordinates: Coordinates?)

data class RegionSubdivision(
    val place: RegionPlace,
    val districts: List<RegionPlace>,
    val cities: List<RegionPlace>,
) {
    fun citiesIn(district: RegionPlace?): List<RegionPlace> {
        if (district == null) return cities
        val ids = mutableSetOf(district.id)
        do {
            val added = districts.filter { it.parentId in ids }.map { it.id }.filter { it !in ids }
            ids.addAll(added)
        } while (added.isNotEmpty())
        return cities.filter { it.parentId in ids }
    }
}

class RegionDirectory(context: Context) {
    private val assets = context.applicationContext.assets

    fun countries(): List<RegionCountry> {
        val rows = JSONArray(assets.open("regions/countries.json").bufferedReader().use { it.readText() })
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            RegionCountry(row.getString("code"), row.getString("name"), point(row))
        }
    }

    fun regions(countryCode: String): List<RegionSubdivision> {
        require(countryCode.matches(Regex("[A-Z]{2}")))
        // AAPT expands .gz assets and removes the suffix when packaging the APK.
        val json = assets.open("regions/$countryCode.json")
            .bufferedReader().use { it.readText() }
        return parseRegions(json)
    }

    companion object {
        fun parseRegions(json: String): List<RegionSubdivision> {
            val rows = JSONArray(json)
            fun places(rows: JSONArray, parentKey: String): List<RegionPlace> =
                (0 until rows.length()).map { index -> place(rows.getJSONObject(index), parentKey) }
            return (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                RegionSubdivision(place(row, "parent"), places(row.getJSONArray("districts"), "parent"),
                    places(row.getJSONArray("cities"), "district"))
            }
        }

        fun matches(label: String, query: String): Boolean {
            fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
            return normalize(label).contains(normalize(query.trim()))
        }

        private fun place(row: JSONObject, parentKey: String) = RegionPlace(
            row.getInt("id"), row.getString("name"), row.optString("native"), point(row),
            if (row.isNull(parentKey)) null else row.getInt(parentKey),
        )

        private fun point(row: JSONObject): Coordinates? = row.optJSONArray("point")?.let {
            Coordinates(it.getDouble(0), it.getDouble(1))
        }
    }
}
