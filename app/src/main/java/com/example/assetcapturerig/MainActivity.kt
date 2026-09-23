package com.example.assetcapturerig

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Config
import com.google.ar.core.Frame
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.ArNode
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var sceneView: ARSceneView
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button

    private val httpClient = OkHttpClient()
    private val modalEndpoint = "https://emmanuelnwalema--mobile-6dof-capture-ui.modal.run/upload_plate"

    private var currentStepIdx = 0
    private var isBoxPlaced = false
    private var isUploading = false

    private val sequence = listOf(
        "front" to "1. Front (0° Level)",
        "hero_3_4" to "2. Hero Seed (45° / 30° Tilt)",
        "right" to "3. Right Profile (0° Level)",
        "back_r" to "4. Rear-Right (30° Tilt)",
        "back" to "5. Back View (0° Level)",
        "back_l" to "6. Rear-Left (30° Tilt)",
        "left" to "7. Left Profile (0° Level)",
        "front_l" to "8. Front-Left (30° Tilt)",
        "top" to "9. Top Overhead (90° Down)",
        "bottom" to "10. Underside (60° Up)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sceneView = findViewById(R.id.arSceneView)
        statusText = findViewById(R.id.statusText)
        actionButton = findViewById(R.id.actionButton)

        // Enable hardware continuous autofocus
        sceneView.sessionConfiguration = { session, config ->
            config.focusMode = Config.FocusMode.AUTO
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        }

        // 60 fps tracking loop
        sceneView.onSessionUpdated = { _, frame ->
            onTrackingFrame(frame)
        }

        actionButton.setOnClickListener {
            if (!isBoxPlaced) {
                placePrismAtHitTest()
            }
        }
    }

    private fun placePrismAtHitTest() {
        val frame = sceneView.currentFrame ?: return
        val hitResults = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)

        val firstHit = hitResults.firstOrNull()
        if (firstHit != null) {
            val anchor = firstHit.createAnchor()
            val anchorNode = ArNode(sceneView.engine).apply {
                this.anchor = anchor
            }
            sceneView.addChildNode(anchorNode)

            isBoxPlaced = true
            actionButton.text = "CAPTURING RUN ACTIVE"
            actionButton.isEnabled = false
            updateHUD()
        } else {
            Toast.makeText(this, "Scan table surface first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onTrackingFrame(frame: Frame) {
        if (!isBoxPlaced || isUploading || currentStepIdx >= sequence.size) return

        val camera = frame.camera
        val cameraPose = camera.pose

        // Optical ray forward vector (-Z in ARCore space)
        val forwardRay = floatArrayOf(
            -cameraPose.zAxis[0],
            -cameraPose.zAxis[1],
            -cameraPose.zAxis[2]
        )

        val targetNormal = getFacetNormal(currentStepIdx)

        // Collimation angle error
        val dot = -(forwardRay[0] * targetNormal[0] + forwardRay[1] * targetNormal[1] + forwardRay[2] * targetNormal[2])
        val angleErrDeg = Math.toDegrees(acos(dot.coerceIn(-1.0f, 1.0f).toDouble()))

        runOnUiThread {
            statusText.text = "${sequence[currentStepIdx].second}\nAngle Error: ${angleErrDeg.toInt()}°"
        }

        if (angleErrDeg <= 10.0) {
            triggerFullResCapture()
        }
    }

    private fun triggerFullResCapture() {
        isUploading = true
        runOnUiThread { statusText.text = "Capturing Plate..." }

        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { copyResult ->
            if (copyResult == PixelCopy.SUCCESS) {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                val jpegBytes = stream.toByteArray()
                uploadToModal(sequence[currentStepIdx].first, jpegBytes)
            } else {
                isUploading = false
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun uploadToModal(targetId: String, jpegBytes: ByteArray) {
        val json = JSONObject().apply {
            put("target_id", targetId)
            put("b64", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
            put("dimensions", JSONObject().apply {
                put("w", 0.18)
                put("h", 0.14)
                put("d", 0.18)
            })
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(modalEndpoint)
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ModalUpload", "Failed to upload plate", e)
                isUploading = false
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    currentStepIdx++
                    isUploading = false
                    updateHUD()
                }
            }
        })
    }

    private fun getFacetNormal(step: Int): FloatArray {
        val cos30 = cos(Math.toRadians(30.0)).toFloat()
        val sin30 = sin(Math.toRadians(30.0)).toFloat()
        val sqrt2Inv = 0.7071f

        return when (step) {
            0 -> floatArrayOf(0f, 0f, 1f)
            1 -> floatArrayOf(cos30 * sqrt2Inv, sin30, cos30 * sqrt2Inv) // 30° Hero
            2 -> floatArrayOf(1f, 0f, 0f)
            3 -> floatArrayOf(cos30 * sqrt2Inv, sin30, -cos30 * sqrt2Inv)
            4 -> floatArrayOf(0f, 0f, -1f)
            5 -> floatArrayOf(-cos30 * sqrt2Inv, sin30, -cos30 * sqrt2Inv)
            6 -> floatArrayOf(-1f, 0f, 0f)
            7 -> floatArrayOf(-cos30 * sqrt2Inv, sin30, cos30 * sqrt2Inv)
            8 -> floatArrayOf(0f, 1f, 0f)
            else -> floatArrayOf(0f, -1f, 0f)
        }
    }

    private fun updateHUD() {
        if (currentStepIdx >= sequence.size) {
            statusText.text = "✓ ALL 10 PLATES STORED ON MODAL VOLUME"
        } else {
            statusText.text = sequence[currentStepIdx].second
        }
    }
}
