package com.example.safepathapp

import android.os.Build
import androidx.annotation.RequiresApi
import org.osmdroid.util.GeoPoint
import java.time.LocalTime
import kotlin.math.sqrt

/* =========================
   CORE DATA MODELS
   ========================= */

data class Edge(
    val from: GeoPoint,
    val to: GeoPoint,
    val distance: Double
)



interface CrimeDataSource {
    suspend fun getCrimeDensity(point: GeoPoint): Double
}

interface CrowdDataSource {
    suspend fun getCrowdLevel(point: GeoPoint, time: LocalTime): Double
}

interface LightingDataSource {
    suspend fun getLightingScore(point: GeoPoint, time: LocalTime): Double
}

interface EnvironmentDataSource {
    suspend fun getIsolationScore(point: GeoPoint): Double
}

interface IncidentDataSource {
    suspend fun getNearestIncidentDistance(point: GeoPoint): Double
}

interface SafetyInfraDataSource {
    suspend fun getPoliceProximityScore(point: GeoPoint): Double
}

/* =========================
   FEATURE EXTRACTION (20 FEATURES)
   ========================= */

class RiskFeatureExtractor(
    private val crime: CrimeDataSource,
    private val crowd: CrowdDataSource,
    private val lighting: LightingDataSource,
    private val environment: EnvironmentDataSource,
    private val incidents: IncidentDataSource,
    private val safety: SafetyInfraDataSource
) {

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun extract(
        from: GeoPoint,
        to: GeoPoint,
        time: LocalTime
    ): FloatArray {

        val features = FloatArray(20)

        // 0–4 : Historical crime rate
        val crimeScore = crime.getCrimeDensity(from)
        for (i in 0..4) features[i] = crimeScore.toFloat()

        // 5–7 : Crowd density / activity
        val crowdScore = crowd.getCrowdLevel(from, time)
        for (i in 5..7) features[i] = crowdScore.toFloat()

        // 8–10 : Lighting & visibility
        val lightingScore = lighting.getLightingScore(from, time)
        for (i in 8..10) features[i] = lightingScore.toFloat()

        // 11–13 : Time-based risk
        val timeRisk = time.hour / 24f
        for (i in 11..13) features[i] = timeRisk

        // 14–16 : Environmental isolation
        val isolationScore = environment.getIsolationScore(from)
        for (i in 14..16) features[i] = isolationScore.toFloat()

        // 17–18 : Incident proximity
        val incidentDistance = incidents.getNearestIncidentDistance(from)
        features[17] = incidentDistance.toFloat()
        features[18] = incidentDistance.toFloat()

        // 19 : Police / safe-zone presence
        features[19] = safety.getPoliceProximityScore(from).toFloat()

        return features
    }
}

/* =========================
   RISK PREDICTION MODEL
   ========================= */

interface RiskPredictor {
    fun predict(features: FloatArray): Double
}

class GenericRiskPredictor : RiskPredictor {
    override fun predict(features: FloatArray): Double {
        val sum = features.sum().toDouble()
        return sum / features.size.toDouble()
    }
}

/* =========================
   LIVE DATA PROVIDER IMPLEMENTATION
   ========================= */

class DefaultLiveDataProvider(
    private val featureExtractor: RiskFeatureExtractor,
    private val predictor: RiskPredictor
) : LiveDataProvider {

    override suspend fun getRoadNetwork(
        start: GeoPoint,
        end: GeoPoint
    ): RoadNetwork {
        throw NotImplementedError("Road network source must be injected")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getRiskFeatures(
        from: GeoPoint,
        to: GeoPoint,
        timeOfDay: LocalTime
    ): FloatArray {
        return featureExtractor.extract(from, to, timeOfDay)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun checkForIncidents(
        path: List<GeoPoint>
    ): List<Incident> {
        // Default implementation - returns empty list
        // Override this with actual incident checking logic
        return emptyList()
    }
}

/* =========================
   DRRA* PATHFINDING ALGORITHM
   ========================= */

class AStarPathfinder(
    private val riskPredictor: com.example.safepathapp.RiskPredictor,
    private val dataProvider: LiveDataProvider,
    private val riskWeight: Double = 0.6
) {

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun findPath(
        start: GeoPoint,
        goal: GeoPoint
    ): List<GeoPoint> {

        val network = dataProvider.getRoadNetwork(start, goal)

        // Build an adjacency map from the edge list for easier lookup
        val adjacencyMap = mutableMapOf<String, MutableList<RoadEdge>>()
        for (edge in network.edges) {
            adjacencyMap.getOrPut(edge.fromNodeId) { mutableListOf() }.add(edge)
        }

        // Create a map of node IDs to nodes for quick lookup
        val nodeMap = network.nodes.associateBy { it.id }

        val openSet = mutableSetOf(start)
        val cameFrom = mutableMapOf<GeoPoint, GeoPoint>()

        val gScore = mutableMapOf<GeoPoint, Double>().withDefault { Double.MAX_VALUE }
        val fScore = mutableMapOf<GeoPoint, Double>().withDefault { Double.MAX_VALUE }

        gScore[start] = 0.0
        fScore[start] = heuristic(start, goal)

        while (openSet.isNotEmpty()) {
            val current = openSet.minByOrNull { fScore.getValue(it) } ?: break

            if (current == goal) {
                return reconstructPath(cameFrom, current)
            }

            openSet.remove(current)

            // Find the current node
            val currentNode = network.nodes.find { it.location == current }
            if (currentNode != null) {
                // Get all edges from this node
                val edges = adjacencyMap[currentNode.id] ?: emptyList()

                for (roadEdge in edges) {
                    val neighborNode = nodeMap[roadEdge.toNodeId]
                    if (neighborNode == null) continue

                    val neighbor = neighborNode.location

                    // Calculate risk using the RiskPredictor
                    val features = dataProvider.getRiskFeatures(current, neighbor, LocalTime.now())
                    val risk = riskPredictor.predict(features)

                    val tentativeGScore =
                        gScore.getValue(current) + roadEdge.distance * (1 + riskWeight * risk)

                    if (tentativeGScore < gScore.getValue(neighbor)) {
                        cameFrom[neighbor] = current
                        gScore[neighbor] = tentativeGScore
                        fScore[neighbor] = tentativeGScore + heuristic(neighbor, goal)
                        openSet.add(neighbor)
                    }
                }
            }
        }
        return emptyList()
    }

    private fun heuristic(a: GeoPoint, b: GeoPoint): Double {
        val dx = a.latitude - b.latitude
        val dy = a.longitude - b.longitude
        return sqrt(dx * dx + dy * dy)
    }

    private fun reconstructPath(
        cameFrom: Map<GeoPoint, GeoPoint>,
        current: GeoPoint
    ): List<GeoPoint> {
        var curr = current
        val path = mutableListOf(curr)
        while (cameFrom.containsKey(curr)) {
            curr = cameFrom[curr]!!
            path.add(curr)
        }
        return path.reversed()
    }
}