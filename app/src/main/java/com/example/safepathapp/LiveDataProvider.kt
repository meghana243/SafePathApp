package com.example.safepathapp

import org.osmdroid.util.GeoPoint
import java.time.LocalTime

interface LiveDataProvider {

    suspend fun getRoadNetwork(
        start: GeoPoint,
        end: GeoPoint
    ): RoadNetwork

    suspend fun getRiskFeatures(
        from: GeoPoint,
        to: GeoPoint,
        timeOfDay: LocalTime
    ): FloatArray

    suspend fun checkForIncidents(
        path: List<GeoPoint>
    ): List<Incident>
}
