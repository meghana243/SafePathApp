package com.example.safepathapp

import com.example.safepathapp.TFLiteRiskPredictor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.*
import kotlin.math.*
import com.example.safepathapp.RiskPredictor
import com.example.safepathapp.TFLiteRiskPredictorAdapter

// --- 1. IMPROVED HEATMAP OVERLAY ---
private lateinit var osmFeatureExtractor: OSMFeatureExtractor
class HeatMapOverlay : org.osmdroid.views.overlay.Overlay() {
    private val points = mutableListOf<GeoPoint>()
    private val screenPoint = Point() // Reuse to save memory

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        alpha = 40 // Low alpha = stacking transparency creates "Heat"
        isAntiAlias = true
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL) // Glowing effect
    }

    fun addPoint(point: GeoPoint) {
        points.add(point)
    }

    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null) return

        val projection = mapView.projection
        val screenRect = projection.intrinsicScreenRect
        // Expand drawing area slightly to handle partial circles
        screenRect.inset(-50, -50)

        for (point in points) {
            projection.toPixels(point, screenPoint)
            // Only draw if on screen (Performance)
            if (screenRect.contains(screenPoint.x, screenPoint.y)) {
                canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), 30f, paint)
            }
        }
    }
}

class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
//    private lateinit var riskPredictor: RiskPredictor
//    private lateinit var tflitePredictor: TFLiteRiskPredictor

    private var locationOverlay: MyLocationNewOverlay? = null
    private lateinit var btnMyLocation: MaterialButton
    private lateinit var btnSearch: FloatingActionButton

    private var searchMarker: Marker? = null
    //    private lateinit var riskPredictor: RiskPredictor
    private lateinit var tflitePredictor: TFLiteRiskPredictor

    private val routeOverlays = mutableListOf<Polyline>()

    // UI Enhancement Components
    private var routeInfoCard: MaterialCardView? = null
    private var routeLegend: LinearLayout? = null
    private var currentTransportMode: String = "driving-car"
    private var currentRouteData: List<RouteInfo> = emptyList()

    // Bottom sheet safety TextViews
    private var tvCrimeScore: TextView? = null
    private var tvNightSafety: TextView? = null
    private var tvLighting: TextView? = null
    private var tvCrowdDensity: TextView? = null // Renamed to match XML
    private var tvRoadQuality: TextView? = null
    private var tvAccidents: TextView? = null
    private var tvPoliceDist: TextView? = null

    // ML Model Integrations
    private lateinit var riskPredictor: RiskPredictor
    private val crimePoints: MutableList<GeoPoint> = mutableListOf()

    companion object {
        private const val TAG = "MapActivity"
        private const val LOCATION_PERMISSION_REQUEST = 1

        // ⚠️ REPLACE WITH YOUR REAL KEY (Starts with 5b3ce...)
        private const val ORS_API_KEY = "eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6IjdiNzZhNTNkOGMxZmQ2MmYwZmZmYjQ4ZGYyN2QzZjQzYTY4MTNiYTVhNjQ1NGE1YmQzZTMxMzM3IiwiaCI6Im11cm11cjY0In0="

        private const val CRIME_PROXIMITY_M = 200.0
        private const val ML_INPUT_FEATURES = 15
    }

    data class RouteInfo(
        val distance: Double, // km
        val duration: Double, // minutes
        val safetyScore: Int, // 0-100
        val safetyFactors: List<String>,
        val color: Int,
        val isSafest: Boolean = false,
        val routeType: String,
        val coordinates: List<GeoPoint>
    )

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        // Initialize OSMDroid configuration
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_map)

        // Initialize Risk Predictor
        lateinit var riskPredictor: RiskPredictor

        try {
            // Step 1: Create concrete TFLite predictor
            val tfLitePredictor = TFLiteRiskPredictor(this)

            // Step 2: Wrap it with adapter (THIS is what the app uses)
            riskPredictor = TFLiteRiskPredictorAdapter(tfLitePredictor)

            Log.i(TAG, "✅ RiskPredictor initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ RiskPredictor init failed", e)
        }


        // Initialize Views
        mapView = findViewById(R.id.map)
        btnMyLocation = findViewById(R.id.btn_my_location)
        btnSearch = findViewById(R.id.fab_search_trigger)
        val btnRefresh = findViewById<FloatingActionButton>(R.id.btn_refresh_crime)

        // Bind Safety Views (Matching IDs from activity_map.xml)
        tvCrimeScore = findViewById(R.id.tvCrimeScore)
        tvNightSafety = findViewById(R.id.tvNightSafety)
        tvLighting = findViewById(R.id.tvLighting)
        tvCrowdDensity = findViewById(R.id.tvCrowdDensity) // Fixed ID
        tvRoadQuality = findViewById(R.id.tvRoadQuality)
        tvAccidents = findViewById(R.id.tvAccidents) // Fixed ID
        tvPoliceDist = findViewById(R.id.tvPoliceDist)

        // Setup Map
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)

        // Start at a default location (e.g. India or New York) until GPS kicks in
        val startPoint = GeoPoint(20.5937, 78.9629)
        mapView.controller.setCenter(startPoint)

        initializeLocationOverlay()

        // Set Listeners
        btnRefresh.setOnClickListener { fetchRealCrimeNews() }
        btnMyLocation.setOnClickListener { centerMapOnUserLocation() }

        btnSearch.setOnClickListener {
            val input = findViewById<EditText>(R.id.input_destination)
            val query = input.text.toString().trim()
            if (query.isNotEmpty()) showTransportModeDialog()
            else Toast.makeText(this, "Please enter a destination", Toast.LENGTH_SHORT).show()
        }

        setupRouteInfoCard()
        setupRouteLegend()
        loadCrimeData()
        addCrimeHeatMap()


    }

    // --------------------------
    // UI: Route Info / Legend
    // --------------------------
    private val policeStations = listOf(
        // Central Bangalore
        PoliceStation(12.9719, 77.5937), // Cubbon Park PS
        PoliceStation(12.9750, 77.5990), // Ashok Nagar PS

        // South Bangalore
        PoliceStation(12.9279, 77.6271), // Jayanagar PS
        PoliceStation(12.9141, 77.6446), // BTM Layout PS
        PoliceStation(12.9304, 77.6784), // Koramangala PS
        PoliceStation(12.9250, 77.5468), // Banashankari PS
        PoliceStation(12.8006, 77.5691),  // Kaggalipura PS

        // North Bangalore
        PoliceStation(13.0358, 77.5970), // Hebbal PS
        PoliceStation(13.0221, 77.6413), // RT Nagar PS

        // East Bangalore
        PoliceStation(12.9982, 77.7019), // Whitefield PS
        PoliceStation(12.9763, 77.7280), // KR Puram PS

        // West Bangalore
        PoliceStation(12.9882, 77.5547)  // Rajajinagar PS
    )

    private fun loadCrimeData() {
        try {
            val inputStream = assets.open("crime_points.json")
            val json = inputStream.bufferedReader().use { it.readText() }

            val array = JSONArray(json)

            crimePoints.clear()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)

                val point = GeoPoint(
                    obj.getDouble("lat"),
                    obj.getDouble("lng")
                )

                crimePoints.add(point)
            }

            Log.d(TAG, "✅ Loaded ${crimePoints.size} crime points")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading crime data", e)
        }
    }
    private fun setupRouteInfoCard() {
        routeInfoCard = MaterialCardView(this).apply {
            cardElevation = 12f
            radius = 24f
            setCardBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }

        val cardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
        }

        // Title
        val tvTitle = TextView(this).apply {
            text = "Route Summary"
            textSize = 18f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }

        val tvDistance = TextView(this).apply {
            text = "Distance: -- km"
            textSize = 16f
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, 4, 0, 4)
        }
        val tvDuration = TextView(this).apply {
            text = "Duration: -- min"
            textSize = 16f
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, 4, 0, 4)
        }
        val tvSafety = TextView(this).apply {
            text = "Safety Score: --"
            textSize = 16f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 4)
        }
        val tvSafetyDetails = TextView(this).apply {
            text = "Details: No route selected"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 4, 0, 0)
        }

        // Add all views
        cardContent.addView(tvTitle)
        cardContent.addView(tvDistance)
        cardContent.addView(tvDuration)
        cardContent.addView(tvSafety)
        cardContent.addView(tvSafetyDetails)
        routeInfoCard!!.addView(cardContent)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            setMargins(16, 250, 16, 0) // Adjusted margin to not overlap search
        }

        addContentView(routeInfoCard, params)
    }

    private fun setupRouteLegend() {
        routeLegend = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val legendCard = MaterialCardView(this).apply {
            cardElevation = 8f
            radius = 16f
            setCardBackgroundColor(Color.WHITE)
            visibility = View.GONE
            addView(routeLegend)
        }

        val title = TextView(this).apply {
            text = "Route Types"
            textSize = 14f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(8, 0, 8, 12)
        }
        routeLegend!!.addView(title)

        val legendItems = listOf(
            "Safest Route" to Color.GREEN,
            "Fastest Route" to Color.BLUE,
            "Balanced Route" to Color.rgb(255, 165, 0)
        )

        for ((labelText, colorInt) in legendItems) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 6, 8, 6)
            }
            val colorBox = View(this).apply {
                setBackgroundColor(colorInt)
                layoutParams = LinearLayout.LayoutParams(32, 32).apply {
                    setMargins(0, 0, 12, 0)
                }
            }
            val label = TextView(this).apply {
                text = labelText
                textSize = 13f
                setTextColor(Color.parseColor("#424242"))
            }
            itemLayout.addView(colorBox)
            itemLayout.addView(label)
            routeLegend!!.addView(itemLayout)
        }

        val legendParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(16, 200, 16, 0)
            gravity = Gravity.TOP or Gravity.END
        }

        addContentView(legendCard, legendParams)
    }

    // --------------------------
    // News Logic
    // --------------------------

    private fun fetchRealCrimeNews() {
        Toast.makeText(this, "🗞️ Searching local news...", Toast.LENGTH_SHORT).show()
        val btnRefresh = findViewById<FloatingActionButton>(R.id.btn_refresh_crime)
        btnRefresh?.animate()?.rotationBy(360f)?.setDuration(1000)?.start()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userLoc = locationOverlay?.myLocation ?: mapView.mapCenter as GeoPoint
                val geocoder = Geocoder(this@MapActivity, Locale.getDefault())
                val addresses = try {
                    geocoder.getFromLocation(userLoc.latitude, userLoc.longitude, 1)
                } catch (e: Exception) { null }

                val city = if (!addresses.isNullOrEmpty()) {
                    addresses[0].locality ?: addresses[0].subAdminArea ?: "Current Location"
                } else "Your Area"

                Log.d(TAG, "Fetching news for: $city")

                val query = URLEncoder.encode("crime accident safety in $city", "UTF-8")
                val rssUrl = "https://news.google.com/rss/search?q=$query&hl=en-IN&gl=IN&ceid=IN:en"

                val url = URL(rssUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val xmlData = connection.inputStream.bufferedReader().readText()
                    val newsItems = parseRssTitles(xmlData)

                    withContext(Dispatchers.Main) {
                        if (newsItems.isEmpty()) {
                            Toast.makeText(this@MapActivity, "No recent news found for $city.", Toast.LENGTH_SHORT).show()
                        } else {
                            val intent = Intent(this@MapActivity, NewsActivity::class.java)
                            intent.putStringArrayListExtra("NEWS_LIST", ArrayList(newsItems))
                            intent.putExtra("CITY_NAME", city)
                            startActivity(intent)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MapActivity, "News server error", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching news", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MapActivity, "Could not fetch news.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun parseRssTitles(xmlData: String): List<String> {
        val titles = mutableListOf<String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val xpp = factory.newPullParser()
            xpp.setInput(StringReader(xmlData))
            var eventType = xpp.eventType
            var insideItem = false
            var count = 0

            while (eventType != XmlPullParser.END_DOCUMENT && count < 15) {
                if (eventType == XmlPullParser.START_TAG) {
                    if (xpp.name.equals("item", ignoreCase = true)) {
                        insideItem = true
                    } else if (insideItem && xpp.name.equals("title", ignoreCase = true)) {
                        val title = xpp.nextText()
                        if (!title.contains("Google News")) {
                            titles.add(title)
                            count++
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG && xpp.name.equals("item", ignoreCase = true)) {
                    insideItem = false
                }
                eventType = xpp.next()
            }
        } catch (e: Exception) { e.printStackTrace() }
        return titles
    }

    // --------------------------
    // Route & Safety Updates
    // --------------------------

    private fun updateRouteInfo(route: RouteInfo) {
        // 1. Make UI visible
        findViewById<View>(R.id.bottom_sheet_content)?.visibility = View.VISIBLE
        routeInfoCard?.visibility = View.VISIBLE
        (routeLegend?.parent as? MaterialCardView)?.visibility = View.VISIBLE

        // 2. Update Route Info Card
        val cardContent = routeInfoCard?.getChildAt(0) as? LinearLayout
        if (cardContent != null && cardContent.childCount >= 5) {
            (cardContent.getChildAt(1) as TextView).text = "Distance: %.1f km".format(route.distance)
            (cardContent.getChildAt(2) as TextView).text = "Duration: %.0f min".format(route.duration)

            val safetyText = when {
                route.safetyScore >= 90 -> "Safety: Excellent"
                route.safetyScore >= 75 -> "Safety: Good"
                route.safetyScore >= 60 -> "Safety: Fair"
                else -> "Safety: Caution"
            }
            val safetyEmoji = if (route.safetyScore >= 75) "🟢" else if (route.safetyScore >= 60) "🟠" else "🔴"

            val tvSafety = cardContent.getChildAt(3) as TextView
            tvSafety.text = "$safetyEmoji $safetyText (${route.safetyScore}/100)"
            tvSafety.setTextColor(if (route.safetyScore >= 60) Color.parseColor("#4CAF50") else Color.RED)
            (cardContent.getChildAt(4) as TextView).text = "Factors: ${route.safetyFactors.take(3).joinToString(" • ")}"
        }

        // 3. Update Bottom Sheet Details
        CoroutineScope(Dispatchers.Main).launch {
            // Crime
            val crimeHits = calculateCrimeHitCount(route.coordinates)
            val crimeScore = min(100, crimeHits * 8 + (100 - route.safetyScore) / 6).coerceAtLeast(0)
            findViewById<Chip>(R.id.chip_crime)?.text = "${100 - crimeScore}/100"
            findViewById<TextView>(R.id.tvCrimeScore)?.text = "Risk calculated from $crimeHits incidents nearby."

            // Lighting (Async)
            val mid = if (route.coordinates.isNotEmpty()) route.coordinates[route.coordinates.size / 2] else GeoPoint(0.0, 0.0)
            val lampCount = try {
                withContext(Dispatchers.IO) { fetchOverpassCountForTag(mid.latitude, mid.longitude, "\"highway\"=\"street_lamp\"", 250) }
            } catch (e: Exception) { 0 }

            val lightingStatus = if(lampCount >= 10) "High" else if(lampCount >= 3) "Medium" else "Low"
            findViewById<Chip>(R.id.chip_lighting)?.text = lightingStatus
            findViewById<TextView>(R.id.tvLighting)?.text = "Found $lampCount street lamps nearby."

            // Crowd
            val poiCount = try {
                withContext(Dispatchers.IO) {
                    val q = """node["amenity"~"bus_station|marketplace|restaurant|pub|bar|cafe"](around:300,${mid.latitude},${mid.longitude}); out;"""
                    fetchElementsCount(q)
                }
            } catch (e: Exception) { 0 }
            findViewById<TextView>(R.id.tvCrowdDensity)?.text = if(poiCount > 10) "High ($poiCount POIs)" else "Low ($poiCount)"

            // Road
            val surface = try {
                withContext(Dispatchers.IO) { fetchNearestRoadSurface(mid.latitude, mid.longitude) }
            } catch (e: Exception) { "Unknown" }
            findViewById<TextView>(R.id.tvRoadQuality)?.text = surface?.replace("_", " ")?.capitalize()

            // Accidents
            // --- ACCIDENTS / INCIDENTS (Calculated from crimes.json) ---
// We check how many crime points are within 300m of the route's midpoint
            val accidentCount = crimePoints.count {
                it.distanceToAsDouble(mid) <= 300.0
            }

            findViewById<TextView>(R.id.tvAccidents)?.text =
                if(accidentCount > 0) "$accidentCount reported" else "None reported"

            // Police
            val policeDistMeters = try {
                withContext(Dispatchers.IO) {
                    fetchNearestPoliceDistance(
                        mid.latitude,
                        mid.longitude,
                        policeStations
                    ) * 1000
                }
            } catch (e: Exception) {
                Double.MAX_VALUE
            }

            findViewById<TextView>(R.id.tvPoliceDist)?.text =
                if (policeDistMeters < 5000)
                    "%.0f m away".format(policeDistMeters)
                else
                    "None nearby"


            // Night
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val nightText = if (currentHour !in 6..18) "Caution (Night)" else "Safe (Day)"
            findViewById<TextView>(R.id.tvNightSafety)?.text = nightText

            buildRouteComparisonUI(currentRouteData)
        }
    }

    private fun buildRouteComparisonUI(routes: List<RouteInfo>) {
        val bottomSheetContent = findViewById<LinearLayout>(R.id.bottom_sheet_content) ?: return
        val existing = bottomSheetContent.findViewWithTag<LinearLayout>("route_comparison_container")
        if (existing != null) bottomSheetContent.removeView(existing)

        if (routes.isEmpty()) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 10, 6, 6)
            tag = "route_comparison_container"
        }

        val header = TextView(this).apply {
            text = "Route options (tap to view)"
            setTextColor(Color.DKGRAY)
            textSize = 14f
        }
        container.addView(header)

        val sorted = routes.sortedWith(compareByDescending<RouteInfo> { it.isSafest }.thenBy { it.duration })
        var shown = 0
        for (r in sorted) {
            if (shown >= 3) break
            val type = when {
                r.routeType.contains("safest") -> "Safest"
                r.routeType.contains("fastest") -> "Fastest"
                else -> "Balanced"
            }
            val item = TextView(this).apply {
                text = "$type — ${"%.1f".format(r.distance)}km • ${"%.0f".format(r.duration)}min • ${r.safetyScore}/100"
                textSize = 14f
                setPadding(8, 16, 8, 16)
                setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
                setOnClickListener { updateRouteInfo(r) }
            }
            container.addView(item)
            shown++
        }
        bottomSheetContent.addView(container)
    }

    // --------------------------
    // Safety Calculation
    // --------------------------

    private fun calculateCrimeHitCount(coordinates: List<GeoPoint>): Int {
        if (crimePoints.isEmpty()) return 0
        var hitCount = 0
        val sampleStride = maxOf(1, coordinates.size / 50)
        for (i in coordinates.indices step sampleStride) {
            for (crimePoint in crimePoints) {
                if (coordinates[i].distanceToAsDouble(crimePoint) <= CRIME_PROXIMITY_M) {
                    hitCount++
                    break
                }
            }
        }
        return hitCount
    }

    private fun createMLFeatures(
        coordinates: List<GeoPoint>,
        distanceKm: Double,
        durationMin: Double,
        transportMode: String
    ): Pair<FloatArray, MutableList<String>> {
        val features = FloatArray(ML_INPUT_FEATURES)
        val time = Calendar.getInstance()
        val currentHour = time.get(Calendar.HOUR_OF_DAY)

        features[0] = distanceKm.toFloat()
        features[1] = durationMin.toFloat()
        features[2] = (distanceKm.toFloat() / (durationMin.toFloat() / 60f)).coerceIn(1f, 100f)

        when (currentHour) {
            in 6..18 -> features[3] = 1.0f
            in 19..22 -> features[4] = 1.0f
            else -> features[5] = 1.0f
        }

        when (transportMode) {
            "driving-car" -> features[6] = 1.0f
            "foot-walking" -> features[7] = 1.0f
            "cycling-regular" -> features[8] = 1.0f
        }

        val routeDensity = calculateRouteDensity(coordinates).toFloat()
        features[9] = routeDensity

        val crimeHitCount = calculateCrimeHitCount(coordinates)
        features[10] = crimeHitCount.toFloat()
        features[11] = crimeHitCount.toFloat() * routeDensity
        features[12] = 1.0f - routeDensity

        val factorList: MutableList<String> = mutableListOf()
        if (features[3] == 1.0f) factorList.add("Daytime")
        if (features[5] == 1.0f) factorList.add("Nighttime")
        if (crimeHitCount > 0) factorList.add("High Risk Zone")

        return Pair(features, factorList)
    }

    private fun calculateSafetyScore(
        distance: Double,
        coordinates: List<GeoPoint>,
        transportMode: String,
        duration: Double
    ): Pair<Int, List<String>> {
        val factorListMutable = mutableListOf<String>()
        var finalScore: Int

        // 1. Calculate Physical Factors (Heuristic)
        // We start with a base score and subtract points for bad conditions
        var heuristicScore = 100

        // A. Crime Proximity
        val crimeHits = calculateCrimeHitCount(coordinates)
        if (crimeHits > 0) {
            val penalty = (crimeHits * 5).coerceAtMost(40) // Max 40 point penalty
            heuristicScore -= penalty
            factorListMutable.add("Crime reports nearby (-$penalty)")
        } else {
            factorListMutable.add("No recent crime reports (+0)")
        }

        // B. Route Length/Complexity
        if (distance > 10.0) {
            heuristicScore -= 5
            factorListMutable.add("Long route penalty (-5)")
        }

        // C. Transport Mode Bonus/Penalty
        when (transportMode) {
            "foot-walking" -> {
                // Walking is riskier than driving
                heuristicScore -= 10
            }

            "cycling-regular" -> heuristicScore -= 5
            "driving-car" -> heuristicScore += 5 // Cars are generally safer from street crime
        }

        // 2. Try AI Prediction
//        if (riskPredictor.getTFLiteInterpreter() != null) {
        try {
            val (mlFeatures, _) = createMLFeatures(
                coordinates,
                distance,
                duration,
                transportMode
            )

            // Interface-based prediction (no interpreter access!)
            val rawRisk = riskPredictor.predict(mlFeatures)
            Log.d(TAG, "Raw AI Risk Output: $rawRisk")

            /*
                 ML OUTPUT CALIBRATION
                 - If model uses sigmoid → output ∈ [0,1]
                 - If regression/logits → clamp safely
                */
            val normalizedRisk = when {
                rawRisk.isNaN() || rawRisk.isInfinite() -> 0.5
                rawRisk <= 0.0 -> 0.0
                rawRisk >= 1.0 -> 1.0
                else -> rawRisk
            }

            // Convert risk → safety score (higher = safer)
            val aiScore = ((1.0 - normalizedRisk) * 100).toInt()

            // Fuse with heuristic score
            finalScore = ((aiScore * 0.6) + (heuristicScore * 0.4)).toInt()

            factorListMutable.add(
                0,
                "AI Risk Score: ${"%.2f".format(normalizedRisk)}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "AI risk prediction failed, using heuristic only", e)

            finalScore = heuristicScore
            factorListMutable.add("AI unavailable (heuristic fallback)")
        }
        return Pair(finalScore, factorListMutable)
    }

    // --------------------------
    // Map & Location Setup
    // --------------------------

    private fun initializeLocationOverlay() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        } else {
            setupLocationOverlay()
        }
    }

    private fun setupLocationOverlay() {
        if (locationOverlay == null) {
            locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
            mapView.overlays.add(locationOverlay)
        }
        locationOverlay?.enableMyLocation()
    }

    private fun centerMapOnUserLocation() {
        locationOverlay?.myLocation?.let {
            mapView.controller.animateTo(it)
            mapView.controller.setZoom(16.0)
        } ?: Toast.makeText(this, "Location not available.", Toast.LENGTH_SHORT).show()
    }



    private var heatMapOverlay: HeatMapOverlay? = null

    private fun addCrimeHeatMap() {
        if (crimePoints.isEmpty()) return

        // Remove the old overlay if it exists to avoid stacking
        heatMapOverlay?.let { mapView.overlays.remove(it) }

        val overlay = HeatMapOverlay()
        for (point in crimePoints) {
            overlay.addPoint(point)
        }

        heatMapOverlay = overlay
        mapView.overlays.add(overlay)
        mapView.invalidate()
    }

    private fun calculateRouteDensity(coordinates: List<GeoPoint>): Double {
        if (coordinates.size < 2) return 0.5
        var totalDistance = 0.0
        for (i in 1 until coordinates.size) totalDistance += coordinates[i - 1].distanceToAsDouble(coordinates[i])
        return (coordinates.size.toDouble() / maxOf(1.0, totalDistance / 1000.0)).coerceAtMost(1.0)
    }

    private fun calculateAccurateDuration(distance: Double, transportMode: String, coordinates: List<GeoPoint>): Double {
        val routeDensity = calculateRouteDensity(coordinates)
        val baseSpeed = when (transportMode) {
            "foot-walking" -> if (routeDensity > 0.7) 3.5 else 5.0
            "cycling-regular" -> 15.0
            "driving-car" -> if (routeDensity > 0.7) 30.0 else 50.0
            else -> 40.0
        }
        return (distance / baseSpeed) * 60
    }

    // --------------------------
    // Routing Logic (ORS + OSRM)
    // --------------------------

    private fun showTransportModeDialog() {
        val modes = arrayOf("Walking", "Bike", "Car")
        val modeMap = mapOf("Walking" to "foot-walking", "Bike" to "cycling-regular", "Car" to "driving-car")

        AlertDialog.Builder(this)
            .setTitle("Choose Transport Mode")
            .setItems(modes) { _, which ->
                val selectedMode = modes[which]
                val transportMode = modeMap[selectedMode] ?: "driving-car"

                val input = findViewById<EditText>(R.id.input_destination)
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) {
                    currentTransportMode = transportMode
                    searchLocation(query, transportMode)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun searchLocation(query: String, transportMode: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val urlString = "https://nominatim.openstreetmap.org/search?format=json&q=$encodedQuery&limit=1"
                val response = URL(urlString).readText()
                val results = JSONArray(response)

                withContext(Dispatchers.Main) {
                    if (results.length() > 0) {
                        val obj = results.getJSONObject(0)
                        val point = GeoPoint(obj.getDouble("lat"), obj.getDouble("lon"))

                        searchMarker?.let { mapView.overlays.remove(it) }
                        searchMarker = Marker(mapView).apply {
                            position = point
                            title = obj.getString("display_name")
                        }
                        mapView.overlays.add(searchMarker)
                        mapView.controller.animateTo(point)

                        showRoutesToDestination(point, transportMode)
                    } else {
                        Toast.makeText(this@MapActivity, "Location not found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showRoutesToDestination(destination: GeoPoint, transportMode: String) {
        val userLocation = locationOverlay?.myLocation ?: run {
            Toast.makeText(this, "Waiting for GPS...", Toast.LENGTH_SHORT).show()
            return
        }

        routeOverlays.forEach { mapView.overlays.remove(it) }
        routeOverlays.clear()

        fetchRoutesWithFallback(userLocation.latitude, userLocation.longitude, destination.latitude, destination.longitude, transportMode)
    }

    private fun fetchRoutesWithFallback(startLat: Double, startLon: Double, endLat: Double, endLon: Double, transportMode: String) {
        CoroutineScope(Dispatchers.IO).launch {
            if (!tryOpenRouteService(startLat, startLon, endLat, endLon, transportMode)) {
                tryOSRM(startLat, startLon, endLat, endLon, transportMode)
            }
        }
    }

    private suspend fun tryOpenRouteService(startLat: Double, startLon: Double, endLat: Double, endLon: Double, transportMode: String): Boolean {
        return try {
            val jsonBody = JSONObject().apply {
                put("coordinates", JSONArray().apply {
                    put(JSONArray().apply { put(startLon); put(startLat) })
                    put(JSONArray().apply { put(endLon); put(endLat) })
                })
                put("profile", transportMode)
                put("format", "geojson")
                put("options", JSONObject().apply {
                    put("avoid_features", JSONArray().apply { put("ferries") })
                })
            }

            val url = URL("https://api.openrouteservice.org/v2/directions/$transportMode/geojson")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", ORS_API_KEY)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.outputStream.use { it.write(jsonBody.toString().toByteArray()) }

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val features = JSONObject(response).getJSONArray("features")
                withContext(Dispatchers.Main) {
                    drawOrsRoutes(features, transportMode)
                }
                true
            } else false
        } catch (e: Exception) { false }
    }

    private fun drawOrsRoutes(features: JSONArray, transportMode: String) {
        val routes = mutableListOf<JSONObject>()
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val summary = feature.getJSONObject("properties").getJSONObject("summary")
            routes.add(JSONObject().apply {
                put("distance", summary.getDouble("distance"))
                put("duration", summary.getDouble("duration"))
                put("geometry", feature.getJSONObject("geometry"))
            })
        }
        drawEnhancedRoutes(routes, transportMode)
    }

    private suspend fun tryOSRM(startLat: Double, startLon: Double, endLat: Double, endLon: Double, transportMode: String) {
        try {
            val profile = if(transportMode == "driving-car") "driving" else "foot"
            val urlString = "https://router.project-osrm.org/route/v1/$profile/$startLon,$startLat;$endLon,$endLat?overview=full&geometries=geojson"
            val response = URL(urlString).readText()
            val routes = JSONObject(response).getJSONArray("routes")

            withContext(Dispatchers.Main) {
                val list = mutableListOf<JSONObject>()
                for(i in 0 until routes.length()) list.add(routes.getJSONObject(i))
                drawEnhancedRoutes(list, transportMode)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun drawEnhancedRoutes(routes: List<JSONObject>, transportMode: String) {
        if (routes.isEmpty()) return

        val tempRoutes = routes.mapNotNull { route ->
            try {
                val coordsJson = route.getJSONObject("geometry").getJSONArray("coordinates")
                val routeCoordinates = mutableListOf<GeoPoint>()
                for (j in 0 until coordsJson.length()) {
                    val c = coordsJson.getJSONArray(j)
                    routeCoordinates.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
                }

                val dist = route.getDouble("distance") / 1000.0
                val dur = calculateAccurateDuration(dist, transportMode, routeCoordinates)
                val (score, factors) = calculateSafetyScore(dist, routeCoordinates, transportMode, dur)

                RouteInfo(dist, dur, score, factors, Color.GRAY, false, "alternative", routeCoordinates)
            } catch (e: Exception) { null }
        }

        if (tempRoutes.isEmpty()) return

        // Sort: Safest, then Fastest
        val maxSafe = tempRoutes.maxByOrNull { it.safetyScore }?.safetyScore ?: 0
        val minDur = tempRoutes.minByOrNull { it.duration }?.duration ?: Double.MAX_VALUE

        val enhancedRoutes = tempRoutes.map { r ->
            val isSafest = r.safetyScore == maxSafe
            val isFastest = r.duration == minDur
            val color = if(isSafest) Color.GREEN else if(isFastest) Color.BLUE else Color.rgb(255, 165, 0)
            val type = if(isSafest) "safest" else if(isFastest) "fastest" else "balanced"
            r.copy(color = color, isSafest = isSafest, routeType = type)
        }

        routeOverlays.forEach { mapView.overlays.remove(it) }
        routeOverlays.clear()

        // Draw non-safest first, safest last (on top)
        enhancedRoutes.sortedBy { it.isSafest }.forEach { r ->
            val poly = Polyline(mapView).apply {
                outlinePaint.color = r.color
                outlinePaint.strokeWidth = if(r.isSafest) 12f else 8f
            }
            r.coordinates.forEach { poly.addPoint(it) }
            mapView.overlays.add(poly)
            routeOverlays.add(poly)
        }

        // Default selection
        val best = enhancedRoutes.firstOrNull { it.isSafest } ?: enhancedRoutes.first()
        updateRouteInfo(best)
        currentRouteData = enhancedRoutes
        mapView.invalidate()
    }

    // Overpass Helpers
    private fun fetchOverpassCountForTag(lat: Double, lon: Double, tagPair: String, radius: Int): Int {
        val q = """[out:json];node[$tagPair](around:$radius,$lat,$lon);out;"""
        return fetchElementsCount(q)
    }

    private fun fetchElementsCount(query: String): Int {
        val url = URL("https://overpass-api.de/api/interpreter")
        val body = "data=${URLEncoder.encode(query, "UTF-8")}"
        try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                return JSONObject(resp).optJSONArray("elements")?.length() ?: 0
            }
        } catch (e: Exception) { }
        return 0
    }

    // 1. Define the data structures
    data class OverpassResponse(val elements: List<Element>)
    data class Element(val tags: Map<String, String>?)

    // 2. The corrected function
    private suspend fun fetchNearestRoadSurface(lat: Double, lon: Double): String? {
        try {
            // In a real app, 'networkClient' would be your Retrofit interface
            // This is a placeholder showing how to access the 'tags' safely
            val response: OverpassResponse = performApiCall(lat, lon)

            // Use ?. to safely access tags if elements is not empty
            return response.elements.firstOrNull()?.tags?.get("surface") ?: "Paved"
        } catch (e: Exception) {
            return "Unknown"
        }
    }

    // 3. Mock helper to satisfy the compiler
    private suspend fun performApiCall(lat: Double, lon: Double): OverpassResponse {
        // This is where your actual networking code (OkHttp/Retrofit) goes
        return OverpassResponse(emptyList())
    }

    private fun fetchNearestPoliceDistance(
        lat: Double,
        lon: Double,
        policeStations: List<PoliceStation>
    ): Double {

        var minDistance = Double.MAX_VALUE

        for (station in policeStations) {
            val distance = LocationUtils.calculateDistance(
                lat, lon,
                station.lat, station.lon
            )
            if (distance < minDistance) {
                minDistance = distance
            }
        }
        return minDistance
    }


    private fun countNearbyAccidents(lat: Double, lon: Double, rad: Double): Int {
        return try {
            val json = assets.open("crime_points.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            var c = 0
            for(i in 0 until arr.length()){
                val o = arr.getJSONObject(i)
                if(haversine(lat, lon, o.getDouble("lat"), o.getDouble("lon")) <= rad) c++
            }
            c
        } catch(e: Exception) { 0 }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupLocationOverlay()
        }
    }
}