package com.example.safepathapp

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.osmdroid.util.GeoPoint
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Runs TensorFlow Lite risk prediction model
 * using real-world OSM-derived features.
 */
class TFLiteRiskPredictor(private val context: Context) {

    companion object {
        private const val TAG = "TFLiteRiskPredictor"
        private const val INPUT_SIZE = 15
        private const val MODEL_FILE = "risk_predictor.tflite"
    }

    // ---- Scaler parameters from Python's StandardScaler.fit(X_train) ----
    // Generated via: scaler.mean_.tolist() and scaler.scale_.tolist()
    private val scalerMean = floatArrayOf(
        0.497312274524214f,
        0.50039971574004f,
        0.5034087238367527f,
        0.4985935187106305f,
        0.4970338513545012f,
        0.5007612805121846f,
        0.49288577461217475f,
        0.49701238364207645f,
        0.49904734688881813f,
        0.49935510905299235f,
        0.4997024751479449f,
        0.4987617095669671f,
        0.5016596994049084f,
        0.5019340062614307f,
        0.5048053698272013f
    )

    private val scalerStd = floatArrayOf(
        0.2885689547099279f,
        0.2898055155569366f,
        0.2904194618006179f,
        0.2891371751658637f,
        0.28815102515067226f,
        0.2844120391138855f,
        0.28879737918524345f,
        0.28826433394871176f,
        0.2907822077101712f,
        0.2909922082174633f,
        0.2891405437143611f,
        0.287823926977354f,
        0.29066031534271924f,
        0.2889837115820667f,
        0.2889574476475292f
    )

    private var interpreter: Interpreter? = null
    private val inferenceLock = Any()

    // NOTE: outputBuffer must NOT be shared across threads.
    // Moved inside predictRisk() to avoid race conditions when called concurrently.

    /** Real OSM feature extractor */
    private val featureExtractor = OSMFeatureExtractor(context)

    init {
        try {
            interpreter = Interpreter(loadModelFile())
            Log.i(TAG, "TFLite model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TFLite model: ${e.message}", e)
        }
    }

    // ---------------- MODEL LOADING ----------------

    private fun loadModelFile(): ByteBuffer {
        val assetFileDescriptor = context.assets.openFd(MODEL_FILE)
        // FIX: Removed use{} block — FileInputStream.use() closes the stream before
        // the ByteBuffer mapping can be read, causing a crash at inference time.
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel: FileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.declaredLength
        )
    }

    // ---------------- ML INFERENCE ----------------

    /**
     * Scales raw features and runs inference.
     * @param features FloatArray of exactly 15 unscaled values.
     * @return Predicted risk score (typically 0.0 – 1.0), or 0f on error.
     */
    fun predictRisk(features: FloatArray): Float {
        if (interpreter == null) {
            Log.w(TAG, "Interpreter is null — model may not have loaded correctly.")
            return 0f
        }
        if (features.size != INPUT_SIZE) {
            Log.e(TAG, "Feature size mismatch: expected $INPUT_SIZE, got ${features.size}")
            return 0f
        }

        // 1. Scale: (x - mean) / std
        val scaledInput = FloatArray(INPUT_SIZE) { i ->
            (features[i] - scalerMean[i]) / scalerStd[i]
        }

        // 2. Pack into input buffer
        val inputBuffer = ByteBuffer
            .allocateDirect(INPUT_SIZE * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        for (value in scaledInput) {
            inputBuffer.putFloat(value)
        }
        // FIX: rewind() BEFORE run(), not after putFloat loop — ensures buffer
        // position is at 0 when TFLite reads it.
        inputBuffer.rewind()

        // 3. Local output buffer per call — avoids shared-state race condition
        val outputBuffer = Array(1) { FloatArray(1) }

        // 4. Thread-safe inference
        synchronized(inferenceLock) {
            interpreter?.run(inputBuffer, outputBuffer)
        }

        return outputBuffer[0][0]
    }

    // ---------------- ROUTE LEVEL RISK ----------------

    /**
     * Calculates average risk score across all sampled points on a route.
     * Samples every 10th point for routes longer than 50 points.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun calculateRouteRisk(routePoints: List<GeoPoint>): Double {
        if (routePoints.isEmpty()) return 0.0

        val stepSize = if (routePoints.size > 50) 10 else 1
        var cumulativeRisk = 0.0
        var samples = 0

        for (i in routePoints.indices step stepSize) {
            val features = featureExtractor.extractFeatures(routePoints[i])
            cumulativeRisk += predictRisk(features)
            samples++
        }

        return if (samples > 0) cumulativeRisk / samples else 0.0
    }

    fun getInterpreter(): Interpreter? = interpreter

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}

/**
 * Adapter to make TFLiteRiskPredictor
 * compatible with the generic RiskPredictor interface.
 */
class TFLiteRiskPredictorAdapter(
    private val predictor: TFLiteRiskPredictor
) : RiskPredictor {

    override fun predict(features: FloatArray): Double {
        return predictor.predictRisk(features).toDouble()
    }
}