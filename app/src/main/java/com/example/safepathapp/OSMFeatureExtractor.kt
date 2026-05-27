package com.example.safepathapp

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.osmdroid.util.GeoPoint
import java.time.LocalTime
import kotlin.math.sqrt

/**
 * Extracts 15 risk features from OpenStreetMap data for a given location
 * Features must match the training data structure for the TFLite model
 */
class OSMFeatureExtractor(private val context: Context) {

    companion object {
        private const val TAG = "OSMFeatureExtractor"
        private const val FEATURE_SIZE = 15

        // Search radius for feature extraction (in meters)
        private const val SEARCH_RADIUS = 200.0
    }

    /**
     * Extract 15 features from a GeoPoint location
     * Features:
     * 0-2: Road type features (primary, residential, footway density)
     * 3-4: POI features (amenity density, shop density)
     * 5-6: Lighting features (streetlight count, building density)
     * 7-8: Safety infrastructure (police, hospital proximity)
     * 9-10: Time-based features (hour of day, day of week)
     * 11-12: Crowd/activity features (estimated from POIs)
     * 13-14: Environmental features (isolation score, connectivity)
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun extractFeatures(location: GeoPoint): FloatArray {
        val features = FloatArray(FEATURE_SIZE)

        try {
            // Feature 0-2: Road type features
            val roadFeatures = extractRoadFeatures(location)
            features[0] = roadFeatures[0] // Primary road density
            features[1] = roadFeatures[1] // Residential road density
            features[2] = roadFeatures[2] // Footway/pedestrian density

            // Feature 3-4: POI features
            val poiFeatures = extractPOIFeatures(location)
            features[3] = poiFeatures[0] // Amenity density (restaurants, shops, etc.)
            features[4] = poiFeatures[1] // Commercial activity

            // Feature 5-6: Lighting features
            val lightingFeatures = extractLightingFeatures(location)
            features[5] = lightingFeatures[0] // Streetlight density
            features[6] = lightingFeatures[1] // Building density (proxy for lighting)

            // Feature 7-8: Safety infrastructure
            val safetyFeatures = extractSafetyFeatures(location)
            features[7] = safetyFeatures[0] // Police station proximity
            features[8] = safetyFeatures[1] // Hospital/emergency proximity

            // Feature 9-10: Time-based features
            val timeFeatures = extractTimeFeatures()
            features[9] = timeFeatures[0] // Hour of day (normalized)
            features[10] = timeFeatures[1] // Day of week (normalized)

            // Feature 11-12: Activity/crowd features
            val activityFeatures = extractActivityFeatures(location)
            features[11] = activityFeatures[0] // Estimated crowd level
            features[12] = activityFeatures[1] // Activity score

            // Feature 13-14: Environmental features
            val envFeatures = extractEnvironmentalFeatures(location)
            features[13] = envFeatures[0] // Isolation score
            features[14] = envFeatures[1] // Street connectivity

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting features", e)
            // Return default values (0.5 = neutral) on error
            for (i in features.indices) {
                features[i] = 0.5f
            }
        }

        return features
    }

    // TODO: Implement with actual OSM data queries
    private fun extractRoadFeatures(location: GeoPoint): FloatArray {

        val lat = location.latitude
        val lon = location.longitude

        // Simple heuristic based on urban likelihood (you can tweak thresholds)
        val isUrban = (lat % 1 > 0.2 && lat % 1 < 0.8) && (lon % 1 > 0.2 && lon % 1 < 0.8)

        val primaryDensity: Float
        val residentialDensity: Float
        val pedestrianDensity: Float

        if (isUrban) {
            // Urban area → more major + pedestrian roads
            primaryDensity = 0.7f
            residentialDensity = 0.6f
            pedestrianDensity = 0.6f
        } else {
            // Non-urban → fewer main roads, more sparse
            primaryDensity = 0.3f
            residentialDensity = 0.5f
            pedestrianDensity = 0.2f
        }

        return floatArrayOf(
            primaryDensity,
            residentialDensity,
            pedestrianDensity
        )
    }

    private fun extractPOIFeatures(location: GeoPoint): FloatArray {
        // Placeholder: Query OSM for amenities, shops within radius
        return floatArrayOf(
            0.5f, // Amenity density
            0.5f  // Commercial activity
        )
    }

    private fun extractLightingFeatures(location: GeoPoint): FloatArray {
        // Placeholder: Query OSM for streetlights and buildings
        return floatArrayOf(
            0.5f, // Streetlight count (normalized)
            0.6f  // Building density
        )
    }

    private fun extractSafetyFeatures(location: GeoPoint): FloatArray {
        // Placeholder: Find nearest police station, hospital
        return floatArrayOf(
            0.7f, // Police proximity (higher = closer)
            0.6f  // Emergency services proximity
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun extractTimeFeatures(): FloatArray {
        // Real-time based features
        val now = LocalTime.now()
        val hour = now.hour / 24f  // Normalize to 0-1
        val dayOfWeek = java.time.LocalDate.now().dayOfWeek.value / 7f

        return floatArrayOf(hour, dayOfWeek)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun extractActivityFeatures(location: GeoPoint): FloatArray {
        // Placeholder: Estimate crowd from POI count and time
        val time = LocalTime.now()
        val isBusinessHours = time.hour in 9..17

        return floatArrayOf(
            if (isBusinessHours) 0.7f else 0.3f, // Crowd estimate
            0.5f  // Activity score
        )
    }

    private fun extractEnvironmentalFeatures(location: GeoPoint): FloatArray {
        // Placeholder: Calculate isolation and connectivity scores
        return floatArrayOf(
            0.4f, // Isolation (lower = less isolated)
            0.7f  // Connectivity (higher = better connected)
        )
    }

    /**
     * Calculate distance between two GeoPoints in meters
     */
    private fun calculateDistance(p1: GeoPoint, p2: GeoPoint): Double {
        val lat1 = Math.toRadians(p1.latitude)
        val lat2 = Math.toRadians(p2.latitude)
        val lon1 = Math.toRadians(p1.longitude)
        val lon2 = Math.toRadians(p2.longitude)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)

        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return 6371000.0 * c // Earth radius in meters
    }
}