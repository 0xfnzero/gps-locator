package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

enum class LocationDocumentFormat {
    GPX,
    KML,
}

object LocationDocumentCodec {
    fun export(route: SimulationRoute, format: LocationDocumentFormat): String = when (format) {
        LocationDocumentFormat.GPX -> exportGpx(route)
        LocationDocumentFormat.KML -> exportKml(route)
    }

    fun import(value: String, fallbackName: String = "Imported route"): SimulationRoute {
        require(!value.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE is not allowed" }
        require(!value.contains("<!ENTITY", ignoreCase = true)) { "Entities are not allowed" }
        val document = secureDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(value.toByteArray(Charsets.UTF_8)))
        val root = document.documentElement
        return when (root.localName?.lowercase() ?: root.tagName.lowercase()) {
            "gpx" -> importGpx(root, fallbackName)
            "kml" -> importKml(root, fallbackName)
            else -> error("Unsupported location document")
        }
    }

    private fun exportGpx(route: SimulationRoute): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"GPS Locator\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:gpsupdater=\"https://gpsupdater.app/schema/1\">\n")
        append("  <trk><name>${escape(route.name)}</name><extensions>")
        append("<gpsupdater:speedKph>${route.speedKph}</gpsupdater:speedKph>")
        append("<gpsupdater:loop>${route.loop}</gpsupdater:loop></extensions><trkseg>\n")
        route.waypoints.forEach { waypoint ->
            append("    <trkpt lat=\"${waypoint.coordinates.latitude}\" lon=\"${waypoint.coordinates.longitude}\">")
            append("<name>${escape(waypoint.name)}</name>")
            append("<extensions><gpsupdater:stopSeconds>${waypoint.stopDurationSeconds}</gpsupdater:stopSeconds></extensions>")
            append("</trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    private fun exportKml(route: SimulationRoute): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>")
        append("<name>${escape(route.name)}</name>")
        append("<ExtendedData><Data name=\"speedKph\"><value>${route.speedKph}</value></Data>")
        append("<Data name=\"loop\"><value>${route.loop}</value></Data></ExtendedData>\n")
        route.waypoints.forEachIndexed { index, waypoint ->
            append("<Placemark><name>${escape(waypoint.name)}</name>")
            append("<ExtendedData><Data name=\"order\"><value>$index</value></Data>")
            append("<Data name=\"stopSeconds\"><value>${waypoint.stopDurationSeconds}</value></Data></ExtendedData>")
            append("<Point><coordinates>${waypoint.coordinates.longitude},${waypoint.coordinates.latitude},0</coordinates></Point></Placemark>\n")
        }
        append("<Placemark><name>${escape(route.name)}</name><LineString><coordinates>")
        append(route.waypoints.joinToString(" ") { "${it.coordinates.longitude},${it.coordinates.latitude},0" })
        append("</coordinates></LineString></Placemark>")
        append("</Document></kml>\n")
    }

    private fun importGpx(root: Element, fallbackName: String): SimulationRoute {
        val track = root.elements("trk").firstOrNull()
        val routeElement = root.elements("rte").firstOrNull()
        val container = track ?: routeElement ?: root
        val points = when {
            track != null -> track.descendantElements("trkpt")
            routeElement != null -> routeElement.descendantElements("rtept")
            else -> root.descendantElements("wpt")
        }
        val waypoints = points.mapIndexed { index, point ->
            RouteWaypoint(
                name = point.childText("name").ifBlank { "Point ${index + 1}" },
                coordinates = Coordinates(
                    point.getAttribute("lat").toDouble(),
                    point.getAttribute("lon").toDouble(),
                ),
                stopDurationSeconds = point.descendantText("stopSeconds").toLongOrNull() ?: 0,
            )
        }
        val speed = container.descendantText("speedKph").toDoubleOrNull() ?: DEFAULT_SPEED_KPH
        val loop = container.descendantText("loop").toBooleanStrictOrNull() ?: false
        return SimulationRoute(
            name = container.childText("name").ifBlank { fallbackName },
            waypoints = waypoints,
            speedKph = speed.coerceIn(SimulationRoute.MIN_SPEED_KPH, SimulationRoute.MAX_SPEED_KPH),
            loop = loop,
        )
    }

    private fun importKml(root: Element, fallbackName: String): SimulationRoute {
        val document = root.descendantElements("Document").firstOrNull() ?: root
        val placemarks = document.descendantElements("Placemark")
        val pointPlacemarks = placemarks.filter { it.descendantElements("Point").isNotEmpty() }
        val waypoints = if (pointPlacemarks.isNotEmpty()) {
            pointPlacemarks
                .sortedBy { it.dataValue("order")?.toIntOrNull() ?: Int.MAX_VALUE }
                .mapIndexed { index, placemark ->
                    val coordinate = parseKmlCoordinate(
                        placemark.descendantElements("coordinates").first().textContent,
                    )
                    RouteWaypoint(
                        name = placemark.childText("name").ifBlank { "Point ${index + 1}" },
                        coordinates = coordinate,
                        stopDurationSeconds = placemark.dataValue("stopSeconds")?.toLongOrNull() ?: 0,
                    )
                }
        } else {
            val coordinates = document.descendantElements("LineString")
                .firstOrNull()
                ?.descendantElements("coordinates")
                ?.firstOrNull()
                ?.textContent
                ?.trim()
                ?.split(Regex("\\s+"))
                .orEmpty()
            coordinates.mapIndexed { index, coordinate ->
                RouteWaypoint(
                    name = "Point ${index + 1}",
                    coordinates = parseKmlCoordinate(coordinate),
                )
            }
        }
        val speed = document.dataValue("speedKph")?.toDoubleOrNull() ?: DEFAULT_SPEED_KPH
        val loop = document.dataValue("loop")?.toBooleanStrictOrNull() ?: false
        return SimulationRoute(
            name = document.childText("name").ifBlank { fallbackName },
            waypoints = waypoints,
            speedKph = speed.coerceIn(SimulationRoute.MIN_SPEED_KPH, SimulationRoute.MAX_SPEED_KPH),
            loop = loop,
        )
    }

    private fun parseKmlCoordinate(value: String): Coordinates {
        val parts = value.trim().split(',')
        require(parts.size >= 2)
        return Coordinates(parts[1].toDouble(), parts[0].toDouble())
    }

    private fun Element.dataValue(name: String): String? = descendantElements("Data")
        .firstOrNull { it.getAttribute("name") == name }
        ?.descendantElements("value")
        ?.firstOrNull()
        ?.textContent
        ?.trim()

    private fun Element.childText(name: String): String = elements(name)
        .firstOrNull()
        ?.textContent
        ?.trim()
        .orEmpty()

    private fun Element.descendantText(name: String): String = descendantElements(name)
        .firstOrNull()
        ?.textContent
        ?.trim()
        .orEmpty()

    private fun Element.elements(name: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = childNodes
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            if (element.localName == name || element.tagName.substringAfter(':') == name) result += element
        }
        return result
    }

    private fun Element.descendantElements(name: String): List<Element> {
        val result = mutableListOf<Element>()
        val all = getElementsByTagNameNS("*", name)
        for (index in 0 until all.length) (all.item(index) as? Element)?.let(result::add)
        if (result.isNotEmpty()) return result
        val withoutNamespace = getElementsByTagName(name)
        for (index in 0 until withoutNamespace.length) {
            (withoutNamespace.item(index) as? Element)?.let(result::add)
        }
        return result
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private const val DEFAULT_SPEED_KPH = 30.0
}
