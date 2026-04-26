package com.example.justrun

import org.w3c.dom.Element
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal object GpxCodec {
    fun importRun(inputStream: InputStream, sourceName: String? = null): RunRecord {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }
        val document = documentBuilderFactory.newDocumentBuilder().parse(inputStream)
        val root = document.documentElement
        val metadataName = root.childElements("metadata")
            .firstOrNull()
            ?.childElements("name")
            ?.firstOrNull()
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val trackName = root.childElements("trk")
            .firstOrNull()
            ?.childElements("name")
            ?.firstOrNull()
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val title = metadataName ?: trackName ?: sourceName?.substringBeforeLast(".") ?: "Imported run"

        val rawTrackPoints = root.childElements("trk")
            .flatMap { it.childElements("trkseg") }
            .flatMap { it.childElements("trkpt") }
            .mapIndexedNotNull { index, element ->
                val latitude = element.getAttribute("lat").toDoubleOrNull() ?: return@mapIndexedNotNull null
                val longitude = element.getAttribute("lon").toDoubleOrNull() ?: return@mapIndexedNotNull null
                val elevation = element.childText("ele")?.toDoubleOrNull() ?: 0.0
                val timestamp = parseGpxTimestamp(element.childText("time"))
                ParsedTrackPoint(
                    index = index,
                    latitude = latitude,
                    longitude = longitude,
                    altitudeMeters = elevation,
                    timestampMillis = timestamp
                )
            }

        require(rawTrackPoints.isNotEmpty()) { "No GPX track points found." }

        val hasReliableTime = rawTrackPoints.count { it.timestampMillis != null } >= 2
        val fallbackStart = System.currentTimeMillis()
        val startedAtMillis = rawTrackPoints.firstNotNullOfOrNull { it.timestampMillis } ?: fallbackStart
        val routePoints = rawTrackPoints.map { point ->
            LocationPoint(
                timestampMillis = point.timestampMillis ?: (startedAtMillis + point.index * 1000L),
                latitude = point.latitude,
                longitude = point.longitude,
                altitudeMeters = point.altitudeMeters,
                accuracyMeters = 0f,
                speedMetersPerSecond = 0f
            )
        }

        val distanceKm = routePoints.windowed(2).sumOf { (start, end) ->
            gpxDistanceBetweenKm(start, end).toDouble()
        }.toFloat()
        val durationSeconds = if (hasReliableTime) {
            (((routePoints.last().timestampMillis - routePoints.first().timestampMillis) / 1000L).coerceAtLeast(0L)).toInt()
        } else {
            0
        }
        val avgPaceMinPerKm = if (distanceKm > 0f && durationSeconds > 0) durationSeconds / 60f / distanceKm else null
        val elevationGainM = routePoints.windowed(2).sumOf { (start, end) ->
            maxOf(0.0, end.altitudeMeters - start.altitudeMeters)
        }.toInt()
        val paceSeries = if (hasReliableTime) {
            routePoints.windowed(2).mapNotNull { (start, end) ->
                val segmentDistanceKm = gpxDistanceBetweenKm(start, end)
                val segmentDurationSeconds = ((end.timestampMillis - start.timestampMillis) / 1000f).coerceAtLeast(1f)
                if (segmentDistanceKm <= 0f) null else (segmentDurationSeconds / 60f) / segmentDistanceKm
            }
        } else {
            emptyList()
        }
        val notes = if (hasReliableTime) {
            "Imported from GPX."
        } else {
            "Imported from GPX without timing metadata."
        }

        return RunRecord(
            id = System.currentTimeMillis(),
            title = title,
            dateLabel = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(startedAtMillis)),
            startedAtMillis = startedAtMillis,
            goal = RunGoal.ENDLESS,
            durationSeconds = durationSeconds,
            distanceKm = distanceKm,
            avgPaceMinPerKm = avgPaceMinPerKm,
            calories = 0,
            elevationGainM = elevationGainM,
            notes = notes,
            paceSeries = paceSeries,
            elevationSeries = routePoints.map { it.altitudeMeters.toFloat() },
            lapSplits = emptyList(),
            routePoints = routePoints,
            hadGpsTracking = true,
            hadHeartRateTracking = false
        )
    }

    fun exportRun(run: RunRecord, outputStream: OutputStream) {
        require(run.routePoints.isNotEmpty()) { "Run has no GPS track to export." }
        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }
        val document = documentBuilderFactory.newDocumentBuilder().newDocument()
        val root = document.createElementNS("http://www.topografix.com/GPX/1/1", "gpx").apply {
            setAttribute("version", "1.1")
            setAttribute("creator", "Just Run")
        }
        document.appendChild(root)

        val metadata = document.createElement("metadata")
        metadata.appendTextElement(document, "name", run.title)
        metadata.appendTextElement(document, "time", formatGpxTimestamp(run.startedAtMillis))
        root.appendChild(metadata)

        val track = document.createElement("trk")
        track.appendTextElement(document, "name", run.title)
        if (run.notes.isNotBlank()) {
            track.appendTextElement(document, "desc", run.notes)
        }
        val segment = document.createElement("trkseg")
        run.routePoints.forEach { point ->
            val trackPoint = document.createElement("trkpt").apply {
                setAttribute("lat", point.latitude.toString())
                setAttribute("lon", point.longitude.toString())
            }
            trackPoint.appendTextElement(document, "ele", point.altitudeMeters.toString())
            if (point.timestampMillis > 0L) {
                trackPoint.appendTextElement(document, "time", formatGpxTimestamp(point.timestampMillis))
            }
            segment.appendChild(trackPoint)
        }
        track.appendChild(segment)
        root.appendChild(track)

        val transformer = TransformerFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }.newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }
        transformer.transform(DOMSource(document), StreamResult(outputStream))
    }

    private fun parseGpxTimestamp(value: String?): Long? {
        val candidate = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Instant.parse(candidate).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(candidate, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private fun formatGpxTimestamp(timestampMillis: Long): String =
        Instant.ofEpochMilli(timestampMillis).toString()
}

private data class ParsedTrackPoint(
    val index: Int,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val timestampMillis: Long?
)

private fun gpxDistanceBetweenKm(start: LocationPoint, end: LocationPoint): Float {
    val earthRadiusKm = 6371.0
    val latDelta = Math.toRadians(end.latitude - start.latitude)
    val lonDelta = Math.toRadians(end.longitude - start.longitude)
    val startLat = Math.toRadians(start.latitude)
    val endLat = Math.toRadians(end.latitude)
    val haversine = sin(latDelta / 2).pow(2.0) +
        cos(startLat) * cos(endLat) * sin(lonDelta / 2).pow(2.0)
    val arc = 2 * asin(sqrt(haversine))
    return (earthRadiusKm * arc).toFloat()
}

private fun Element.childElements(localName: String): List<Element> =
    childNodes.run {
        buildList {
            for (index in 0 until length) {
                val item = item(index)
                if (item is Element && (item.localName == localName || item.tagName == localName)) {
                    add(item)
                }
            }
        }
    }

private fun Element.childText(localName: String): String? =
    childElements(localName).firstOrNull()?.textContent

private fun Element.appendTextElement(document: org.w3c.dom.Document, name: String, value: String) {
    val element = document.createElement(name)
    element.textContent = value
    appendChild(element)
}
