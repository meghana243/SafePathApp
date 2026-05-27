package com.example.safepathapp

import android.graphics.Color
import android.util.Log
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ── Data model ───────────────────────────────────────────────────────────────

enum class SafeZoneType {
    POLICE,
    HOSPITAL,
    PHARMACY,
    FIRE_STATION
}

data class SafeZone(
    val name: String,
    val type: SafeZoneType,
    val location: GeoPoint
)

// ── Extension functions on SafeZoneType ──────────────────────────────────────
// Defined here so both SafeZoneFetcher and MainActivity can use them

fun SafeZoneType.label(): String = when (this) {
    SafeZoneType.POLICE       -> "🚔 Police Station"
    SafeZoneType.HOSPITAL     -> "🏥 Hospital / Clinic"
    SafeZoneType.PHARMACY     -> "💊 Pharmacy"
    SafeZoneType.FIRE_STATION -> "🚒 Fire Station"
}

fun SafeZoneType.color(): Int = when (this) {
    SafeZoneType.POLICE       -> Color.parseColor("#1565C0") // blue
    SafeZoneType.HOSPITAL     -> Color.parseColor("#C62828") // red
    SafeZoneType.PHARMACY     -> Color.parseColor("#2E7D32") // green
    SafeZoneType.FIRE_STATION -> Color.parseColor("#E65100") // orange
}

// ── Fetcher ──────────────────────────────────────────────────────────────────

object SafeZoneFetcher {

    private const val TAG           = "SafeZoneFetcher"
    private const val OVERPASS_URL  = "https://overpass-api.de/api/interpreter"

    /**
     * Fetches safe zones within the bounding box of the route.
     * Must be called from a background thread.
     */
    fun fetchAlongRoute(routePoints: List<GeoPoint>): List<SafeZone> {
        if (routePoints.isEmpty()) return emptyList()

        val lats      = routePoints.map { it.latitude }
        val lons      = routePoints.map { it.longitude }
        val bufferDeg = 0.01  // ~1 km buffer

        val south = lats.min() - bufferDeg
        val west  = lons.min() - bufferDeg
        val north = lats.max() + bufferDeg
        val east  = lons.max() + bufferDeg
        val bbox  = "$south,$west,$north,$east"

        val query = """
            [out:json][timeout:25];
            (
              node["amenity"="police"]($bbox);
              node["amenity"="hospital"]($bbox);
              node["amenity"="clinic"]($bbox);
              node["amenity"="pharmacy"]($bbox);
              node["amenity"="fire_station"]($bbox);
            );
            out body;
        """.trimIndent()

        return try {
            val encoded  = URLEncoder.encode(query, "UTF-8")
            val url      = URL("$OVERPASS_URL?data=$encoded")
            val conn     = url.openConnection() as HttpURLConnection
            conn.requestMethod  = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout    = 15000
            conn.setRequestProperty("User-Agent", "SafePathApp/1.0")

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Overpass error: ${conn.responseCode}")
                return emptyList()
            }

            val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            parseOverpassResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch safe zones", e)
            emptyList()
        }
    }

    private fun parseOverpassResponse(json: String): List<SafeZone> {
        val zones = mutableListOf<SafeZone>()
        try {
            val elements = JSONObject(json).getJSONArray("elements")
            for (i in 0 until elements.length()) {
                val el  = elements.getJSONObject(i)
                val lat = el.optDouble("lat", Double.NaN)
                val lon = el.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue

                val tags    = el.optJSONObject("tags") ?: continue
                val amenity = tags.optString("amenity", "")
                val name    = tags.optString("name", friendlyName(amenity))

                val type = when (amenity) {
                    "police"            -> SafeZoneType.POLICE
                    "hospital", "clinic"-> SafeZoneType.HOSPITAL
                    "pharmacy"          -> SafeZoneType.PHARMACY
                    "fire_station"      -> SafeZoneType.FIRE_STATION
                    else                -> continue
                }

                zones.add(SafeZone(name, type, GeoPoint(lat, lon)))
            }
            Log.d(TAG, "Parsed ${zones.size} safe zones")
        } catch (e: Exception) {
            Log.e(TAG, "Parse error", e)
        }
        return zones
    }

    private fun friendlyName(amenity: String): String = when (amenity) {
        "police"       -> "Police Station"
        "hospital"     -> "Hospital"
        "clinic"       -> "Clinic"
        "pharmacy"     -> "Pharmacy"
        "fire_station" -> "Fire Station"
        else           -> "Safe Zone"
    }
}