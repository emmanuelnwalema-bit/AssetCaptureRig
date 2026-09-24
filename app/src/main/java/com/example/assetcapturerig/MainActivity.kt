package com.example.assetcapturerig

import android.content.Context
import android.graphics.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var sceneView: ARSceneView
    private lateinit var overlayView: PrismOverlayView
    private lateinit var snapFlash: View

    private lateinit var targetBadge: TextView
    private lateinit var chkListLeft: TextView
    private lateinit var chkListRight: TextView
    private lateinit var valDist: TextView
    private lateinit var valAlign: TextView
    private lateinit var valScale: TextView

    private lateinit var distIndicator: TextView
    private lateinit var normalIndicator: TextView
    private lateinit var centerIndicator: TextView

    private lateinit var scaleBar: LinearLayout
    private lateinit var scaleReadout: TextView
    private lateinit var btnScaleDown: Button
    private lateinit var btnScaleUp: Button
    private lateinit var forceSnapBtn: Button
    private lateinit var actionButton: Button

    private val httpClient = OkHttpClient()
    private val modalEndpoint = "https://emmanuelnwalema--mobile-6dof-capture-ui.modal.run/upload_plate"
    private var toneGen: ToneGenerator? = null

    private var currentStepIdx = 0
    private var isBoxPlaced = false
    private var isUploading = false
    private var lastFrame: Frame? = null
    private var prismAnchor: Anchor? = null
    private var initialAzimuth = 0f
    private var alignStartTime: Long? = null

    private var prismWidth = 0.18f
    private var prismDepth = 0.18f
    private var prismHeight = 0.14f

    data class SequenceStep(val id: String, val label: String, val hint: String)

    private val sequence = listOf(
        SequenceStep("front", "1. FRONT (0° LEVEL)", "AIM LEVEL (0°) AT FRONT"),
        SequenceStep("hero_3_4", "2. HERO SEED (45° / 30° TILT)", "ORBIT 45° & TILT 30° DOWN"),
        SequenceStep("right", "3. RIGHT PROFILE (0° LEVEL)", "AIM LEVEL (0°) AT RIGHT"),
        SequenceStep("back_r", "4. REAR-RIGHT (30° TILT)", "ORBIT 135° & TILT 30° DOWN"),
        SequenceStep("back", "5. BACK VIEW (0° LEVEL)", "AIM LEVEL (0°) AT REAR"),
        SequenceStep("back_l", "6. REAR-LEFT (30° TILT)", "ORBIT 225° & TILT 30° DOWN"),
        SequenceStep("left", "7. LEFT PROFILE (0° LEVEL)", "AIM LEVEL (0°) AT LEFT"),
        SequenceStep("front_l", "8. FRONT-LEFT (30° TILT)", "ORBIT 315° & TILT 30° DOWN"),
        SequenceStep("top", "9. TOP OVERHEAD (90° DOWN)", "HOLD OVERHEAD LOOKING DOWN"),
        SequenceStep("bottom", "10. UNDERSIDE (60° UP)", "AIM UPWARD AT ASSET BASE")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        sceneView = findViewById(R.id.arSceneView)
        snapFlash = findViewById(R.id.snapFlash)

        targetBadge = findViewById(R.id.targetBadge)
        chkListLeft = findViewById(R.id.chkListLeft)
        chkListRight = findViewById(R.id.chkListRight)
        valDist = findViewById(R.id.valDist)
        valAlign = findViewById(R.id.valAlign)
        valScale = findViewById(R.id.valScale)

        distIndicator = findViewById(R.id.distIndicator)
        normalIndicator = findViewById(R.id.normalIndicator)
        centerIndicator = findViewById(R.id.centerIndicator)

        scaleBar = findViewById(R.id.scaleBar)
        scaleReadout = findViewById(R.id.scaleReadout)
        btnScaleDown = findViewById(R.id.btnScaleDown)
        btnScaleUp = findViewById(R.id.btnScaleUp)
        forceSnapBtn = findViewById(R.id.forceSnapBtn)
        actionButton = findViewById(R.id.actionButton)

        try {
            toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            // Audio optional
        }

        overlayView = PrismOverlayView(this)
        rootLayout.addView(overlayView, 1)

        sceneView.sessionConfiguration = { _, config ->
            config.focusMode = Config.FocusMode.AUTO
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        }

        sceneView.onSessionUpdated = { _, frame ->
            lastFrame = frame
            onTrackingFrame(frame)
        }

        actionButton.setOnClickListener {
            if (!isBoxPlaced) placePrismAtHitTest()
        }

        forceSnapBtn.setOnClickListener {
            if (!isUploading && currentStepIdx < sequence.size) {
                triggerFullResCapture()
            }
        }

        btnScaleUp.setOnClickListener { scalePrism(1.08f) }
        btnScaleDown.setOnClickListener { scalePrism(0.92f) }

        updateChecklistUI()
        updateScaleLabels()
    }

    private fun scalePrism(factor: Float) {
        prismWidth = (prismWidth * factor).coerceIn(0.06f, 0.60f)
        prismDepth = (prismDepth * factor).coerceIn(0.06f, 0.60f)
        prismHeight = (prismHeight * factor).coerceIn(0.04f, 0.40f)
        updateScaleLabels()
        overlayView.postInvalidate()
    }

    private fun updateScaleLabels() {
        val wCm = (prismWidth * 100).roundToInt()
        val dCm = (prismDepth * 100).roundToInt()
        val hCm = (prismHeight * 100).roundToInt()
        scaleReadout.text = "PRISM: ${wCm}×${dCm}×${hCm}cm"
        valScale.text = "SCALE: ${wCm}×${dCm}"
    }

    private fun updateChecklistUI() {
        val sbL = StringBuilder()
        val sbR = StringBuilder()
        for (i in 0 until 5) {
            val icon = if (i < currentStepIdx) "🟩" else if (i == currentStepIdx) "▶" else "⬜"
            sbL.append("$icon ${sequence[i].label.substring(3)}\n")
        }
        for (i in 5 until 10) {
            val icon = if (i < currentStepIdx) "🟩" else if (i == currentStepIdx) "▶" else "⬜"
            sbR.append("$icon ${sequence[i].label.substring(3)}\n")
        }
        chkListLeft.text = sbL.toString().trimEnd()
        chkListRight.text = sbR.toString().trimEnd()
    }

    private fun placePrismAtHitTest() {
        val frame = lastFrame ?: return
        val hitResults = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
        val firstHit = hitResults.firstOrNull()

        if (firstHit != null) {
            val anchor = firstHit.createAnchor()
            prismAnchor = anchor

            val camPose = frame.camera.pose
            val anchorPose = anchor.pose
            val dx = camPose.tx() - anchorPose.tx()
            val dz = camPose.tz() - anchorPose.tz()
            initialAzimuth = atan2(dx, dz)

            isBoxPlaced = true
            actionButton.visibility = View.GONE
            scaleBar.visibility = View.VISIBLE
            forceSnapBtn.visibility = View.VISIBLE
            forceSnapBtn.text = "FORCE SNAP [${sequence[currentStepIdx].id.uppercase(Locale.US)}]"
            targetBadge.text = sequence[currentStepIdx].label
            updateChecklistUI()
            overlayView.postInvalidate()
        } else {
            Toast.makeText(this, "Pan phone to scan table surface first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onTrackingFrame(frame: Frame) {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return

        if (!isBoxPlaced) {
            val hitResults = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
            runOnUiThread {
                if (hitResults.isNotEmpty()) {
                    distIndicator.text = "SURFACE FOUND: TAP LOCK PRISM"
                    distIndicator.setTextColor(Color.parseColor("#3FB950"))
                } else {
                    distIndicator.text = "SCANNING FOR TABLE..."
                    distIndicator.setTextColor(Color.parseColor("#E3B341"))
                }
            }
            overlayView.postInvalidate()
            return
        }

        if (currentStepIdx >= sequence.size || isUploading) return

        val anchor = prismAnchor ?: return
        val anchorPose = anchor.pose
        val camPose = camera.pose

        val desc = getFacetDescriptor(currentStepIdx)
        val faceCenterWorld = localToWorld(desc.centerX, desc.centerY, desc.centerZ, anchorPose)
        val faceNormalWorld = rotateNormalByAzimuth(desc.normX, desc.normY, desc.normZ)

        // 1. Focal Distance
        val toFaceVecX = faceCenterWorld[0] - camPose.tx()
        val toFaceVecY = faceCenterWorld[1] - camPose.ty()
        val toFaceVecZ = faceCenterWorld[2] - camPose.tz()
        val distMeters = sqrt(toFaceVecX * toFaceVecX + toFaceVecY * toFaceVecY + toFaceVecZ * toFaceVecZ)
        val distCm = distMeters * 100f
        val isDistanceOptimal = distCm in 20.0f..38.0f

        // 2. Collimation Angle (Optical Ray vs Facet Normal)
        val camForward = floatArrayOf(-camPose.zAxis[0], -camPose.zAxis[1], -camPose.zAxis[2])
        val dotNormal = -(camForward[0] * faceNormalWorld[0] + camForward[1] * faceNormalWorld[1] + camForward[2] * faceNormalWorld[2])
        val normAngleErr = Math.toDegrees(acos(dotNormal.coerceIn(-1.0f, 1.0f).toDouble())).toFloat()
        val isNormalAligned = normAngleErr <= 10.0f

        // 3. Centering Angle
        val safeDist = if (distMeters > 0.001f) distMeters else 1.0f
        val toFaceDirX = toFaceVecX / safeDist
        val toFaceDirY = toFaceVecY / safeDist
        val toFaceDirZ = toFaceVecZ / safeDist
        val dotCenter = camForward[0] * toFaceDirX + camForward[1] * toFaceDirY + camForward[2] * toFaceDirZ
        val centAngleErr = Math.toDegrees(acos(dotCenter.coerceIn(-1.0f, 1.0f).toDouble())).toFloat()
        val isCentered = centAngleErr <= 12.0f

        val isReadyToCapture = isNormalAligned && isCentered && isDistanceOptimal

        runOnUiThread {
            valDist.text = "DIST: ${distCm.roundToInt()}cm"
            valAlign.text = String.format(Locale.US, "ALIGN: %.1f°", normAngleErr)

            if (distCm < 20f) {
                distIndicator.text = "⚠️ TOO CLOSE (${distCm.roundToInt()}cm) — STEP BACK"
                distIndicator.setTextColor(Color.parseColor("#F85149"))
            } else if (distCm > 38f) {
                distIndicator.text = "MOVE CLOSER (${distCm.roundToInt()}cm) — AIM 25-32cm"
                distIndicator.setTextColor(Color.parseColor("#E3B341"))
            } else {
                distIndicator.text = "✓ FOCUS DISTANCE: ${distCm.roundToInt()}cm (SHARP)"
                distIndicator.setTextColor(Color.parseColor("#3FB950"))
            }

            if (isNormalAligned) {
                normalIndicator.text = String.format(Locale.US, "ALIGNMENT: %.1f° (LOCKED)", normAngleErr)
                normalIndicator.setTextColor(Color.parseColor("#3FB950"))
            } else {
                normalIndicator.text = String.format(Locale.US, "OFF-AXIS: %.1f° (%s)", normAngleErr, sequence[currentStepIdx].hint)
                normalIndicator.setTextColor(Color.parseColor("#F85149"))
            }

            if (isCentered) {
                centerIndicator.text = String.format(Locale.US, "CENTERING: %.1f° (CENTERED)", centAngleErr)
                centerIndicator.setTextColor(Color.parseColor("#3FB950"))
            } else {
                centerIndicator.text = String.format(Locale.US, "CENTERING: %.1f° (FRAME BULLSEYE)", centAngleErr)
                centerIndicator.setTextColor(Color.parseColor("#8B949E"))
            }
        }

        if (isReadyToCapture) {
            overlayView.isAligned = true
            val now = System.currentTimeMillis()
            if (alignStartTime == null) {
                alignStartTime = now
            } else if (now - alignStartTime!! >= 450) {
                alignStartTime = null
                triggerFullResCapture()
            }
        } else {
            overlayView.isAligned = false
            alignStartTime = null
        }

        overlayView.postInvalidate()
    }

    private fun triggerFullResCapture() {
        isUploading = true
        runOnUiThread {
            distIndicator.text = "CAPTURING SHARP PLATE..."
            playSnapFeedback()
        }

        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { copyResult ->
            if (copyResult == PixelCopy.SUCCESS) {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                val jpegBytes = stream.toByteArray()
                uploadToModal(sequence[currentStepIdx].id, jpegBytes)
            } else {
                isUploading = false
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun playSnapFeedback() {
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: Exception) {
            // Optional feedback
        }

        snapFlash.alpha = 0.5f
        snapFlash.visibility = View.VISIBLE
        snapFlash.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { snapFlash.visibility = View.GONE }
            .start()
    }

    private fun uploadToModal(targetId: String, jpegBytes: ByteArray) {
        val json = JSONObject().apply {
            put("target_id", targetId)
            put("b64", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
            put("dimensions", JSONObject().apply {
                put("w", prismWidth)
                put("h", prismHeight)
                put("d", prismDepth)
            })
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(modalEndpoint).post(body).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ModalUpload", "Upload failed", e)
                isUploading = false
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    currentStepIdx++
                    isUploading = false
                    updateChecklistUI()

                    if (currentStepIdx >= sequence.size) {
                        targetBadge.text = "✓ ALL 10 PLATES STORED"
                        targetBadge.setTextColor(Color.parseColor("#3FB950"))
                        distIndicator.text = "CAPTURE COMPLETE"
                        distIndicator.setTextColor(Color.parseColor("#3FB950"))
                        normalIndicator.text = "STORED ON MODAL VOLUME"
                        centerIndicator.text = ""
                        forceSnapBtn.visibility = View.GONE
                        scaleBar.visibility = View.GONE
                    } else {
                        targetBadge.text = sequence[currentStepIdx].label
                        forceSnapBtn.text = "FORCE SNAP [${sequence[currentStepIdx].id.uppercase(Locale.US)}]"
                    }
                    overlayView.postInvalidate()
                }
            }
        })
    }

    data class FacetDesc(
        val centerX: Float, val centerY: Float, val centerZ: Float,
        val normX: Float, val normY: Float, val normZ: Float
    )

    private fun getFacetDescriptor(step: Int): FacetDesc {
        val cos30 = cos(Math.toRadians(30.0)).toFloat()
        val sin30 = sin(Math.toRadians(30.0)).toFloat()
        val sqrt2Inv = 0.7071f
        val hW = prismWidth / 2f
        val hD = prismDepth / 2f
        val hH = prismHeight / 2f

        return when (step) {
            0 -> FacetDesc(0f, hH, hD, 0f, 0f, 1f)
            1 -> FacetDesc(hW * 0.7f, hH, hD * 0.7f, cos30 * sqrt2Inv, sin30, cos30 * sqrt2Inv)
            2 -> FacetDesc(hW, hH, 0f, 1f, 0f, 0f)
            3 -> FacetDesc(hW * 0.7f, hH, -hD * 0.7f, cos30 * sqrt2Inv, sin30, -cos30 * sqrt2Inv)
            4 -> FacetDesc(0f, hH, -hD, 0f, 0f, -1f)
            5 -> FacetDesc(-hW * 0.7f, hH, -hD * 0.7f, -cos30 * sqrt2Inv, sin30, -cos30 * sqrt2Inv)
            6 -> FacetDesc(-hW, hH, 0f, -1f, 0f, 0f)
            7 -> FacetDesc(-hW * 0.7f, hH, hD * 0.7f, -cos30 * sqrt2Inv, sin30, cos30 * sqrt2Inv)
            8 -> FacetDesc(0f, prismHeight, 0f, 0f, 1f, 0f)
            else -> FacetDesc(0f, 0f, 0f, 0f, -1f, 0f)
        }
    }

    private fun rotateNormalByAzimuth(nx: Float, ny: Float, nz: Float): FloatArray {
        val c = cos(initialAzimuth)
        val s = sin(initialAzimuth)
        return floatArrayOf(nx * c + nz * s, ny, -nx * s + nz * c)
    }

    private fun localToWorld(lx: Float, ly: Float, lz: Float, anchorPose: com.google.ar.core.Pose): FloatArray {
        val c = cos(initialAzimuth)
        val s = sin(initialAzimuth)
        val rx = lx * c + lz * s
        val ry = ly
        val rz = -lx * s + lz * c
        return floatArrayOf(anchorPose.tx() + rx, anchorPose.ty() + ry, anchorPose.tz() + rz)
    }

    inner class PrismOverlayView(context: Context) : View(context) {

        var isAligned = false

        private val wirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#58A6FF")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        private val facetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        private val fillReticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val viewMatrix = FloatArray(16)
        private val projMatrix = FloatArray(16)
        private val vpMatrix = FloatArray(16)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val frame = lastFrame ?: return
            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) return

            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.05f, 50.0f)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

            val screenW = width.toFloat()
            val screenH = height.toFloat()

            // 1. Draw Surface Reticle Before Lock
            if (!isBoxPlaced) {
                val hitResults = frame.hitTest(screenW / 2f, screenH / 2f)
                val hit = hitResults.firstOrNull()
                if (hit != null) {
                    val p = projectPoint(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz(), screenW, screenH)
                    if (p != null) {
                        reticlePaint.color = Color.parseColor("#58A6FF")
                        canvas.drawCircle(p.x, p.y, 45f, reticlePaint)
                        canvas.drawCircle(p.x, p.y, 15f, reticlePaint)
                    }
                }
                return
            }

            val anchor = prismAnchor ?: return
            val anchorPose = anchor.pose

            // 2. Build 3D compound geometry vertices
            val hW = prismWidth / 2f
            val hD = prismDepth / 2f
            val c = min(hW, hD) * 0.35f
            val tan30 = tan(Math.toRadians(30.0)).toFloat()
            val delta = min(prismHeight * tan30, c * 0.95f)
            val dX = delta * 0.7071f
            val dZ = delta * 0.7071f

            val botLocal = arrayOf(
                floatArrayOf(hW - c, 0f, hD),
                floatArrayOf(-hW + c, 0f, hD),
                floatArrayOf(-hW, 0f, hD - c),
                floatArrayOf(-hW, 0f, -hD + c),
                floatArrayOf(-hW + c, 0f, -hD),
                floatArrayOf(hW - c, 0f, -hD),
                floatArrayOf(hW, 0f, -hD + c),
                floatArrayOf(hW, 0f, hD - c)
            )

            val topLocal = arrayOf(
                floatArrayOf(hW - c, prismHeight, hD),
                floatArrayOf(-hW + c, prismHeight, hD),
                floatArrayOf(-hW, prismHeight, hD - c),
                floatArrayOf(-hW, prismHeight, -hD + c),
                floatArrayOf(-hW + c, prismHeight, -hD),
                floatArrayOf(hW - c, prismHeight, -hD),
                floatArrayOf(hW, prismHeight, -hD + c),
                floatArrayOf(hW, prismHeight, hD - c)
            )

            val topCornersLocal = arrayOf(
                floatArrayOf((botLocal[0][0] + botLocal[7][0]) / 2f - dX, prismHeight, (botLocal[0][2] + botLocal[7][2]) / 2f - dZ),
                floatArrayOf((botLocal[1][0] + botLocal[2][0]) / 2f + dX, prismHeight, (botLocal[1][2] + botLocal[2][2]) / 2f - dZ),
                floatArrayOf((botLocal[3][0] + botLocal[4][0]) / 2f + dX, prismHeight, (botLocal[3][2] + botLocal[4][2]) / 2f + dZ),
                floatArrayOf((botLocal[5][0] + botLocal[6][0]) / 2f - dX, prismHeight, (botLocal[5][2] + botLocal[6][2]) / 2f + dZ)
            )

            val botPts = botLocal.map { projectPoint(localToWorld(it[0], it[1], it[2], anchorPose), screenW, screenH) }
            val topPts = topLocal.map { projectPoint(localToWorld(it[0], it[1], it[2], anchorPose), screenW, screenH) }
            val cornerPts = topCornersLocal.map { projectPoint(localToWorld(it[0], it[1], it[2], anchorPose), screenW, screenH) }

            // 3. Draw Octagonal Wireframe Cage
            for (i in 0 until 8) {
                val next = (i + 1) % 8
                drawEdge(canvas, botPts[i], botPts[next])
            }
            // Vertical Walls
            drawEdge(canvas, botPts[1], topPts[1])
            drawEdge(canvas, botPts[0], topPts[0])
            drawEdge(canvas, topPts[1], topPts[0])

            drawEdge(canvas, botPts[7], topPts[7])
            drawEdge(canvas, botPts[6], topPts[6])
            drawEdge(canvas, topPts[7], topPts[6])

            drawEdge(canvas, botPts[5], topPts[5])
            drawEdge(canvas, botPts[4], topPts[4])
            drawEdge(canvas, topPts[5], topPts[4])

            drawEdge(canvas, botPts[3], topPts[3])
            drawEdge(canvas, botPts[2], topPts[2])
            drawEdge(canvas, topPts[3], topPts[2])

            // Slanted Corner Chamfers
            drawEdge(canvas, botPts[0], cornerPts[0])
            drawEdge(canvas, botPts[7], cornerPts[0])
            drawEdge(canvas, botPts[6], cornerPts[3])
            drawEdge(canvas, botPts[5], cornerPts[3])
            drawEdge(canvas, botPts[4], cornerPts[2])
            drawEdge(canvas, botPts[3], cornerPts[2])
            drawEdge(canvas, botPts[2], cornerPts[1])
            drawEdge(canvas, botPts[1], cornerPts[1])

            // 4. Highlight Active Target Facet
            if (currentStepIdx < sequence.size) {
                val activeColor = if (isAligned) Color.parseColor("#803FB950") else Color.parseColor("#5058A6FF")
                facetPaint.color = activeColor

                val facetPoly = when (currentStepIdx) {
                    0 -> listOf(botPts[1], botPts[0], topPts[0], topPts[1])
                    1 -> listOf(botPts[0], botPts[7], cornerPts[0])
                    2 -> listOf(botPts[7], botPts[6], topPts[6], topPts[7])
                    3 -> listOf(botPts[6], botPts[5], cornerPts[3])
                    4 -> listOf(botPts[5], botPts[4], topPts[4], topPts[5])
                    5 -> listOf(botPts[4], botPts[3], cornerPts[2])
                    6 -> listOf(botPts[3], botPts[2], topPts[2], topPts[3])
                    7 -> listOf(botPts[2], botPts[1], cornerPts[1])
                    8 -> listOf(topPts[0], topPts[2], topPts[4], topPts[6])
                    else -> listOf(botPts[0], botPts[2], botPts[4], botPts[6])
                }

                if (facetPoly.all { it != null }) {
                    val path = Path().apply {
                        moveTo(facetPoly[0]!!.x, facetPoly[0]!!.y)
                        for (idx in 1 until facetPoly.size) {
                            lineTo(facetPoly[idx]!!.x, facetPoly[idx]!!.y)
                        }
                        close()
                    }
                    canvas.drawPath(path, facetPaint)
                }

                // 5. Draw 3D Face Reticle on Active Target Center
                val desc = getFacetDescriptor(currentStepIdx)
                val centerWorld = localToWorld(desc.centerX, desc.centerY, desc.centerZ, anchorPose)
                val reticlePt = projectPoint(centerWorld, screenW, screenH)

                if (reticlePt != null) {
                    val reticleColor = if (isAligned) Color.parseColor("#3FB950") else Color.parseColor("#58A6FF")
                    reticlePaint.color = reticleColor
                    fillReticlePaint.color = reticleColor

                    canvas.drawCircle(reticlePt.x, reticlePt.y, 32f, reticlePaint)
                    canvas.drawCircle(reticlePt.x, reticlePt.y, 8f, fillReticlePaint)
                    canvas.drawLine(reticlePt.x - 45f, reticlePt.y, reticlePt.x + 45f, reticlePt.y, reticlePaint)
                    canvas.drawLine(reticlePt.x, reticlePt.y - 45f, reticlePt.x, reticlePt.y + 45f, reticlePaint)
                }
            }
        }

        private fun drawEdge(canvas: Canvas, p1: PointF?, p2: PointF?) {
            if (p1 != null && p2 != null) {
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, wirePaint)
            }
        }

        private fun projectPoint(x: Float, y: Float, z: Float, screenW: Float, screenH: Float): PointF? {
            return projectPoint(floatArrayOf(x, y, z), screenW, screenH)
        }

        private fun projectPoint(worldCoords: FloatArray, screenW: Float, screenH: Float): PointF? {
            val clipVec = FloatArray(4)
            Matrix.multiplyMV(clipVec, 0, vpMatrix, 0, floatArrayOf(worldCoords[0], worldCoords[1], worldCoords[2], 1.0f), 0)
            val w = clipVec[3]
            if (w <= 0.01f) return null
            val ndcX = clipVec[0] / w
            val ndcY = clipVec[1] / w
            val sx = (ndcX * 0.5f + 0.5f) * screenW
            val sy = (-ndcY * 0.5f + 0.5f) * screenH
            return PointF(sx, sy)
        }
    }
}
