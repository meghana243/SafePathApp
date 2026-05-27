package com.example.safepathapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import com.google.android.material.card.MaterialCardView
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import androidx.core.graphics.toColorInt
import java.time.Instant.now
import java.util.Calendar
import java.util.TimeZone
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler


class MainActivity : AppCompatActivity() {

    // ── TFLite ───────────────────────────────────────────────────────────────
    private lateinit var tfLiteRiskPredictor: TFLiteRiskPredictor
    private var currentLocation: GeoPoint? = null
    private lateinit var sensorManager: SensorManager
    private var shakeListener: SensorEventListener? = null
    private var stationarySince     = 0L
    private var lastAnomalyAlert    = 0L
    private var anomalySensorManager: SensorManager? = null
    private var anomalyListener:      SensorEventListener? = null
    // ── Add this field at the top of MainActivity ─────────────────────────
    private var isSosDialogShowing = false

    private var lastRerouteTime = 0L

    // ── Route state ──────────────────────────────────────────────────────────
    private var fastestRoute: RouteInfo? = null
    private var safestRoute: RouteInfo? = null
    private var highestRiskRoute: RouteInfo? = null
    private var routeOverlay: Polyline? = null

    // ── Safe zone state ──────────────────────────────────────────────────────
    private val safeZoneMarkers = mutableListOf<Marker>()
    private var safeZonesVisible = true

    //triggering rerouting
    private var activeTransportMode: String = "Walking" // Default

    enum class RouteType { FASTEST, SAFEST, HIGHEST_RISK }

    data class RouteInfo(
        val points: List<GeoPoint>,
        val distance: Double,
        val duration: Double,
        val roadData: RoadData,
        // Composite score cached here so updateSafetyCard never re-scans crime data
        val compositeScore: CompositeSafetyScore? = null,
        val sunsetTime: java.util.Date? = null
    )

    data class RoadData(
        val surfaceType: String,
        val roadType: String,
        val isPaved: Boolean,
        val isMajorRoad: Boolean
    )

    data class CompositeSafetyScore(
        val totalScore: Double,       // 0.0 (safe) – 10.0 (dangerous)
        val accidentScore: Double,
        val policeScore: Double,
        val crimeScore: Double,
        val crowdScore: Double,
        val roadScore: Double
    )

    companion object {
        private const val TAG = "SafePathApp"
        private const val ORS_TIMEOUT_SECONDS = 30L
        private const val DEFAULT_ZOOM = 15.0
        private const val ROUTE_ZOOM = 16.0
        private const val BANGALORE_LAT = 12.9716
        private const val BANGALORE_LON = 77.5946
        private const val ACCIDENT_RADIUS_M = 300.0
        private const val ACCIDENT_NORMALISE_AT = 20   // 20+ incidents → max risk
        private const val ROUTE_SAMPLE_STRIDE = 100    // sample every Nth point during ranking
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    private lateinit var map: MapView
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<NestedScrollView>
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // ── Network ──────────────────────────────────────────────────────────────
    private val client = OkHttpClient.Builder()
        .connectTimeout(ORS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(ORS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(ORS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val ORS_API_KEY =
        "eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6IjdiNzZhNTNkOGMxZmQ2MmYwZmZmYjQ4ZGYyN2QzZjQzYTY4MTNiYTVhNjQ1NGE1YmQzZTMxMzM3IiwiaCI6Im11cm11cjY0In0="

    // ── Destination state ────────────────────────────────────────────────────
    private var currentDestination: GeoPoint? = null
    private var destinationMarker: Marker? = null
    private var currentDestinationName: String = ""

    // ── News state ───────────────────────────────────────────────────────────
    private var isDestinationNewsEnabled: Boolean = false

    // ── AI & Pathfinding ─────────────────────────────────────────────────────
    private lateinit var riskPredictor: RiskPredictor
    private lateinit var pathfinder: AStarPathfinder
    private var isTfliteReady = false
    private var isRouteFetching = false

    // ── Crime data — loaded once into parallel arrays for fast iteration ──────
    private var crimeLats: DoubleArray = DoubleArray(0)
    private var crimeLons: DoubleArray = DoubleArray(0)
    private var crimeDataLoaded = false

    // ── Permission launcher ──────────────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.O)
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            if (fine || coarse) {
                enableLocationTracking()
                Toast.makeText(this, "Location permission granted", Toast.LENGTH_SHORT).show()
            } else {
                showPermissionDeniedDialog()
            }
        }

    // ════════════════════════════════════════════════════════════════════════
    // onCreate
    // ════════════════════════════════════════════════════════════════════════

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            userAgentValue = applicationContext.packageName
            load(applicationContext, getSharedPreferences("osm_prefs", MODE_PRIVATE))
        }

        setContentView(R.layout.activity_map)

        tfLiteRiskPredictor = TFLiteRiskPredictor(this)
        testModel()

        setupMap()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        routeOverlay = Polyline().apply {
            outlinePaint.color = Color.BLUE
            outlinePaint.strokeWidth = 8f
        }
        map.overlays.add(routeOverlay)

        setupViews()
        setupShakeToSOS()
        setupFearAnomalyDetection()
        setupRouteButtons()
        setupDestinationNewsToggle()
        setupBottomSheet()

        handleLocationPermissions()
        requestPermissionsIfNeeded()
        initializeAIComponents()

        // Pre-load crime data in the background so the first route is fast
        Thread { loadCrimeDataIfNeeded() }.start()

        loadSafetyNews()
    }

    // ════════════════════════════════════════════════════════════════════════
    // MAP SETUP
    // ════════════════════════════════════════════════════════════════════════

    private fun setupMap() {
        map = findViewById(R.id.map)
        map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
        }
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        map.overlays.add(myLocationOverlay)
        map.controller.setZoom(DEFAULT_ZOOM)
        map.controller.setCenter(GeoPoint(BANGALORE_LAT, BANGALORE_LON))
    }

    // ════════════════════════════════════════════════════════════════════════
    // UI SETUP
    // ════════════════════════════════════════════════════════════════════════


    private fun setupViews() {
        val inputDestination = findViewById<EditText>(R.id.input_destination)
        val btnMyLocIcon = findViewById<ImageButton>(R.id.btn_my_location)
        val btnPlanRoute = findViewById<MaterialButton>(R.id.btn_plan_route)
        val fabSearchTrigger = findViewById<FloatingActionButton>(R.id.fab_search_trigger)

        val bottomSheetView = findViewById<NestedScrollView>(R.id.bottom_sheet_scroll_view)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheetView)
        bottomSheetBehavior.isDraggable = true
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        btnMyLocIcon.setOnClickListener { centerMapOnUserLocation() }

        inputDestination.setOnEditorActionListener { v, _, _ ->
            val query = v.text.toString().trim()
            if (query.isNotEmpty()) {
                hideKeyboard(v); findDestinationCoordinates(query)
            } else Toast.makeText(this, "Please enter a destination", Toast.LENGTH_SHORT).show()
            true
        }

        fabSearchTrigger.setOnClickListener {
            val query = inputDestination.text.toString().trim()
            if (query.isNotEmpty()) {
                hideKeyboard(inputDestination)
                findDestinationCoordinates(query)
            } else {
                Toast.makeText(this, "Please enter a destination", Toast.LENGTH_SHORT).show()
                inputDestination.requestFocus()
                showKeyboard(inputDestination)
            }
        }

        btnPlanRoute.setOnClickListener {
            when {
                !isTfliteReady -> Toast.makeText(
                    this,
                    "AI Model is not ready. Please restart the app.",
                    Toast.LENGTH_SHORT
                ).show()

                isRouteFetching -> Toast.makeText(
                    this,
                    "Already fetching route...",
                    Toast.LENGTH_SHORT
                ).show()

                currentDestination != null -> showTransportModeDialog()
                else -> {
                    Toast.makeText(this, "Search for a location first", Toast.LENGTH_SHORT).show()
                    inputDestination.requestFocus()
                    showKeyboard(inputDestination)
                }
            }
        }

        findViewById<MaterialButton>(R.id.btn_refresh_crime)?.setOnClickListener {
            val shortName = currentDestinationName.split(",").firstOrNull()?.trim()
            if (isDestinationNewsEnabled && shortName != null) loadSafetyNews(locationOverride = shortName)
            else loadSafetyNews()
        }

        findViewById<MaterialButton>(R.id.btn_toggle_safe_zones)?.apply {
            visibility = View.GONE
            setOnClickListener { toggleSafeZoneVisibility() }
        }

        val btnReport = findViewById<MaterialButton>(R.id.btn_report_incident)
        val btnView = findViewById<MaterialButton>(R.id.btn_view_incidents)

        // 1. Logic to OPEN the Google Form
        btnReport.setOnClickListener {
            val googleFormUrl =
                "https://docs.google.com/forms/d/e/1FAIpQLScVPHA9wHsGSqrUOe-F4RWiCuOIGRYRFgA8fMptCL2obYsxVw/viewform?usp=sharing&ouid=116305649611189665281"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(googleFormUrl))
            startActivity(intent)
        }

        // 2. Logic to VIEW the recorded incidents (WebView approach)
        btnView.setOnClickListener {
            val sheetUrl =
                "https://docs.google.com/spreadsheets/d/e/2PACX-1vSo80wiyMSVxtTChr0eRCOLn3gTDYwInErtIKWo2XZemm4nT3jjDLVYEaoUxuLJ5GQR751M_ojwrYwI/pubhtml"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sheetUrl))
            startActivity(intent)
        }

        findViewById<FloatingActionButton>(R.id.fab_sos)?.setOnClickListener { showSOSConfirmation() }
        findViewById<MaterialButton>(R.id.btn_manage_sos)?.setOnClickListener { showContactsList() }


    }

    // ── Route filter buttons ──────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupRouteButtons() {
        val btnFastest = findViewById<Button>(R.id.btnFastest)
        val btnSafest = findViewById<Button>(R.id.btnSafest)
        val btnRisky = findViewById<Button>(R.id.btnRisk)

        btnFastest.setOnClickListener {
            val route = fastestRoute ?: run {
                Toast.makeText(this, "Plan a route first", Toast.LENGTH_SHORT)
                    .show(); return@setOnClickListener
            }
            drawRoute(route.points, RouteType.FASTEST)
            loadSafeZoneMarkers(route.points)
            updateSafetyCard(route, "Fastest")
            highlightRouteButton(RouteType.FASTEST)
        }
        btnSafest.setOnClickListener {
            val route = safestRoute ?: run {
                Toast.makeText(this, "Plan a route first", Toast.LENGTH_SHORT)
                    .show(); return@setOnClickListener
            }
            drawRoute(route.points, RouteType.SAFEST)
            loadSafeZoneMarkers(route.points)
            updateSafetyCard(route, "Safest")
            highlightRouteButton(RouteType.SAFEST)
        }
        btnRisky.setOnClickListener {
            val route = highestRiskRoute ?: run {
                Toast.makeText(this, "Plan a route first", Toast.LENGTH_SHORT)
                    .show(); return@setOnClickListener
            }
            drawRoute(route.points, RouteType.HIGHEST_RISK)
            loadSafeZoneMarkers(route.points)
            updateSafetyCard(route, "High Risk")
            highlightRouteButton(RouteType.HIGHEST_RISK)
        }
    }

    private fun highlightRouteButton(activeType: RouteType) {
        val btnFastest = findViewById<Button>(R.id.btnFastest)
        val btnSafest = findViewById<Button>(R.id.btnSafest)
        val btnRisky = findViewById<Button>(R.id.btnRisk)
        listOf(btnFastest, btnSafest, btnRisky).forEach {
            it.setBackgroundColor("#EEEEEE".toColorInt())
            it.setTextColor("#333333".toColorInt())
        }
        when (activeType) {
            RouteType.FASTEST -> {
                btnFastest.setBackgroundColor("#1565C0".toColorInt()); btnFastest.setTextColor(Color.WHITE)
            }

            RouteType.SAFEST -> {
                btnSafest.setBackgroundColor("#2E7D32".toColorInt()); btnSafest.setTextColor(Color.WHITE)
            }

            RouteType.HIGHEST_RISK -> {
                btnRisky.setBackgroundColor("#B71C1C".toColorInt()); btnRisky.setTextColor(Color.WHITE)
            }
        }
    }

    // ── Bottom sheet ──────────────────────────────────────────────────────────

    private fun setupBottomSheet() {
        val bottomSheet = findViewById<NestedScrollView>(R.id.bottom_sheet_scroll_view)
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.peekHeight = 90.dpToPx()
        behavior.isHideable = false
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun Int.dpToPx(): Int =
        (this * Resources.getSystem().displayMetrics.density).toInt()

    // ════════════════════════════════════════════════════════════════════════
    // CRIME DATA — loaded once, stored in flat arrays for cache-friendly access
    // ════════════════════════════════════════════════════════════════════════

    /** Must only be called from a background thread. */
    @Synchronized
    private fun loadCrimeDataIfNeeded() {
        if (crimeDataLoaded) return
        try {
            val json = assets.open("crime_points.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            val lats = DoubleArray(arr.length())
            val lons = DoubleArray(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                lats[i] = obj.getDouble("lat")
                lons[i] = obj.getDouble("lng")
            }
            crimeLats = lats
            crimeLons = lons
            crimeDataLoaded = true
            Log.d(TAG, "Crime data loaded: ${arr.length()} points")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading crime data", e)
            crimeDataLoaded = true  // don't retry on every route
        }
    }

    private fun fetchSunsetTime(
        lat: Double,
        lon: Double,
        onResult: (Date?) -> Unit
    ) {
        val url = "https://api.sunrise-sunset.org/json?lat=$lat&lng=$lon&formatted=0"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SafePathApp", "Sun API failed", e)
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { json ->
                    try {
                        val results = JSONObject(json).getJSONObject("results")
                        val sunsetUtc = results.getString("sunset")

                        // Convert ISO UTC → Date (local)
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
                        sdf.timeZone = TimeZone.getTimeZone("UTC")

                        val dateUtc = sdf.parse(sunsetUtc)
                        onResult(dateUtc)

                    } catch (e: Exception) {
                        Log.e("SafePathApp", "Parse error", e)
                        onResult(null)
                    }
                } ?: onResult(null)
            }
        })
    }

    private fun setupShakeToSOS() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            Log.w(TAG, "No accelerometer found — shake-to-SOS disabled")
            return
        }

        val SHAKE_THRESHOLD = 2.7f
        val SHAKE_COUNT = 3
        val SHAKE_WINDOW_MS = 1500L

        var shakeCount = 0
        var firstShakeAt = 0L

        shakeListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gX = event.values[0] / SensorManager.GRAVITY_EARTH
                val gY = event.values[1] / SensorManager.GRAVITY_EARTH
                val gZ = event.values[2] / SensorManager.GRAVITY_EARTH
                val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)

                if (gForce > SHAKE_THRESHOLD) {
                    val now = System.currentTimeMillis()
                    if (shakeCount == 0) firstShakeAt = now
                    if (now - firstShakeAt > SHAKE_WINDOW_MS) {
                        shakeCount = 0
                        firstShakeAt = now
                    }
                    shakeCount++
                    if (shakeCount >= SHAKE_COUNT) {
                        shakeCount = 0
                        runOnUiThread { showShakeSOSDialog() }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(
            shakeListener,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI
        )
    }

    private fun showShakeSOSDialog() {
        if (isFinishing || isDestroyed) return
        if (isSosDialogShowing) return  // ← blocks re-entry

        isSosDialogShowing = true

        val handler = Handler(mainLooper)
        var autoTrigger: Runnable? = null

        val dialog = AlertDialog.Builder(this)
            .setTitle("🚨 SOS in 3 seconds…")
            .setMessage("Shake detected.\nSending SOS to your trusted contacts automatically.\n\nTap Cancel to stop.")
            .setNegativeButton("Cancel") { _, _ ->
                handler.removeCallbacks(autoTrigger!!)
                isSosDialogShowing = false  // ← reset on cancel
            }
            .setCancelable(false)
            .create()

        autoTrigger = Runnable {
            if (!isFinishing && !isDestroyed) {
                dialog.dismiss()
                isSosDialogShowing = false  // ← reset after firing
                triggerSOS()
            }
        }

        handler.postDelayed(autoTrigger!!, 3000)
        dialog.show()
    }

    private fun setupFearAnomalyDetection() {
        anomalySensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = anomalySensorManager
            ?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            Log.w(TAG, "No accelerometer — fear detection disabled")
            return
        }

        val movementHistory = ArrayDeque<Float>(15)

        anomalyListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gX = event.values[0] / SensorManager.GRAVITY_EARTH
                val gY = event.values[1] / SensorManager.GRAVITY_EARTH
                val gZ = event.values[2] / SensorManager.GRAVITY_EARTH
                val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)

                // Rolling window of 15 readings
                if (movementHistory.size >= 15) movementHistory.removeFirst()
                movementHistory.addLast(gForce)
                if (movementHistory.size < 15) return

                // Variance — low variance = phone is truly still
                val mean     = movementHistory.average()
                val variance = movementHistory.map {
                    (it - mean) * (it - mean)
                }.average()

                val isStill = variance < 0.002

                val now = System.currentTimeMillis()
                val cooldownPassed = now - lastAnomalyAlert > 30_000L  // 30s between alerts

                if (isStill) {
                    if (stationarySince == 0L) stationarySince = now
                    val frozenFor = now - stationarySince

                    // Still for 3 seconds → fire check-in
                    if (frozenFor >= 10_000L && cooldownPassed) {
                        lastAnomalyAlert = now
                        stationarySince  = 0L
                        runOnUiThread { showFearCheckInDialog() }
                    }
                } else {
                    stationarySince = 0L  // reset as soon as phone moves
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        anomalySensorManager?.registerListener(
            anomalyListener,
            accelerometer,
            SensorManager.SENSOR_DELAY_NORMAL  // ~5 readings/sec, battery friendly
        )
    }

    private fun showFearCheckInDialog() {
        if (isFinishing || isDestroyed) return

        // Vibrate once to alert user
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
        }

        val handler  = Handler(mainLooper)
        var autoSOS: Runnable? = null
        var secondsLeft = 10

        val dialog = AlertDialog.Builder(this)
            .setTitle("🔍 Are you okay?")
            .setMessage("You've been still for a few seconds.\n\nSOS will be sent in 10 seconds if you don't respond.")
            .setPositiveButton("✅ I'm Fine") { _, _ ->
                handler.removeCallbacks(autoSOS!!)
            }
            .setNegativeButton("🚨 Send SOS Now") { _, _ ->
                handler.removeCallbacks(autoSOS!!)
                triggerSOS()
            }
            .setCancelable(false)
            .create()

        // Countdown — updates message every second
        val countdownRunnable = object : Runnable {
            override fun run() {
                if (!isFinishing && !isDestroyed && dialog.isShowing) {
                    secondsLeft--
                    dialog.setMessage(
                        "You've been still for a few seconds.\n\nSOS will be sent in $secondsLeft seconds if you don't respond."
                    )
                    if (secondsLeft > 0) handler.postDelayed(this, 1000)
                }
            }
        }

        autoSOS = Runnable {
            if (!isFinishing && !isDestroyed) {
                handler.removeCallbacks(countdownRunnable)
                dialog.dismiss()
                triggerSOS()
            }
        }

        dialog.show()
        handler.postDelayed(countdownRunnable, 1000)   // start countdown display
        handler.postDelayed(autoSOS!!,        10_000)  // auto-fire SOS at 10s
    }
    // ════════════════════════════════════════════════════════════════════════
    // COMPOSITE SAFETY SCORING  — background thread only
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Weighted composite risk score (0.0 = safe, 10.0 = dangerous).
     *   Accident density  30%
     *   Police proximity  20%
     *   Crime / AI risk   25%
     *   Crowd density     10%
     *   Road quality      15%
     *
     * Called once per route inside processRouteResponse() which already runs
     * on OkHttp's background thread. Result is cached in RouteInfo.compositeScore
     * so updateSafetyCard() never repeats the work.
     */
    private fun calculateCompositeSafetyScore(
        pathPoints: List<GeoPoint>,
        roadData: RoadData,
        tfRisk: Double?
    ): CompositeSafetyScore {

        // ── 1. Accident density (30%) ─────────────────────────────────────
        loadCrimeDataIfNeeded()
        var accidentMatches = 0

        if (crimeLats.isNotEmpty() && pathPoints.isNotEmpty()) {
            val stride = maxOf(1, pathPoints.size / ROUTE_SAMPLE_STRIDE)
            val latBuf = ACCIDENT_RADIUS_M / 111_000.0
            val midLat = pathPoints.map { it.latitude }.average()
            val lonBuf = ACCIDENT_RADIUS_M / (111_000.0 * cos(Math.toRadians(midLat)))

            val rMinLat = pathPoints.minOf { it.latitude } - latBuf
            val rMaxLat = pathPoints.maxOf { it.latitude } + latBuf
            val rMinLon = pathPoints.minOf { it.longitude } - lonBuf
            val rMaxLon = pathPoints.maxOf { it.longitude } + lonBuf

            // Bounding-box pre-filter — avoids Haversine on distant points
            val candidateIdx = crimeLats.indices.filter { j ->
                crimeLats[j] in rMinLat..rMaxLat && crimeLons[j] in rMinLon..rMaxLon
            }

            val matched = mutableSetOf<Int>()
            for (i in pathPoints.indices step stride) {
                val rp = pathPoints[i]
                for (j in candidateIdx) {
                    if (matched.contains(j)) continue
                    if (abs(crimeLats[j] - rp.latitude) > latBuf * 1.1 ||
                        abs(crimeLons[j] - rp.longitude) > lonBuf * 1.1
                    ) continue
                    if (haversineMeters(rp.latitude, rp.longitude, crimeLats[j], crimeLons[j])
                        <= ACCIDENT_RADIUS_M
                    ) matched.add(j)
                }
            }
            accidentMatches = matched.size
        }
        val accidentScore = (accidentMatches.toDouble() / ACCIDENT_NORMALISE_AT * 10.0)
            .coerceIn(0.0, 10.0)

        // ── 2. Police proximity (20%) ─────────────────────────────────────
        val policeScore = when {
            roadData.isMajorRoad -> 1.5
            roadData.roadType.contains("residential", ignoreCase = true) -> 5.5
            else -> 7.0
        }

        // ── 3. Crime / AI risk (25%) ──────────────────────────────────────
        val crimeScore = (tfRisk ?: run {
            var r = 5.0
            r += if (roadData.isPaved) -1.0 else 2.0
            r += if (roadData.isMajorRoad) -1.5 else 1.0
            r
        }).coerceIn(0.0, 10.0)

        // ── 4. Crowd density (10%) ────────────────────────────────────────
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val crowdBase = if (roadData.isMajorRoad) 5.0 else 3.0
        val rawCrowd = (crowdBase + when (hour) {
            in 7..9 -> 2.0
            in 17..19 -> 2.5
            in 22..23, in 0..5 -> -3.0
            else -> 0.0
        }).coerceIn(0.0, 10.0)
        // Night: busy = riskier; Day: busy = safer
        val crowdScore = if (hour in 20..23 || hour in 0..5) rawCrowd else (10.0 - rawCrowd)

        // ── 5. Road quality (15%) ─────────────────────────────────────────
        val roadScore = when {
            roadData.isPaved && roadData.isMajorRoad -> 1.0
            roadData.isPaved && !roadData.isMajorRoad -> 3.5
            !roadData.isPaved && roadData.isMajorRoad -> 5.0
            else -> 8.0
        }

        val total = (accidentScore * 0.30 +
                policeScore * 0.20 +
                crimeScore * 0.25 +
                crowdScore * 0.10 +
                roadScore * 0.15).coerceIn(0.0, 10.0)

        Log.d(
            TAG, "Composite: acc=${"%.1f".format(accidentScore)} " +
                    "police=${"%.1f".format(policeScore)} crime=${"%.1f".format(crimeScore)} " +
                    "crowd=${"%.1f".format(crowdScore)} road=${"%.1f".format(roadScore)} → ${
                        "%.2f".format(
                            total
                        )
                    }"
        )

        return CompositeSafetyScore(
            total,
            accidentScore,
            policeScore,
            crimeScore,
            crowdScore,
            roadScore
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // DISTANCE HELPERS
    // ════════════════════════════════════════════════════════════════════════

    /** Haversine with primitive args — avoids GeoPoint allocation in tight loops. */
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun distanceInMeters(p1: GeoPoint, p2: GeoPoint): Double =
        haversineMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)

    // ════════════════════════════════════════════════════════════════════════
    // ACCIDENT COUNT — async, updates UI card only, does not affect routing
    // ════════════════════════════════════════════════════════════════════════

    private fun countNearbyAccidentsAsync(routePoints: List<GeoPoint>, onResult: (Int) -> Unit) {
        Thread {
            var result = 0
            try {
                loadCrimeDataIfNeeded()
                if (crimeLats.isEmpty()) {
                    runOnUiThread { onResult(0) }; return@Thread
                }

                val stride = maxOf(1, routePoints.size / 200)
                val latBuf = ACCIDENT_RADIUS_M / 111_000.0
                val midLat = routePoints.map { it.latitude }.average()
                val lonBuf = ACCIDENT_RADIUS_M / (111_000.0 * cos(Math.toRadians(midLat)))

                val rMinLat = routePoints.minOf { it.latitude } - latBuf
                val rMaxLat = routePoints.maxOf { it.latitude } + latBuf
                val rMinLon = routePoints.minOf { it.longitude } - lonBuf
                val rMaxLon = routePoints.maxOf { it.longitude } + lonBuf

                val candidateIdx = crimeLats.indices.filter { j ->
                    crimeLats[j] in rMinLat..rMaxLat && crimeLons[j] in rMinLon..rMaxLon
                }

                val matched = mutableSetOf<Int>()
                for (i in routePoints.indices step stride) {
                    val rp = routePoints[i]
                    for (j in candidateIdx) {
                        if (matched.contains(j)) continue
                        if (abs(crimeLats[j] - rp.latitude) > latBuf * 1.1 ||
                            abs(crimeLons[j] - rp.longitude) > lonBuf * 1.1
                        ) continue
                        if (haversineMeters(rp.latitude, rp.longitude, crimeLats[j], crimeLons[j])
                            <= ACCIDENT_RADIUS_M
                        ) matched.add(j)
                    }
                }
                result = matched.size
            } catch (e: Exception) {
                Log.e(TAG, "Accident count error: ${e.message}")
            }
            runOnUiThread { onResult(result) }
        }.start()
    }

    // ════════════════════════════════════════════════════════════════════════
    // SOS
    // ════════════════════════════════════════════════════════════════════════

    private fun showContactsList() {
        AlertDialog.Builder(this)
            .setTitle("Trusted Contacts")
            .setItems(getContacts().toTypedArray(), null)
            .setPositiveButton("Add New") { _, _ -> showAddContactDialog() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSOSConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Emergency SOS")
            .setMessage("Send your live location to trusted contacts and call emergency services?")
            .setPositiveButton("SEND SOS") { _, _ -> triggerSOS() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun triggerSOS() {
        if (getContacts().isEmpty()) {
            Toast.makeText(this, "Please add at least one SOS contact", Toast.LENGTH_LONG)
                .show(); return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT)
                .show(); return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                sendSOSMessage(location.latitude, location.longitude); callEmergency()
            } else Toast.makeText(this, "Unable to fetch location", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddContactDialog() {
        val input = EditText(this).apply { hint = "Enter phone number" }
        AlertDialog.Builder(this)
            .setTitle("Add Trusted Contact")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val number = input.text.toString().trim()
                if (number.isNotEmpty()) {
                    val contacts = getContacts().toMutableSet().also { it.add(number) }
                    getSharedPreferences("sos_prefs", MODE_PRIVATE).edit()
                        .putStringSet("contacts", contacts).apply()
                    Toast.makeText(this, "Contact added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getContacts(): Set<String> =
        getSharedPreferences("sos_prefs", MODE_PRIVATE).getStringSet("contacts", emptySet())
            ?: emptySet()

    private fun sendSOSMessage(lat: Double, lon: Double) {
        val trustedContacts = getContacts()
        if (trustedContacts.isEmpty()) {
            Toast.makeText(this, "No trusted contacts added", Toast.LENGTH_SHORT).show(); return
        }
        val message =
            "🚨 SAFE PATH SOS 🚨\nI am in danger and need help.\nLocation:\nhttps://maps.google.com/?q=$lat,$lon"
        val smsManager = SmsManager.getDefault()
        trustedContacts.forEach { contact ->
            try {
                smsManager.sendMultipartTextMessage(
                    contact,
                    null,
                    smsManager.divideMessage(message),
                    null,
                    null
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to send SMS to $contact", Toast.LENGTH_SHORT).show()
            }
        }
        Toast.makeText(this, "SOS sent to all contacts", Toast.LENGTH_LONG).show()
    }

    private fun callEmergency() {
        startActivity(Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:") })
    }

    // ════════════════════════════════════════════════════════════════════════
    // SAFE ZONE MARKERS
    // ════════════════════════════════════════════════════════════════════════

    private var allSafeZones: List<SafeZone> = emptyList()
    private var lastUpdateTime = 0L

    @RequiresApi(Build.VERSION_CODES.O)
    private fun loadSafeZoneMarkers(routePoints: List<GeoPoint>) {
        Thread {
            val zones = SafeZoneFetcher.fetchAlongRoute(routePoints)

            if (zones.isEmpty()) {
                runOnUiThread {
                    Log.d(TAG, "No safe zones found")
                    clearSafeZoneMarkers()
                }
                return@Thread
            }

            runOnUiThread {
                // ✅ Store all zones (no filtering here)
                allSafeZones = zones

                Log.d(TAG, "Total zones fetched: ${zones.size}")

                updateNearestSafeZones(fallbackAnchor = routePoints.firstOrNull())

                findViewById<MaterialButton>(R.id.btn_toggle_safe_zones)?.visibility = View.VISIBLE
            }

        }.start()
    }

//    @RequiresApi(Build.VERSION_CODES.O)
//    private fun loadSafeZoneMarkers(routePoints: List<GeoPoint>) {
//        Thread {
//            val zones = SafeZoneFetcher.fetchAlongRoute(routePoints)
//            runOnUiThread {
//                if (zones.isEmpty()) { clearSafeZoneMarkers(); return@runOnUiThread }
//                allSafeZones = zones
//                updateNearestSafeZones()
//                findViewById<MaterialButton>(R.id.btn_toggle_safe_zones)?.visibility = View.VISIBLE
//            }
//        }.start()
//    }

    //    private fun loadSafeZoneMarkers(routePoints: List<GeoPoint>) {
//        if (routePoints.isEmpty()) return  // ← add this guard
//
//        Thread {
//            try {
//                val zones = SafeZoneFetcher.fetchAlongRoute(routePoints)
//                Log.d(TAG, "SafeZone: fetched ${zones.size} zones")
//
//                if (zones.isEmpty()) {
//                    runOnUiThread { clearSafeZoneMarkers() }
//                    return@Thread
//                }
//
//                runOnUiThread {
//                    allSafeZones = zones
//                    val userLoc = currentLocation
//                    val anchor = routePoints.first()  // safe now — guarded above
//
//                    val zonesToShow = if (userLoc != null) {
//                        val nearby = zones
//                            .filter { userLoc.distanceToAsDouble(it.location) <= 3000 }
//                            .sortedBy { userLoc.distanceToAsDouble(it.location) }
//                            .take(3)
//                        nearby.ifEmpty {
//                            zones.sortedBy { anchor.distanceToAsDouble(it.location) }.take(5)
//                        }
//                    } else {
//                        zones.sortedBy { anchor.distanceToAsDouble(it.location) }.take(5)
//                    }
//
//                    Log.d(TAG, "SafeZone: drawing ${zonesToShow.size} markers")
//                    clearSafeZoneMarkers()
//                    zonesToShow.forEach { addSafeZoneMarker(it) }
//                    map.invalidate()
//                    findViewById<MaterialButton>(R.id.btn_toggle_safe_zones)?.visibility = View.VISIBLE
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "SafeZone crash: ${e.message}", e)
//                runOnUiThread {
//                    Toast.makeText(this, "Safe zone error: ${e.message}", Toast.LENGTH_LONG).show()
//                }
//            }
//        }.start()
//    }
    private fun updateNearestSafeZones(fallbackAnchor: GeoPoint? = null) {
        val anchor = currentLocation ?: fallbackAnchor ?: return
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < 3000) return
        lastUpdateTime = now

        val nearestZones = allSafeZones
            .filter { anchor.distanceToAsDouble(it.location) <= 3000 }
            .sortedBy { anchor.distanceToAsDouble(it.location) }
            .take(3)

        val zonesToShow = nearestZones.ifEmpty {
            allSafeZones.sortedBy { anchor.distanceToAsDouble(it.location) }.take(5)
        }

        clearSafeZoneMarkers()
        zonesToShow.forEach { addSafeZoneMarker(it) }
        map.invalidate()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enableLocationTracking() {
        if (::myLocationOverlay.isInitialized) {
            myLocationOverlay.enableMyLocation()
            myLocationOverlay.enableFollowLocation()
        }
        val request = com.google.android.gms.location.LocationRequest.create().apply {
            priority = com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = 3000
            fastestInterval = 2000
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)
        }
    }


    @RequiresApi(Build.VERSION_CODES.O)
    @RequiresPermission(Manifest.permission.VIBRATE)
    private val locationCallback = object : com.google.android.gms.location.LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return

            // 1. Filter by Accuracy: 25m is good, 15m is better for urban areas
            if (location.accuracy > 20) return

            // 2. Filter by Speed: If the user is stationary, don't recalculate
            // This prevents the phone from vibrating while you're just standing at a red light
            if (location.speed < 0.5f) return

            val userGeoPoint = GeoPoint(location.latitude, location.longitude)
            currentLocation = userGeoPoint

            runOnUiThread {
                if (safestRoute != null && !isRouteFetching) {
                    if (isUserOffSafestRoute(userGeoPoint)) {
                        triggerDeviatedAlert()
                    }
                }
                updateNearestSafeZones()
            }
        }
    }

    private fun addSafeZoneMarker(zone: SafeZone) {
        val marker = Marker(map).apply {
            position = zone.location; title = zone.name; snippet = zone.type.label()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_safe_zone)
        }
        safeZoneMarkers.add(marker)
        map.overlays.add(marker)
    }

    private fun clearSafeZoneMarkers() {
        safeZoneMarkers.forEach { map.overlays.remove(it) }
        safeZoneMarkers.clear()
        map.invalidate()
    }

    private fun toggleSafeZoneVisibility() {
        safeZonesVisible = !safeZonesVisible
        safeZoneMarkers.forEach { it.isEnabled = safeZonesVisible }
        map.invalidate()
        findViewById<MaterialButton>(R.id.btn_toggle_safe_zones)?.text =
            if (safeZonesVisible) "Hide Safe Zones" else "Show Safe Zones"
    }

    // ════════════════════════════════════════════════════════════════════════
    // DESTINATION NEWS TOGGLE
    // ════════════════════════════════════════════════════════════════════════

    private fun setupDestinationNewsToggle() {
        val toggle = findViewById<SwitchMaterial>(R.id.switch_destination_news)
        val label = findViewById<TextView>(R.id.tv_news_location_label)

        toggle?.setOnCheckedChangeListener { _, isChecked ->
            isDestinationNewsEnabled = isChecked
            if (isChecked) {
                if (currentDestinationName.isEmpty()) {
                    toggle.isChecked = false
                    Toast.makeText(this, "Search for a destination first", Toast.LENGTH_SHORT)
                        .show()
                    return@setOnCheckedChangeListener
                }
                val shortName = currentDestinationName.split(",").firstOrNull()?.trim()
                    ?: currentDestinationName
                label?.text = "Showing news for: $shortName"
                loadSafetyNews(locationOverride = shortName)
            } else {
                label?.text = if (currentDestinationName.isNotEmpty())
                    "Tap to filter by destination area" else "Set a destination to enable"
                loadSafetyNews()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // NEWS
    // ════════════════════════════════════════════════════════════════════════

    private fun loadSafetyNews(locationOverride: String? = null) {
        val newsContainer = findViewById<LinearLayout>(R.id.news_container)
        val progressBar = findViewById<ProgressBar>(R.id.news_progress_bar)
        val btnReload = findViewById<MaterialButton>(R.id.btn_refresh_crime)

        progressBar?.visibility = View.VISIBLE
        btnReload?.isEnabled = false
        newsContainer?.removeAllViews()

        val locationQuery = locationOverride ?: "Bengaluru Bangalore"
        Toast.makeText(
            this,
            if (locationOverride != null) "Fetching safety news for $locationOverride..."
            else "Fetching Bangalore safety alerts...",
            Toast.LENGTH_SHORT
        ).show()

        fetchSafetyNewsFromAPI(locationQuery) { newsItems ->
            runOnUiThread {
                progressBar?.visibility = View.GONE
                btnReload?.isEnabled = true
                if (newsItems.isEmpty()) {
                    newsContainer?.addView(TextView(this).apply {
                        text =
                            "No recent safety alerts found for ${locationOverride ?: "Bangalore"}."
                        textSize = 14f; setTextColor(Color.GRAY); setPadding(16, 16, 16, 16)
                    })
                } else {
                    newsItems.forEach { newsContainer?.addView(createNewsItemView(it)) }
                    Toast.makeText(
                        this,
                        "${newsItems.size} alerts loaded for ${locationOverride ?: "Bangalore"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private data class NewsItem(
        val title: String,
        val description: String,
        val timeAgo: String,
        val severity: String
    )

    private fun fetchSafetyNewsFromAPI(query: String, callback: (List<NewsItem>) -> Unit) {
        Thread {
            val newsList = ArrayList<NewsItem>()
            try {
                val encodedQuery = URLEncoder.encode("$query safety accident crime", "UTF-8")
                val urlString =
                    "https://news.google.com/rss/search?q=$encodedQuery&hl=en-IN&gl=IN&ceid=IN:en"
                val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 10000; requestMethod = "GET"
                }
                if (connection.responseCode == HttpURLConnection.HTTP_OK)
                    newsList.addAll(parseRSS(connection.inputStream))
                if (newsList.isEmpty())
                    newsList.add(
                        NewsItem(
                            "No recent alerts",
                            "Could not fetch news at this time.",
                            "Just now",
                            "low"
                        )
                    )
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching news", e)
                newsList.add(
                    NewsItem(
                        "Connection Error",
                        "Check your internet connection.",
                        "--",
                        "low"
                    )
                )
            }
            runOnUiThread { callback(newsList) }
        }.start()
    }

    private fun parseRSS(inputStream: InputStream): List<NewsItem> {
        val items = ArrayList<NewsItem>()
        try {
            val xpp = XmlPullParserFactory.newInstance().newPullParser()
                .apply { setInput(inputStream, "UTF_8") }
            var eventType = xpp.eventType
            var insideItem = false
            var title = "";
            var description = "";
            var pubDate = ""

            while (eventType != XmlPullParser.END_DOCUMENT && items.size < 5) {
                if (eventType == XmlPullParser.START_TAG) {
                    if (xpp.name.equals("item", ignoreCase = true)) insideItem = true
                    else if (insideItem) when (xpp.name.lowercase(Locale.ROOT)) {
                        "title" -> title = xpp.nextText().trim()
                        "description" -> description = stripHtml(xpp.nextText().trim())
                        "pubdate" -> pubDate = xpp.nextText().trim()
                    }
                } else if (eventType == XmlPullParser.END_TAG && xpp.name.equals(
                        "item",
                        ignoreCase = true
                    )
                ) {
                    insideItem = false
                    if (title.isNotEmpty()) {
                        val cleanTitle = if (title.contains("-")) title.substringBeforeLast("-")
                            .trim() else title
                        items.add(
                            NewsItem(
                                cleanTitle,
                                description,
                                calculateTimeAgo(pubDate),
                                calculateSeverity(title, description)
                            )
                        )
                    }
                    title = ""; description = ""; pubDate = ""
                }
                eventType = xpp.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "XML Parsing Error", e)
        }
        return items
    }

    private fun stripHtml(html: String): String =
        android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString()

    private fun calculateSeverity(title: String, desc: String): String {
        val text = "$title $desc".lowercase(Locale.ROOT)
        return when {
            text.contains("murder") || text.contains("kill") ||
                    text.contains("dead") || text.contains("rape") -> "high"

            text.contains("accident") || text.contains("injury") ||
                    text.contains("crash") || text.contains("robbery") -> "medium"

            else -> "low"
        }
    }

    private fun calculateTimeAgo(dateString: String): String {
        return try {
            val date =
                SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH).parse(dateString)
                    ?: return "Recently"
            val diff = Date().time - date.time
            when {
                diff / (60 * 1000) < 60 -> "${diff / (60 * 1000)} mins ago"
                diff / (3600 * 1000) < 24 -> "${diff / (3600 * 1000)} hours ago"
                else -> "${diff / (86400 * 1000)} days ago"
            }
        } catch (e: Exception) {
            "Recently"
        }
    }

    private fun createNewsItemView(newsItem: NewsItem): View {
        val newsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, 16) }
        }
        val severityColor = when (newsItem.severity) {
            "high" -> Color.parseColor("#F44336")
            "medium" -> Color.parseColor("#FF9800")
            else -> Color.parseColor("#4CAF50")
        }
        val titleLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleLayout.addView(View(this).apply {
            setBackgroundColor(severityColor)
            layoutParams = LinearLayout.LayoutParams(12, 12).also { it.setMargins(0, 8, 12, 0) }
        })
        titleLayout.addView(TextView(this).apply {
            text = newsItem.title; textSize = 16f; setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        newsCard.addView(titleLayout)
        newsCard.addView(TextView(this).apply {
            text = newsItem.description; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(
            24,
            8,
            0,
            0
        )
        })
        newsCard.addView(TextView(this).apply {
            text = newsItem.timeAgo; textSize = 12f; setTextColor(Color.GRAY); setPadding(
            24,
            4,
            0,
            0
        )
        })
        return newsCard
    }

    // ════════════════════════════════════════════════════════════════════════
    // GEOCODING
    // ════════════════════════════════════════════════════════════════════════

    private fun findDestinationCoordinates(query: String) {
        Toast.makeText(this, "Searching for \"$query\"...", Toast.LENGTH_SHORT).show()
        val encodedQuery = URLEncoder.encode("$query, Bangalore", "UTF-8")
        val url = "https://api.openrouteservice.org/geocode/search?" +
                "api_key=$ORS_API_KEY&text=$encodedQuery&size=5" +
                "&boundary.circle.lat=$BANGALORE_LAT&boundary.circle.lon=$BANGALORE_LON&boundary.circle.radius=50" +
                "&focus.point.lat=$BANGALORE_LAT&focus.point.lon=$BANGALORE_LON"

        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Connection error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Search failed (Error ${response.code})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return
                }
                response.body?.string()?.let { jsonData ->
                    try {
                        val features = JSONObject(jsonData).getJSONArray("features")
                        if (features.length() > 0) {
                            val coords = features.getJSONObject(0).getJSONObject("geometry")
                                .getJSONArray("coordinates")
                            val locationName = features.getJSONObject(0).getJSONObject("properties")
                                .optString("label", "Destination")
                            runOnUiThread {
                                handleDestinationFound(
                                    coords.getDouble(1),
                                    coords.getDouble(0),
                                    locationName
                                )
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Location not found. Try a more specific name.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Error processing search results",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })
    }

    private fun handleDestinationFound(lat: Double, lon: Double, name: String) {
        currentDestination = GeoPoint(lat, lon)
        currentDestinationName = name

        destinationMarker?.let { map.overlays.remove(it) }
        destinationMarker = Marker(map).apply {
            position = currentDestination; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = name; snippet = "Tap 'Plan Route' to navigate"
        }
        map.overlays.add(destinationMarker)
        map.controller.animateTo(currentDestination)
        map.controller.setZoom(ROUTE_ZOOM)
        map.invalidate()

        Toast.makeText(this, "Destination set: $name", Toast.LENGTH_SHORT).show()

        val shortName = name.split(",").firstOrNull()?.trim() ?: name
        findViewById<TextView>(R.id.tv_news_location_label)?.text = "Tap to filter by: $shortName"
        findViewById<SwitchMaterial>(R.id.switch_destination_news)?.isEnabled = true

        showTransportModeDialog()
    }

    // ════════════════════════════════════════════════════════════════════════
    // ROUTING
    // ════════════════════════════════════════════════════════════════════════

    private fun showTransportModeDialog() {
        if (currentDestination == null) {
            Toast.makeText(this, "No destination set", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose Transport Mode")
            .setItems(arrayOf("Walking 🚶", "Bike 🚴", "Car 🚗")) { _, which ->
                // Save the mode so the auto-rerouter knows what to use
                activeTransportMode = when (which) {
                    0 -> "Walking"
                    1 -> "Bike"
                    else -> "Car"
                }
                planRoute(currentDestination!!, activeTransportMode)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    @RequiresApi(Build.VERSION_CODES.O)
    @RequiresPermission(Manifest.permission.VIBRATE)

    private fun triggerDeviatedAlert() {
        val currentTime = System.currentTimeMillis()

        // If we are currently fetching a route, or just fetched one in the last 15 seconds, STALL.
        if (isRouteFetching || (currentTime - lastRerouteTime < 15000)) return

        lastRerouteTime = currentTime
        // ... rest of your vibration and planRoute code ...


        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Check if vibrator exists and vibrate 3 times
        if (vibrator.hasVibrator()) {
            val timings = longArrayOf(0, 200, 200, 200, 200, 200) // Pulse pattern
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(600)
            }
        }

        Toast.makeText(
            this,
            "⚠️ Off-route! Recalculating $activeTransportMode path...",
            Toast.LENGTH_SHORT
        ).show()

        currentDestination?.let { destination ->
            planRoute(destination, activeTransportMode)
        }
    }

    private fun getDynamicThreshold(): Double {
        return when (activeTransportMode) {
            "Car" -> 150.0   // Increased for speed
            "Bike" -> 100.0
            else -> 100.0    // Increased to 100m to stop "ghost" vibrations
        }
    }

    private fun isUserOffSafestRoute(userLoc: GeoPoint): Boolean {
        val routePoints = safestRoute?.points ?: return false
        if (routePoints.size < 2) return false

        var minDistance = Double.MAX_VALUE

        // Check distance to the closest line segment
        for (i in 0 until routePoints.size - 1) {
            val dist = distanceToSegment(userLoc, routePoints[i], routePoints[i + 1])
            if (dist < minDistance) minDistance = dist

            // Optimization: If we're already close enough, we're "On Route"
            if (minDistance < 20.0) return false
        }

        // THIS IS WHERE YOU USE THE FUNCTION:
        val threshold = getDynamicThreshold()

        Log.d(TAG, "Off-route check: Distance=$minDistance, Threshold=$threshold")
        return minDistance > threshold
    }

    // Helper to find the shortest distance from a point to a line segment
    private fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val x = p.latitude
        val y = p.longitude
        val x1 = a.latitude
        val y1 = a.longitude
        val x2 = b.latitude
        val y2 = b.longitude

        val dx = x2 - x1
        val dy = y2 - y1

        if (dx == 0.0 && dy == 0.0) return distanceInMeters(p, a)

        val t = ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)

        return when {
            t < 0 -> distanceInMeters(p, a)
            t > 1 -> distanceInMeters(p, b)
            else -> {
                val closestPoint = GeoPoint(x1 + t * dx, y1 + t * dy)
                distanceInMeters(p, closestPoint)
            }
        }
    }

    private fun planRoute(destination: GeoPoint, transportMode: String) {
        if (isRouteFetching) {
            Toast.makeText(this, "Already fetching route...", Toast.LENGTH_SHORT).show(); return
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT)
                .show(); return
        }
        Toast.makeText(this, "Getting your current location...", Toast.LENGTH_SHORT).show()
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) fetchORSRoute(
                    GeoPoint(location.latitude, location.longitude),
                    destination,
                    transportMode
                )
                else requestFreshLocationAndRoute(destination, transportMode)
            }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    "Could not get your location. Please enable GPS.",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun requestFreshLocationAndRoute(destination: GeoPoint, transportMode: String) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        Toast.makeText(this, "Acquiring GPS fix...", Toast.LENGTH_SHORT).show()
        val locationRequest = com.google.android.gms.location.LocationRequest.create().apply {
            priority = com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
            numUpdates = 1; interval = 0; fastestInterval = 0
        }
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    fusedLocationClient.removeLocationUpdates(this)
                    val location = result.lastLocation
                    if (location != null) fetchORSRoute(
                        GeoPoint(
                            location.latitude,
                            location.longitude
                        ), destination, transportMode
                    )
                    else runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Unable to get GPS location.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            mainLooper
        )
    }

    private fun fetchORSRoute(start: GeoPoint, end: GeoPoint, mode: String) {
        isRouteFetching = true
        Toast.makeText(this, "Fetching $mode routes...", Toast.LENGTH_SHORT).show()
        val profile = when (mode) {
            "Bike" -> "cycling-regular"; "Walking" -> "foot-walking"; else -> "driving-car"
        }
        makeORSRequest(start, end, mode, profile, withAlternatives = true)
    }

    private fun buildORSRequestBody(
        start: GeoPoint,
        end: GeoPoint,
        withAlternatives: Boolean
    ): RequestBody {
        val body = JSONObject().apply {
            put("coordinates", org.json.JSONArray().apply {
                put(org.json.JSONArray().apply { put(start.longitude); put(start.latitude) })
                put(org.json.JSONArray().apply { put(end.longitude); put(end.latitude) })
            })
            put(
                "extra_info",
                org.json.JSONArray().apply { put("surface"); put("waycategory"); put("steepness") })
            if (withAlternatives) put("alternative_routes", JSONObject().apply {
                put("target_count", 3); put("weight_factor", 1.6); put("share_factor", 0.6)
            })
            put("preference", "recommended"); put("geometry", true); put("instructions", false)
            put("radiuses", org.json.JSONArray().apply { put(500); put(500) })
        }
        return body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
    }

    private fun makeORSRequest(
        start: GeoPoint,
        end: GeoPoint,
        mode: String,
        profile: String,
        withAlternatives: Boolean
    ) {
        val request = Request.Builder()
            .url("https://api.openrouteservice.org/v2/directions/$profile/geojson")
            .addHeader("Authorization", ORS_API_KEY)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, application/geo+json")
            .post(buildORSRequestBody(start, end, withAlternatives))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isRouteFetching = false
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Network error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            @RequiresApi(Build.VERSION_CODES.O)
            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string()
                Log.d(TAG, "ORS [${response.code}]")
                when (response.code) {
                    200 -> {
                        isRouteFetching = false
                        bodyStr?.let {
                            try {
                                processRouteResponse(it, mode)
                            } catch (e: Exception) {
                                runOnUiThread {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Error processing route data",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }

                    400 -> {
                        if (withAlternatives) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Adjusting request, retrying...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            makeORSRequest(start, end, mode, profile, false)
                        } else {
                            isRouteFetching = false
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Destination unreachable for $mode.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    500 -> {
                        if (profile != "driving-car") {
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "No $mode route found. Trying car route...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            makeORSRequest(start, end, mode, "driving-car", withAlternatives)
                        } else {
                            isRouteFetching = false
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "No route found. Try a different destination.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    else -> {
                        isRouteFetching = false
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Route unavailable (Error ${response.code})",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        })
    }

    // ════════════════════════════════════════════════════════════════════════
    // PROCESS ROUTE RESPONSE
    // Heavy work (scoring, TFLite) runs on the OkHttp thread.
    // A single runOnUiThread call at the end updates all UI state.
    // ════════════════════════════════════════════════════════════════════════

    @RequiresApi(Build.VERSION_CODES.O)
    private fun processRouteResponse(jsonString: String, mode: String) {
        val features = JSONObject(jsonString).getJSONArray("features")
        if (features.length() == 0) {
            runOnUiThread { Toast.makeText(this, "No route found", Toast.LENGTH_SHORT).show() }
            return
        }

        data class ScoredRoute(val info: RouteInfo, val compositeScore: Double)

        val scoredRoutes = mutableListOf<ScoredRoute>()

        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
            val pathPoints = ArrayList<GeoPoint>(coordinates.length())
            for (j in 0 until coordinates.length()) {
                val coord = coordinates.getJSONArray(j)
                pathPoints.add(GeoPoint(coord.getDouble(1), coord.getDouble(0)))
            }

            val properties = feature.getJSONObject("properties")
            val summary = properties.optJSONObject("summary")
            val roadData = parseRoadData(properties.optJSONObject("extras"))

            // TFLite inference — once per route, on the background thread
            val tfRisk: Double? = if (isTfliteReady) {
                try {
                    sanitizeRisk(tfLiteRiskPredictor.calculateRouteRisk(pathPoints))
                } catch (e: Exception) {
                    Log.w(TAG, "TFLite error route $i", e); null
                }
            } else null

            // Composite score computed once and cached in RouteInfo
            val composite = calculateCompositeSafetyScore(pathPoints, roadData, tfRisk)

            scoredRoutes.add(
                ScoredRoute(
                    RouteInfo(
                        points = pathPoints,
                        distance = summary?.optDouble("distance", 0.0) ?: 0.0,
                        duration = summary?.optDouble("duration", 0.0) ?: 0.0,
                        roadData = roadData,
                        compositeScore = composite,
                        sunsetTime = null // Initialized as null, fetched below
                    ),
                    composite.totalScore
                )
            )
        }

        val fastest = scoredRoutes.minByOrNull { it.info.duration }?.info
        val safest = scoredRoutes.minByOrNull { it.compositeScore }?.info
        val highestRisk = scoredRoutes.maxByOrNull { it.compositeScore }?.info

        // Single UI dispatch
        runOnUiThread {
            fastestRoute = fastest
            safestRoute = if (scoredRoutes.size == 1) fastest else safest
            highestRiskRoute = if (scoredRoutes.size == 1) fastest else highestRisk

            // ── TRIGGER SUNSET FETCH ──
            // Fetch sunset for the destination of the primary route
            fastestRoute?.points?.lastOrNull()?.let { dest ->
                fetchSunsetTime(dest.latitude, dest.longitude) { sunsetDate ->
                    runOnUiThread {
                        // Update all route instances with the fetched sunset
                        fastestRoute = fastestRoute?.copy(sunsetTime = sunsetDate)
                        safestRoute = safestRoute?.copy(sunsetTime = sunsetDate)
                        highestRiskRoute = highestRiskRoute?.copy(sunsetTime = sunsetDate)

                        // Refresh the card immediately to show the Daylight/Night logic
                        val currentDisplay = fastestRoute
                        if (currentDisplay != null) {
                            updateSafetyCard(currentDisplay, mode)
                        }
                    }
                }
            }

            // Reset the reroute timer to give GPS time to settle
            lastRerouteTime = System.currentTimeMillis()

            fastestRoute?.let {
                drawRoute(it.points, RouteType.FASTEST)
                loadSafeZoneMarkers(it.points)
                updateSafetyCard(it, mode) // Initial call (shows basic stats while sunset loads)
                highlightRouteButton(RouteType.FASTEST)
            }

            Toast.makeText(
                this,
                if (scoredRoutes.size > 1)
                    "${scoredRoutes.size} routes found — tap buttons for details"
                else "Only 1 route found for this trip",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun parseRoadData(extrasObj: JSONObject?): RoadData {

        var surfaceType = "unknown"
        var roadType = "unknown"

        // Extract most frequent surface
        extrasObj?.optJSONObject("surface")?.optJSONArray("summary")?.let { arr ->
            val counts = mutableMapOf<String, Double>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val value = obj.optString("value", "unknown")
                val dist = obj.optDouble("distance", 0.0)
                counts[value] = counts.getOrDefault(value, 0.0) + dist
            }

            surfaceType = counts.maxByOrNull { it.value }?.key ?: "unknown"
        }

        // Extract most frequent road type
        extrasObj?.optJSONObject("waycategory")?.optJSONArray("summary")?.let { arr ->
            val counts = mutableMapOf<String, Double>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val value = obj.optString("value", "unknown")
                val dist = obj.optDouble("distance", 0.0)
                counts[value] = counts.getOrDefault(value, 0.0) + dist
            }

            roadType = counts.maxByOrNull { it.value }?.key ?: "unknown"
        }

        // Better paved detection
        val isPaved = listOf("paved", "asphalt", "concrete", "paving_stones")
            .any { surfaceType.contains(it, ignoreCase = true) }

        // Better major road detection
        val isMajorRoad = listOf("motorway", "trunk", "primary", "secondary")
            .any { roadType.contains(it, ignoreCase = true) }

        return RoadData(surfaceType, roadType, isPaved, isMajorRoad)
    }

    // ════════════════════════════════════════════════════════════════════════
    // SAFETY CARD — reads cached composite score, no re-computation
    // ════════════════════════════════════════════════════════════════════════

    private fun sanitizeRisk(raw: Double?): Double? {
        if (raw == null || raw.isNaN() || raw.isInfinite()) return null
        return raw.coerceIn(0.0, 10.0)
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateSafetyCard(route: RouteInfo, mode: String) {
        val now = Date()
        val sunset = route.sunsetTime

        val isNight = sunset?.let { now.after(it) }
            ?: (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) !in 6..18)

        val roadData = route.roadData
        val composite = route.compositeScore ?: return

        val adjustedRisk = composite.totalScore
        val safetyScore = ((10.0 - adjustedRisk) / 10.0 * 100.0)
            .toInt()
            .coerceIn(0, 100)

        // ── 1. Overall Safety Score ──
        findViewById<ProgressBar>(R.id.pb_overall_safety)?.apply {
            progress = safetyScore
            progressTintList = android.content.res.ColorStateList.valueOf(
                when {
                    safetyScore >= 80 -> Color.parseColor("#4CAF50")
                    safetyScore >= 60 -> Color.parseColor("#FF9800")
                    else -> Color.parseColor("#F44336")
                }
            )
        }

        findViewById<TextView>(R.id.tv_safety_description)?.text =
            "$safetyScore% Safe Route"

        // ── 2. Dynamic Safety Warnings ──
        val warningBuilder = StringBuilder()

        route.sunsetTime?.let { sunset ->
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val sunsetStr = sdf.format(sunset)

            val arrivalTime = Date(now.time + (route.duration * 1000).toLong())

            if (isNight) {
                warningBuilder.append("🌙 Night mode active. Sunset was at $sunsetStr.\n")
            } else if (arrivalTime.after(sunset)) {
                warningBuilder.append("⚠️ Caution: You will arrive after sunset ($sunsetStr).\n")
            } else {
                warningBuilder.append("☀️ Daylight available until $sunsetStr.\n")
            }
        }

        if (mode == "Safest") {
            warningBuilder.append("✨ This is the safest route for you.")
        }

        // Police / Isolation
        if (safeZoneMarkers.isEmpty()) {
            warningBuilder.append("🏠 Isolated area; no help points nearby.\n")
        } else if (composite.policeScore > 2.0) {
            warningBuilder.append("📍 Limited police presence; use safe zones.\n")
        }


        // Calculate Arrival: Current Time + Route Duration (in millisecond

// Road condition (independent of time)
        if (!roadData.isPaved) {
            warningBuilder.append("⚠️ Uneven or unpaved road; watch your step.\n")
        }

        if (composite.roadScore > 7.0) {
            if (isNight) {
                warningBuilder.append("🌙 Poor visibility after sunset; path may be dark.\n")
            } else {
                warningBuilder.append("⚠️ This route may have low-quality or less maintained roads.\n")
            }
        }

        // Safest reassurance (only if no warnings)


        val warningBox = findViewById<MaterialCardView>(R.id.layout_safety_warnings)
        val warningText = findViewById<TextView>(R.id.tv_safety_warnings_list)

        // Initial UI update
        updateWarningUI(warningBox, warningText, warningBuilder)


        // ── 3. Detailed Analysis ──
        val surface = roadData.surfaceType.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }

        findViewById<TextView>(R.id.tvRoadQuality)?.text =
            if (roadData.isPaved)
                "🛣️ Smooth road"
            else
                "⚠️ Rough road"

// Updating the main text
        findViewById<TextView>(R.id.tvLighting)?.text = when {
            !isNight -> "☀️ Natural daylight"
            roadData.isMajorRoad -> "💡 Well-lit main road"
            else -> "🌙 Lighting may be limited"
        }

// Updating the chip status
        findViewById<TextView>(R.id.chip_lighting)?.text = when {
            !isNight -> "Bright"
            roadData.isMajorRoad -> "Well-lit"
            else -> "Low light"
        }
        val policeDistText = when {
            composite.policeScore <= 2.0 -> "~0.5–1.5 km away"
            composite.policeScore <= 6.0 -> "~1.5–3.0 km away"
            else -> "~3.0–5.0 km away"
        }

        findViewById<TextView>(R.id.tvPoliceDist)?.text =
            "Police: $policeDistText"

        val nightSafetyView = findViewById<TextView>(R.id.tvNightSafety)

        nightSafetyView?.text = when {
            // If the overall AI/Composite risk is very low
            composite.totalScore < 3.0 -> "✅ Verified Safe Path"

            // If it's a major road but AI detects local risk
            roadData.isMajorRoad && composite.crimeScore > 6.0 -> "⚠️ Major Road: Localized Alerts"

            // Default logic
            roadData.isMajorRoad && roadData.isPaved -> "Safe to travel at night"

            else -> "🚨 Be cautious: Low visibility/Isolated"
        }

        val crimeScoreView = findViewById<TextView>(R.id.tvCrimeScore)

        findViewById<TextView>(R.id.tvCrimeScore)?.text = when {
            safetyScore >= 80 -> "Low Risk Area"
            safetyScore >= 60 -> "Moderate Risk"
            else -> "Caution Advised"
        }
        crimeScoreView?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Safety Analysis Breakdown")
                .setMessage(
                    "This route's safety profile is calculated using:\n\n" +
                            "• Past Accidents: ${"%.1f".format(composite?.accidentScore ?: 0.0)}/10\n" +
                            "• AI Crime Prediction: ${"%.1f".format(composite?.crimeScore ?: 0.0)}/10\n" +
                            "• Road Type Risk: ${"%.1f".format(composite?.roadScore ?: 0.0)}/10\n" +
                            "• Active Safe Zones: ${safeZoneMarkers.size} found nearby"
                )
                .setPositiveButton("Got it", null)
                .show()
        }

        findViewById<TextView>(R.id.chip_crime)?.text =
            "Risk: ${"%.1f".format(adjustedRisk)}/10"

        // ── 4. AI Risk Indicator ──
        findViewById<TextView>(R.id.tvAiRiskScore)?.apply {

            val verdict = when {
                adjustedRisk < 3.0 -> "✅ Safe Path"
                adjustedRisk < 6.0 -> "⚠️ Use Caution"
                else -> "🚨 High Risk"
            }

            // Improved Confidence Logic:
// High confidence if we have accident data OR if the road is a Major Road.
            val hasCrimeData = composite.crimeScore > 0.5

            val confidencePct = when {
                composite.accidentScore > 1.0 && hasCrimeData && roadData.isMajorRoad -> 90
                composite.accidentScore > 1.0 || hasCrimeData -> 75
                roadData.isMajorRoad -> 65
                else -> 40
            }
            val reliabilityLabel = if (confidencePct > 70) "Verified" else "Estimated"

            text = "$verdict\nAI Confidence: $reliabilityLabel ($confidencePct%)"

            setTextColor(
                when {
                    adjustedRisk < 3.0 -> Color.parseColor("#2E7D32")
                    adjustedRisk < 6.0 -> Color.parseColor("#F57C00")
                    else -> Color.parseColor("#C62828")
                }
            )

            setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle("Safety Analysis")
                    .setMessage(
                        if (confidencePct > 70)
                            "This path is well-mapped with high historical safety data."
                        else
                            "Limited data available. Prefer well-lit main roads."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        // ── 5. Crowd Density ──
        val crowdDensity = estimateCrowdDensityInfo(roadData)

        findViewById<TextView>(R.id.tvCrowdDensity)?.text =
            crowdDensity.description

        findViewById<TextView>(R.id.chip_crowd)?.text =
            crowdDensity.level

        // ── 6. Route Summary ──
        findViewById<LinearLayout>(R.id.route_info_container)?.visibility =
            View.VISIBLE

        findViewById<TextView>(R.id.tv_route_distance)?.text =
            "${"%.2f".format(route.distance / 1000.0)} km"

        findViewById<TextView>(R.id.tv_route_duration)?.text =
            "${(route.duration / 60.0).toInt()} min"

        findViewById<TextView>(R.id.tv_transport_mode)?.text = mode

        // ── 7. Async Accident Check ──
        val pastAccidentView = findViewById<TextView>(R.id.tvAccidents)
        pastAccidentView?.text = "Checking..."

        countNearbyAccidentsAsync(route.points) { count ->

            pastAccidentView?.text =
                if (count > 0) "$count nearby" else "No major incidents"

            if (count > 50) {
                val currentWarnings = warningText?.text?.toString() ?: ""

                if (!currentWarnings.contains("traffic")) {
                    val newWarning =
                        "⚠️ Watch for traffic; many incidents ($count) were reported here.\n$currentWarnings"

                    warningText?.text = newWarning.trim()
                    warningBox?.visibility = View.VISIBLE
                    warningBox?.setCardBackgroundColor(
                        Color.parseColor("#FFEBEE")
                    )
                }
            }
        }

        // ── 8. Expand Bottom Sheet ──
        if (::bottomSheetBehavior.isInitialized) {
            bottomSheetBehavior.state =
                BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun updateWarningUI(
        box: MaterialCardView?,
        textView: TextView?,
        builder: StringBuilder
    ) {
        if (builder.isNotEmpty()) {
            box?.visibility = View.VISIBLE
            textView?.text = builder.toString().trim()
            val isSafe = builder.contains("✨") && !builder.contains("⚠️") && !builder.contains("🏠")
            box?.setCardBackgroundColor(Color.parseColor(if (isSafe) "#E8F5E9" else "#FFEBEE"))
            textView?.setTextColor(Color.parseColor(if (isSafe) "#2E7D32" else "#B71C1C"))
        } else {
            box?.visibility = View.GONE
        }
    }
    // ════════════════════════════════════════════════════════════════════════
    // DRAW ROUTE
    // ════════════════════════════════════════════════════════════════════════

    private fun drawRoute(points: List<GeoPoint>, type: RouteType) {
        if (points.size < 2) return
        if (routeOverlay == null) {
            routeOverlay = Polyline(); map.overlays.add(routeOverlay)
        }
        routeOverlay?.setPoints(points)
        routeOverlay?.outlinePaint?.strokeWidth = 10f
        routeOverlay?.outlinePaint?.color = when (type) {
            RouteType.FASTEST -> Color.BLUE
            RouteType.SAFEST -> Color.GREEN
            RouteType.HIGHEST_RISK -> Color.RED
        }
        map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.2f), true)
        map.invalidate()
    }

    // ════════════════════════════════════════════════════════════════════════
    // CROWD HELPER (display only)
    // ════════════════════════════════════════════════════════════════════════

    private data class CrowdDensityInfo(val level: String, val description: String)

    private fun estimateCrowdDensityInfo(roadData: RoadData): CrowdDensityInfo {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val score = (if (roadData.isMajorRoad) 6.0 else 3.0) + when (hour) {
            in 7..9 -> 2.0; in 17..19 -> 2.5
            in 22..23, in 0..5 -> -3.0; else -> 0.0
        }
        return when {
            score.coerceIn(0.0, 10.0) >= 7.0 -> CrowdDensityInfo("High", "Busy Area")
            score.coerceIn(0.0, 10.0) >= 4.0 -> CrowdDensityInfo("Medium", "Moderate Foot Traffic")
            else -> CrowdDensityInfo("Low", "Quiet Area")
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // LOCATION HELPERS
    // ════════════════════════════════════════════════════════════════════════

    private fun centerMapOnUserLocation() {
        if (::myLocationOverlay.isInitialized && myLocationOverlay.myLocation != null) {
            map.controller.animateTo(myLocationOverlay.myLocation)
            map.controller.setZoom(ROUTE_ZOOM)
        } else Toast.makeText(this, "Acquiring GPS location...", Toast.LENGTH_SHORT).show()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleLocationPermissions() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
            enableLocationTracking()
        else requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun requestPermissionsIfNeeded() {
        val notGranted =
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.SEND_SMS)
                .filter {
                    ContextCompat.checkSelfPermission(
                        this,
                        it
                    ) != PackageManager.PERMISSION_GRANTED
                }
        if (notGranted.isNotEmpty()) ActivityCompat.requestPermissions(
            this,
            notGranted.toTypedArray(),
            101
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Location Permission Required")
            .setMessage("SafePath needs location access.")
            .setPositiveButton("Grant Permission") { _, _ ->
                requestPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun hideKeyboard(view: View) =
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(
            view.windowToken,
            0
        )

    private fun showKeyboard(view: View) {
        view.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(
            view,
            InputMethodManager.SHOW_IMPLICIT
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // AI INIT & TEST
    // ════════════════════════════════════════════════════════════════════════

    private fun initializeAIComponents() {
        try {
            riskPredictor = TFLiteRiskPredictorAdapter(tfLiteRiskPredictor)
            isTfliteReady = true
            val riskPredictorAdapter = TFLiteRiskPredictorAdapter(tfLiteRiskPredictor)
            val osmFeatureExtractor = OSMFeatureExtractor(this)

            val crimeDataSource = object : CrimeDataSource {
                @RequiresApi(Build.VERSION_CODES.O)
                override suspend fun getCrimeDensity(point: GeoPoint) =
                    riskPredictorAdapter.predict(osmFeatureExtractor.extractFeatures(point))
            }
            val crowdDataSource = object : CrowdDataSource {
                @RequiresApi(Build.VERSION_CODES.O)
                override suspend fun getCrowdLevel(
                    point: GeoPoint,
                    time: java.time.LocalTime
                ): Double {
                    val f = osmFeatureExtractor.extractFeatures(point)
                    return ((f[6] + f[7]) / 20.0).coerceIn(0.0, 1.0)
                }
            }
            val lightingDataSource = object : LightingDataSource {
                @RequiresApi(Build.VERSION_CODES.O)
                override suspend fun getLightingScore(
                    point: GeoPoint,
                    time: java.time.LocalTime
                ): Double {
                    val f = osmFeatureExtractor.extractFeatures(point)
                    val p = when (time.hour) {
                        in 0..5 -> 0.3; in 6..7 -> 0.7; in 8..17 -> 1.0; in 18..19 -> 0.8; else -> 0.4
                    }
                    return f[3].toDouble() * p
                }
            }
            val environmentDataSource = object : EnvironmentDataSource {
                @RequiresApi(Build.VERSION_CODES.O)
                override suspend fun getIsolationScore(point: GeoPoint) =
                    1.0 - osmFeatureExtractor.extractFeatures(point)[8].toDouble()
            }
            val incidentDataSource = object : IncidentDataSource {
                override suspend fun getNearestIncidentDistance(point: GeoPoint) = 1000.0
            }
            val safetyInfraDataSource = object : SafetyInfraDataSource {
                @RequiresApi(Build.VERSION_CODES.O)
                override suspend fun getPoliceProximityScore(point: GeoPoint): Double {
                    val f = osmFeatureExtractor.extractFeatures(point)
                    return ((1.0 - f[4]) + (1.0 - f[5])) / 2.0
                }
            }

            DefaultLiveDataProvider(
                featureExtractor = RiskFeatureExtractor(
                    crimeDataSource,
                    crowdDataSource,
                    lightingDataSource,
                    environmentDataSource,
                    incidentDataSource,
                    safetyInfraDataSource
                ),
                predictor = riskPredictorAdapter
            )
            pathfinder = AStarPathfinder(
                riskPredictor = riskPredictorAdapter,
                dataProvider = TomTomDataProvider(),
                riskWeight = 0.6
            )
            Log.i(TAG, "AI components initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AI components", e)
        }
    }

    private fun testModel() {
        Log.d(TAG, "=== TESTING MODEL ===")
        if (tfLiteRiskPredictor.getInterpreter() == null) {
            Toast.makeText(this, "AI Model failed to load", Toast.LENGTH_LONG).show(); return
        }
        val result = tfLiteRiskPredictor.predictRisk(FloatArray(15) { 0.5f })
        when {
            result.isNaN() -> Log.e(TAG, "❌ NaN output from model")
            result.isInfinite() -> Log.e(TAG, "❌ Infinite output from model")
            else -> Toast.makeText(this, "AI Model OK: Risk=$result", Toast.LENGTH_SHORT).show()
        }
        Log.d(TAG, "=== TEST COMPLETE ===")
    }

    // ════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume(); map.onResume()
    }

    override fun onPause() {
        super.onPause(); map.onPause()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        clearSafeZoneMarkers()
        shakeListener?.let { sensorManager.unregisterListener(it) }
        anomalyListener?.let { anomalySensorManager?.unregisterListener(it) }// ← add this
        try {
            tfLiteRiskPredictor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TFLite", e)
        }
        client.dispatcher.cancelAll()
    }
}