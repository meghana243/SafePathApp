package com.example.safepathapp

import android.util.Log
import org.osmdroid.util.GeoPoint
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Client for querying OpenStreetMap data via Overpass API
 */
class OverpassClient {

    companion object {
        private const val TAG = "OverpassClient"
        private const val OVERPASS_API = "https://overpass-api.de/api/interpreter"
        private const val DEFAULT_RADIUS = 500 // meters
        private const val TIMEOUT = 10000 // 10 seconds
    }

    /**
     * Query the nearest distance to a specific amenity type
     * @param point The center point to search from
     * @param amenityType The type of amenity (e.g., "police", "hospital", "park")
     * @return Normalized distance (0 = very close, 1 = far away)
     */
    fun queryNearestDistance(point: GeoPoint, amenityType: String): Float {
        try {
            val query = buildNearestQuery(point, amenityType)
            val response = executeQuery(query)

            if (response != null) {
                val json = JSONObject(response)
                val elements = json.getJSONArray("elements")

                if (elements.length() > 0) {
                    var minDistance = Double.MAX_VALUE

                    for (i in 0 until elements.length()) {
                        val element = elements.getJSONObject(i)
                        val lat = element.getDouble("lat")
                        val lon = element.getDouble("lon")

                        val distance = calculateDistance(
                            point.latitude, point.longitude,
                            lat, lon
                        )

                        if (distance < minDistance) {
                            minDistance = distance
                        }
                    }

                    // Normalize: 0-500m -> 0.0-1.0 (closer = lower value = safer)
                    return (minDistance / DEFAULT_RADIUS).toFloat().coerceIn(0f, 1f)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying nearest $amenityType", e)
        }

        // Default: assume moderately far
        return 0.7f
    }

    /**
     * Count amenities of a specific type within radius
     * @param point The center point to search from
     * @param amenityType The type of amenity (e.g., "bus_stop", "shop")
     * @return Count of amenities found
     */
    fun queryAmenityCount(point: GeoPoint, amenityType: String): Float {
        try {
            val query = buildCountQuery(point, amenityType)
            val response = executeQuery(query)

            if (response != null) {
                val json = JSONObject(response)
                val elements = json.getJSONArray("elements")
                return elements.length().toFloat()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error counting $amenityType", e)
        }

        return 0f
    }

    /**
     * Build Overpass QL query to find nearest amenity
     */
    private fun buildNearestQuery(point: GeoPoint, amenityType: String): String {
        val lat = point.latitude
        val lon = point.longitude
        val radius = DEFAULT_RADIUS

        return """
            [out:json][timeout:25];
            (
              node["amenity"="$amenityType"](around:$radius,$lat,$lon);
              way["amenity"="$amenityType"](around:$radius,$lat,$lon);
            );
            out center;
        """.trimIndent()
    }

    /**
     * Build Overpass QL query to count amenities
     */
    private fun buildCountQuery(point: GeoPoint, amenityType: String): String {
        val lat = point.latitude
        val lon = point.longitude
        val radius = DEFAULT_RADIUS

        return """
            [out:json][timeout:25];
            (
              node["amenity"="$amenityType"](around:$radius,$lat,$lon);
              way["amenity"="$amenityType"](around:$radius,$lat,$lon);
            );
            out;
        """.trimIndent()
    }

    /**
     * Execute Overpass API query
     */
    private fun executeQuery(query: String): String? {
        var connection: HttpURLConnection? = null
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlString = "$OVERPASS_API?data=$encodedQuery"

            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                return response.toString()
            } else {
                Log.w(TAG, "HTTP error: $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error executing query", e)
        } finally {
            connection?.disconnect()
        }

        return null
    }

    /**
     * Calculate distance between two points using Haversine formula
     * @return Distance in meters
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // meters

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }
}