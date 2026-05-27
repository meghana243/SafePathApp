package com.example.safepathapp

import org.osmdroid.util.GeoPoint
import java.time.LocalDateTime

/**
 * Represents a safety incident or hazard along a route
 */
data class Incident(
    val id: String,
    val location: GeoPoint,
    val type: IncidentType,
    val severity: IncidentSeverity,
    val title: String,
    val description: String? = null,
    val reportedAt: LocalDateTime,
    val expiresAt: LocalDateTime? = null,
    val isActive: Boolean = true,
    val source: IncidentSource = IncidentSource.USER_REPORT,
    val affectedRadius: Double = 100.0 // in meters
)

enum class IncidentType {
    ACCIDENT,
    ROAD_CLOSURE,
    CONSTRUCTION,
    HAZARD,
    WEATHER,
    CRIME,
    POOR_LIGHTING,
    DANGEROUS_INTERSECTION,
    POTHOLE,
    FLOODING,
    OTHER
}

enum class IncidentSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

enum class IncidentSource {
    USER_REPORT,
    OFFICIAL_AUTHORITY,
    TRAFFIC_API,
    CRIME_DATA,
    WEATHER_SERVICE,
    ML_PREDICTION
}