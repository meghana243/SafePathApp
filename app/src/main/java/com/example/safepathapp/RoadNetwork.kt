package com.example.safepathapp

import org.osmdroid.util.GeoPoint

/**
 * Represents the road network data between two points
 */
data class RoadNetwork(
    val nodes: List<RoadNode>,
    val edges: List<RoadEdge>,
    val boundingBox: BoundingBox
)

/**
 * Represents a node (intersection/point) in the road network
 */
data class RoadNode(
    val id: String,
    val location: GeoPoint,
    val type: NodeType = NodeType.INTERSECTION
)

enum class NodeType {
    INTERSECTION,
    WAYPOINT,
    START,
    END
}

/**
 * Represents an edge (road segment) in the road network
 */
data class RoadEdge(
    val id: String,
    val fromNodeId: String,
    val toNodeId: String,
    val distance: Double, // in meters
    val roadType: RoadType = RoadType.RESIDENTIAL,
    val speedLimit: Int? = null, // in km/h
    val name: String? = null
)

enum class RoadType {
    MOTORWAY,
    TRUNK,
    PRIMARY,
    SECONDARY,
    TERTIARY,
    RESIDENTIAL,
    SERVICE,
    FOOTWAY,
    CYCLEWAY,
    PATH
}

/**
 * Represents a geographic bounding box
 */
data class BoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)