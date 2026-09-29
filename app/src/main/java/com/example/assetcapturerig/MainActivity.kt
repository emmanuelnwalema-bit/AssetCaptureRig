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
import android.widget.*
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
import java.io.File
import java.io.FileOutputStream
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
    private lateinit var batchUploadBtn: Button
    private lateinit var actionButton: Button

    private lateinit var previewScrollView: HorizontalScrollView
    private lateinit var previewContainer: LinearLayout

    private lateinit var fullPreviewLayout: FrameLayout
    private lateinit var fullImageView: ImageView
    private lateinit var fullPreviewLabel: TextView
    private lateinit var closePreviewBtn: Button

    private val httpClient = OkHttpClient()
    private val modalEndpoint = "https://emmanuelnwalema--mobile-6dof-capture-ui.modal.run/upload_plate"

    private var toneGen: ToneGenerator? = null

    private var currentStepIdx = 0
    private var isBoxPlaced = false
    private var isCapturing = false
    private var isBatchUploading = false
    private var lastFrame: Frame? = null
    private var prismAnchor: Anchor? = null
    private var initialAzimuth = 0f
    private var alignStartTime: Long? = null
    private var cooldownUntil: Long = 0L

    private var prismWidth = 0.18f
    private var prismDepth = 0.18f
    private var prismHeight = 0.14f

    private val capturedThumbnails = mutableMapOf<Int, Bitmap>()
    private val previewCardViews = mutableListOf<ImageView>()

    data class SequenceStep(
        val id: String,
        val label: String,
        val hint: String,
        val targetTilt: Int,
        val targetOrbit: Int
    )

    private val sequence = listOf(
        SequenceStep("front", "1. FRONT (0° LEVEL)", "AIM LEVEL AT FRONT", 0, 0),
        SequenceStep("hero_3_4", "2. HERO SEED (45° / 30° TILT)", "ORBIT 45° & TILT 30° DOWN", 30, 45),
        SequenceStep("right", "3. RIGHT PROFILE (0° LEVEL)", "AIM LEVEL AT RIGHT", 0, 90),
        SequenceStep("back_r", "4. REAR-RIGHT (30° TILT)", "ORBIT 135° & TILT 30° DOWN", 30, 135),
        SequenceStep("back", "5. BACK VIEW (0° LEVEL)", "AIM LEVEL AT REAR", 0, 180),
        SequenceStep("back_l", "6. REAR-LEFT (30° TILT)", "ORBIT 225° & TILT 30° DOWN", 30, 225),
        SequenceStep("left", "7. LEFT PROFILE (0° LEVEL)", "AIM LEVEL AT LEFT", 0, 270),
        SequenceStep("front_l", "8. FRONT-LEFT (30° TILT)", "ORBIT 315° & TILT 30° DOWN", 30, 315),
        SequenceStep("top", "9. TOP OVERHEAD (90° DOWN)", "HOLD OVERHEAD LOOKING DOWN", 90, 0),
        SequenceStep("bottom", "10. UNDERSIDE (60° UP)", "AIM UPWARD AT ASSET BASE", -60, 0)
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
        batchUploadBtn = findViewById(R.id.batchUploadBtn)
        actionButton = findViewById(R.id.actionButton)

        previewScrollView = findViewById(R.id.previewScrollView)
        previewContainer = findViewById(R.id.previewContainer)

        fullPreviewLayout = findViewById(R.id.fullPreviewLayout)
        fullImageView = findViewById(R.id.fullImageView)
        fullPreviewLabel = findViewById(R.id.fullPreviewLabel)
        closePreviewBtn = findViewById(R.id.closePreviewBtn)

        try {
            toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {}

        overlayView = PrismOverlayView(this)
        rootLayout.addView(overlayView, 1)

        buildThumbnailStrip()

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
            if (!isCapturing && currentStepIdx < sequence.size) {
                triggerLocalCapture()
            }
        }

        batchUploadBtn.setOnClickListener {
            if (!isBatchUploading && capturedThumbnails.isNotEmpty()) {
                startBatchUpload()
            }
        }

        closePreviewBtn.setOnClickListener {
            fullPreviewLayout.visibility = View.GONE
        }

        btnScaleUp.setOnClickListener { scalePrism(1.08f) }
        btnScaleDown.setOnClickListener { scalePrism(0.92f) }

        updateChecklistUI()
        updateScaleLabels()
    }

    private fun buildThumbnailStrip() {
        previewContainer.removeAllViews()
        previewCardViews.clear()

        val dpWidth = (64 * resources.displayMetrics.density).toInt()
        val dpHeight = (64 * resources.displayMetrics.density).toInt()
        val dpMargin = (4 * resources.displayMetrics.density).toInt()

        for (i in sequence.indices) {
            val frame = FrameLayout(this)
            val params = LinearLayout.LayoutParams(dpWidth, dpHeight).apply {
                setMargins(dpMargin, 0, dpMargin, 0)
            }
            frame.layoutParams = params
            frame.setBackgroundColor(Color.parseColor("#21262D"))

            val iv = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }

            val badge = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.BOTTOM
                }
                text = "${i + 1}"
                textSize = 9f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#B30D1117"))
                gravity = android.view.Gravity.CENTER
            }

            frame.addView(iv)
            frame.addView(badge)

            frame.setOnClickListener {
                showFullPreview(i)
            }

            previewContainer.addView(frame)
            previewCardViews.add(iv)
        }
    }

    private fun showFullPreview(idx: Int) {
        val file = File(cacheDir, "plate_${sequence[idx].id}.jpg")
        if (file.exists()) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            fullImageView.setImageBitmap(bmp)
            fullPreviewLabel.text = "${sequence[idx].label} (LOCAL FILE SAVED)"
            fullPreviewLayout.visibility = View.VISIBLE
        } else {
            Toast.makeText(this, "Facet ${idx + 1} not captured yet", Toast.LENGTH_SHORT).show()
        }
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
            val icon = if (capturedThumbnails.containsKey(i)) "🟩" else if (i == currentStepIdx) "▶" else "⬜"
            sbL.append("$icon ${sequence[i].label.substring(3)}\n")
        }
        for (i in 5 until 10) {
            val icon = if (capturedThumbnails.containsKey(i)) "🟩" else if (i == currentStepIdx) "▶" else "⬜"
            sbR.append("$icon ${sequence[i].label.substring(3)}\n")
        }
        chkListLeft.text = sbL.toString().trimEnd()
        chkListRight.text = sbR.toString().trimEnd()

        val capturedCount = capturedThumbnails.size
        batchUploadBtn.text = if (capturedCount == 10) "UPLOAD ALL 10 PLATES TO MODAL" else "UPLOAD PLATES TO MODAL ($capturedCount/10)"
        batchUploadBtn.visibility = if (capturedCount > 0) View.VISIBLE else View.GONE
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
            val camInAnchor = anchorPose.inverse().transformPoint(floatArrayOf(camPose.tx(), camPose.ty(), camPose.tz()))
            initialAzimuth = atan2(camInAnchor[0], camInAnchor[2])

            isBoxPlaced = true

            // 1. Initial Point-Cloud Sizing for Height
            autoFitToObjectPointCloud(frame, anchor)

            // 2. Immediate On-Device 2D-OBB-to-ARCore Alignment (<30ms)
            performOnDeviceObbAlignment(frame, anchor)

            sceneView.planeRenderer.isEnabled = false
            sceneView.planeRenderer.isVisible = false

            actionButton.visibility = View.GONE
            scaleBar.visibility = View.VISIBLE
            previewScrollView.visibility = View.VISIBLE
            forceSnapBtn.visibility = View.VISIBLE
            forceSnapBtn.text = "FORCE SNAP [${sequence[currentStepIdx].id.uppercase(Locale.US)}]"
            targetBadge.text = sequence[currentStepIdx].label
            updateChecklistUI()
            updateScaleLabels()
            overlayView.postInvalidate()

            try {
                toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 140)
            } catch (e: Exception) {}
        } else {
            Toast.makeText(this, "Pan phone to scan table surface first", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * High-speed on-device Oriented Bounding Box (OBB) & Plane Projection.
     * Takes ~15-25ms. Runs directly on CPU without network calls or external models.
     */
    private fun performOnDeviceObbAlignment(frame: Frame, anchor: Anchor) {
        val sWidth = sceneView.width
        val sHeight = sceneView.height
        if (sWidth <= 0 || sHeight <= 0) return

        val bitmap = Bitmap.createBitmap(sWidth, sHeight, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            sceneView,
            bitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    Thread {
                        analyzeObbFromBitmap(bitmap, frame, anchor)
                    }.start()
                }
            },
            Handler(Looper.getMainLooper())
        )
    }

    private fun analyzeObbFromBitmap(fullBmp: Bitmap, frame: Frame, anchor: Anchor) {
        try {
            val cropSize = 240
            val startX = (fullBmp.width - cropSize) / 2
            val startY = (fullBmp.height - cropSize) / 2
            val roi = Bitmap.createBitmap(fullBmp, startX, startY, cropSize, cropSize)

            // Sample border pixels to establish background table color
            var bgR = 0L; var bgG = 0L; var bgB = 0L; var borderSamples = 0
            for (i in 0 until cropSize step 8) {
                val pTop = roi.getPixel(i, 0)
                val pBot = roi.getPixel(i, cropSize - 1)
                val pL = roi.getPixel(0, i)
                val pR = roi.getPixel(cropSize - 1, i)
                bgR += Color.red(pTop) + Color.red(pBot) + Color.red(pL) + Color.red(pR)
                bgG += Color.green(pTop) + Color.green(pBot) + Color.green(pL) + Color.green(pR)
                bgB += Color.blue(pTop) + Color.blue(pBot) + Color.blue(pL) + Color.blue(pR)
                borderSamples += 4
            }
            val avgBgR = (bgR / borderSamples).toInt()
            val avgBgG = (bgG / borderSamples).toInt()
            val avgBgB = (bgB / borderSamples).toInt()

            // Calculate foreground spatial moments (PCA)
            var sumX = 0.0
            var sumY = 0.0
            var count = 0
            val fgPointsX = IntArrayList(1024)
            val fgPointsY = IntArrayList(1024)

            for (y in 12 until cropSize - 12 step 2) {
                for (x in 12 until cropSize - 12 step 2) {
                    val px = roi.getPixel(x, y)
                    val dR = Color.red(px) - avgBgR
                    val dG = Color.green(px) - avgBgG
                    val dB = Color.blue(px) - avgBgB
                    val colorDist = sqrt((dR * dR + dG * dG + dB * dB).toDouble())

                    // Difference threshold from table surface
                    if (colorDist > 32.0) {
                        sumX += x
                        sumY += y
                        fgPointsX.add(x)
                        fgPointsY.add(y)
                        count++
                    }
                }
            }

            if (count < 30) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Asset blended with table: manual scale active", Toast.LENGTH_SHORT).show()
                }
                return
            }

            val cX = sumX / count
            val cY = sumY / count

            // Second central moments (covariance matrix)
            var mu20 = 0.0
            var mu02 = 0.0
            var mu11 = 0.0
            for (i in 0 until count) {
                val dx = fgPointsX.get(i) - cX
                val dy = fgPointsY.get(i) - cY
                mu20 += dx * dx
                mu02 += dy * dy
                mu11 += dx * dy
            }

            // 2D Principal Orientation Angle (Image Plane)
            val theta2D = 0.5 * atan2(2 * mu11, mu20 - mu02)
            val cosT = cos(theta2D)
            val sinT = sin(theta2D)

            // Measure extent along principal and orthogonal axes
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for (i in 0 until count) {
                val dx = fgPointsX.get(i) - cX
                val dy = fgPointsY.get(i) - cY
                val u = dx * cosT + dy * sinT
                val v = -dx * sinT + dy * cosT
                minU = min(minU, u)
                maxU = max(maxU, u)
                minV = min(minV, v)
                maxV = max(maxV, v)
            }

            val spanMajor = maxU - minU
            val spanMinor = maxV - minV
            val aspectRatio = (spanMajor / max(spanMinor, 1.0)).coerceIn(0.5, 2.5).toFloat()

            // Raycast two sample points on principal axis to ARCore table plane
            val screenCenterX = fullBmp.width / 2f
            val screenCenterY = fullBmp.height / 2f
            val pMajorX = screenCenterX + (cosT * 80.0).toFloat()
            val pMajorY = screenCenterY + (sinT * 80.0).toFloat()

            val hitCenter = frame.hitTest(screenCenterX, screenCenterY).firstOrNull()
            val hitMajor = frame.hitTest(pMajorX, pMajorY).firstOrNull()

            var groundYawOffsetDeg = 0.0f
            if (hitCenter != null && hitMajor != null) {
                val invAnchor = anchor.pose.inverse()
                val p0 = invAnchor.transformPoint(floatArrayOf(hitCenter.hitPose.tx(), hitCenter.hitPose.ty(), hitCenter.hitPose.tz()))
                val p1 = invAnchor.transformPoint(floatArrayOf(hitMajor.hitPose.tx(), hitMajor.hitPose.ty(), hitMajor.hitPose.tz()))

                val vX = p1[0] - p0[0]
                val vZ = p1[2] - p0[2]
                val localAngle = atan2(vX, vZ)
                groundYawOffsetDeg = Math.toDegrees(localAngle.toDouble()).toFloat()
            }

            runOnUiThread {
                initialAzimuth += Math.toRadians(groundYawOffsetDeg.toDouble()).toFloat()

                if (aspectRatio > 1.08f) {
                    prismWidth *= sqrt(aspectRatio)
                    prismDepth /= sqrt(aspectRatio)
                } else if (aspectRatio < 0.92f) {
                    prismWidth /= sqrt(1f / aspectRatio)
                    prismDepth *= sqrt(1f / aspectRatio)
                }

                prismWidth = prismWidth.coerceIn(0.08f, 0.45f)
                prismDepth = prismDepth.coerceIn(0.08f, 0.45f)

                updateScaleLabels()
                overlayView.postInvalidate()

                val sign = if (groundYawOffsetDeg >= 0) "+" else ""
                Toast.makeText(
                    this@MainActivity,
                    "✓ 2D-OBB Aligned (${(prismWidth * 100).roundToInt()}×${(prismDepth * 100).roundToInt()}cm | ${sign}${String.format(Locale.US, "%.1f", groundYawOffsetDeg)}°)",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e("OBB", "2D-OBB processing skipped: ${e.message}")
        }
    }

    private fun autoFitToObjectPointCloud(frame: Frame, anchor: Anchor) {
        try {
            val pointCloud = frame.acquirePointCloud()
            val pointsBuffer = pointCloud.points
            val count = pointsBuffer.remaining() / 4
            val anchorPose = anchor.pose
            val invAnchor = anchorPose.inverse()

            val cosA = cos(-initialAzimuth)
            val sinA = sin(-initialAzimuth)

            var maxY = 0.0f
            var clusterCount = 0

            for (i in 0 until count) {
                val wx = pointsBuffer.get(i * 4)
                val wy = pointsBuffer.get(i * 4 + 1)
                val wz = pointsBuffer.get(i * 4 + 2)
                val conf = pointsBuffer.get(i * 4 + 3)

                if (conf < 0.15f) continue

                val pLocal = invAnchor.transformPoint(floatArrayOf(wx, wy, wz))
                val lx = pLocal[0]
                val ly = pLocal[1]
                val lz = pLocal[2]

                val rx = lx * cosA + lz * sinA
                val rz = -lx * sinA + lz * cosA

                val horizontalDist = sqrt(rx * rx + rz * rz)
                if (ly in 0.008f..0.45f && horizontalDist <= 0.35f) {
                    maxY = max(maxY, ly)
                    clusterCount++
                }
            }

            pointCloud.close()

            if (clusterCount >= 3 && maxY > 0.012f) {
                prismHeight = (maxY * 1.15f + 0.015f).coerceIn(0.04f, 0.35f)
            }
        } catch (e: Exception) {
            Log.e("AutoFit", "Point cloud auto-dimensioning skipped: ${e.message}")
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

        if (currentStepIdx >= sequence.size) {
            overlayView.postInvalidate()
            return
        }

        val anchor = prismAnchor ?: return
        val anchorPose = anchor.pose
        val camPose = camera.pose
        val step = sequence[currentStepIdx]

        val desc = getFacetDescriptor(currentStepIdx)
        val faceCenterWorld = localToWorld(desc.centerX, desc.centerY, desc.centerZ, anchorPose)
        val faceNormalWorld = normalToWorld(desc.normX, desc.normY, desc.normZ, anchorPose)

        val camToFaceX = camPose.tx() - faceCenterWorld[0]
        val camToFaceY = camPose.ty() - faceCenterWorld[1]
        val camToFaceZ = camPose.tz() - faceCenterWorld[2]
        val facingDot = camToFaceX * faceNormalWorld[0] + camToFaceY * faceNormalWorld[1] + camToFaceZ * faceNormalWorld[2]
        val isFacingCamera = facingDot > 0.001f

        val toFaceVecX = faceCenterWorld[0] - camPose.tx()
        val toFaceVecY = faceCenterWorld[1] - camPose.ty()
        val toFaceVecZ = faceCenterWorld[2] - camPose.tz()
        val distMeters = sqrt(toFaceVecX * toFaceVecX + toFaceVecY * toFaceVecY + toFaceVecZ * toFaceVecZ)
        val distCm = distMeters * 100f
        val isDistanceOptimal = distCm in 20.0f..38.0f

        val camForward = floatArrayOf(-camPose.zAxis[0], -camPose.zAxis[1], -camPose.zAxis[2])
        val actualTiltDeg = Math.toDegrees(asin((-camForward[1]).coerceIn(-1.0f, 1.0f).toDouble())).roundToInt()

        val dotNormal = -(camForward[0] * faceNormalWorld[0] + camForward[1] * faceNormalWorld[1] + camForward[2] * faceNormalWorld[2])
        val normAngleErr = Math.toDegrees(acos(dotNormal.coerceIn(-1.0f, 1.0f).toDouble())).toFloat()
        val isNormalAligned = isFacingCamera && (normAngleErr <= 10.0f)

        val safeDist = if (distMeters > 0.001f) distMeters else 1.0f
        val toFaceDirX = toFaceVecX / safeDist
        val toFaceDirY = toFaceVecY / safeDist
        val toFaceDirZ = toFaceVecZ / safeDist
        val dotCenter = camForward[0] * toFaceDirX + camForward[1] * toFaceDirY + camForward[2] * toFaceDirZ
        val centAngleErr = Math.toDegrees(acos(dotCenter.coerceIn(-1.0f, 1.0f).toDouble())).toFloat()
        val isCentered = isFacingCamera && (centAngleErr <= 12.0f)

        val isReadyToCapture = isNormalAligned && isCentered && isDistanceOptimal

        runOnUiThread {
            valDist.text = "DIST: ${distCm.roundToInt()}cm"
            valAlign.text = "TILT: ${actualTiltDeg}° [${step.targetTilt}°]"

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

            if (!isFacingCamera) {
                normalIndicator.text = "MOVE TO FRONT OF ${step.label}"
                normalIndicator.setTextColor(Color.parseColor("#F85149"))
                centerIndicator.text = "FACET FACING AWAY"
                centerIndicator.setTextColor(Color.parseColor("#8B949E"))
            } else {
                if (isNormalAligned) {
                    normalIndicator.text = "TILT: ${actualTiltDeg}° | ALIGN: ${String.format(Locale.US, "%.1f", normAngleErr)}° (LOCKED)"
                    normalIndicator.setTextColor(Color.parseColor("#3FB950"))
                } else {
                    normalIndicator.text = "TILT: ${actualTiltDeg}° [AIM ${step.targetTilt}°] | DEV: ${String.format(Locale.US, "%.1f", normAngleErr)}°"
                    normalIndicator.setTextColor(Color.parseColor("#F85149"))
                }

                if (isCentered) {
                    centerIndicator.text = "BULLSEYE CENTERED (OFFSET: ${String.format(Locale.US, "%.1f", centAngleErr)}°)"
                    centerIndicator.setTextColor(Color.parseColor("#3FB950"))
                } else {
                    centerIndicator.text = "AIM AT RETICLE (OFFSET: ${String.format(Locale.US, "%.1f", centAngleErr)}°)"
                    centerIndicator.setTextColor(Color.parseColor("#8B949E"))
                }
            }
        }

        val now = System.currentTimeMillis()
        if (isReadyToCapture && !isCapturing && now >= cooldownUntil) {
            overlayView.isAligned = true
            if (alignStartTime == null) {
                alignStartTime = now
            } else if (now - alignStartTime!! >= 450) {
                alignStartTime = null
                triggerLocalCapture()
            }
        } else {
            if (!isCapturing) {
                overlayView.isAligned = false
                alignStartTime = null
            }
        }

        overlayView.postInvalidate()
    }

    private fun triggerLocalCapture() {
        if (isCapturing || currentStepIdx >= sequence.size) return
        isCapturing = true

        val snappedStepIdx = currentStepIdx
        val step = sequence[snappedStepIdx]

        playSnapFeedback()

        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            sceneView,
            bitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    Thread {
                        try {
                            val file = File(cacheDir, "plate_${step.id}.jpg")
                            val fos = FileOutputStream(file)
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                            fos.flush()
                            fos.close()

                            val thumb = Bitmap.createScaledBitmap(bitmap, 128, 128, false)
                            capturedThumbnails[snappedStepIdx] = thumb

                            runOnUiThread {
                                previewCardViews[snappedStepIdx].setImageBitmap(thumb)

                                currentStepIdx++
                                isCapturing = false
                                cooldownUntil = System.currentTimeMillis() + 800L
                                alignStartTime = null
                                updateChecklistUI()

                                if (currentStepIdx >= sequence.size) {
                                    targetBadge.text = "✓ ALL 10 PLATES STORED LOCALLY"
                                    targetBadge.setTextColor(Color.parseColor("#3FB950"))
                                    distIndicator.text = "CAPTURE COMPLETE"
                                    distIndicator.setTextColor(Color.parseColor("#3FB950"))
                                    normalIndicator.text = "READY FOR MODAL BATCH UPLOAD"
                                    centerIndicator.text = "TAP UPLOAD BELOW"
                                    forceSnapBtn.visibility = View.GONE
                                    scaleBar.visibility = View.GONE
                                } else {
                                    targetBadge.text = sequence[currentStepIdx].label
                                    forceSnapBtn.text = "FORCE SNAP [${sequence[currentStepIdx].id.uppercase(Locale.US)}]"
                                }
                                overlayView.postInvalidate()
                            }
                        } catch (e: Exception) {
                            Log.e("Capture", "Error saving plate locally", e)
                            isCapturing = false
                        }
                    }.start()
                } else {
                    isCapturing = false
                }
            },
            Handler(Looper.getMainLooper())
        )
    }

    private fun startBatchUpload() {
        isBatchUploading = true
        batchUploadBtn.isEnabled = false
        batchUploadBtn.text = "UPLOADING 0/${capturedThumbnails.size}..."

        Thread {
            val total = capturedThumbnails.size
            var uploaded = 0

            for (entry in capturedThumbnails.entries.sortedBy { it.key }) {
                val idx = entry.key
                val step = sequence[idx]
                val file = File(cacheDir, "plate_${step.id}.jpg")
                if (!file.exists()) continue

                val bytes = file.readBytes()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

                val json = JSONObject().apply {
                    put("target_id", step.id)
                    put("b64", b64)
                    put("dimensions", JSONObject().apply {
                        put("w", prismWidth)
                        put("h", prismHeight)
                        put("d", prismDepth)
                    })
                }

                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(modalEndpoint).post(body).build()

                try {
                    val response = httpClient.newCall(request).execute()
                    response.close()
                    uploaded++
                    runOnUiThread {
                        batchUploadBtn.text = "UPLOADING $uploaded/$total..."
                    }
                } catch (e: Exception) {
                    Log.e("BatchUpload", "Failed on ${step.id}", e)
                }
            }

            runOnUiThread {
                isBatchUploading = false
                batchUploadBtn.isEnabled = true
                batchUploadBtn.text = "✓ ALL $uploaded PLATES ON MODAL VOLUME"
                batchUploadBtn.setBackgroundColor(Color.parseColor("#238636"))
                Toast.makeText(this, "Batch upload complete ($uploaded plates)", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun playSnapFeedback() {
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: Exception) {}

        snapFlash.alpha = 0.5f
        snapFlash.visibility = View.VISIBLE
        snapFlash.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { snapFlash.visibility = View.GONE }
            .start()
    }

    data class FacetDesc(
        val centerX: Float, val centerY: Float, val centerZ: Float,
        val normX: Float, val normY: Float, val normZ: Float,
        val isChamfer: Boolean
    )

    private fun getFacetDescriptor(step: Int): FacetDesc {
        val cos30 = cos(Math.toRadians(30.0)).toFloat()
        val sin30 = sin(Math.toRadians(30.0)).toFloat()
        val sqrt2Inv = 0.7071f
        val hW = prismWidth / 2f
        val hD = prismDepth / 2f
        val hMid = prismHeight * 0.50f
        val hChamferY = hMid + (prismHeight - hMid) * 0.50f

        return when (step) {
            0 -> FacetDesc(0f, hMid * 0.5f, hD, 0f, 0f, 1f, false)
            1 -> FacetDesc(hW * 0.76f, hChamferY, hD * 0.76f, cos30 * sqrt2Inv, sin30, cos30 * sqrt2Inv, true)
            2 -> FacetDesc(hW, hMid * 0.5f, 0f, 1f, 0f, 0f, false)
            3 -> FacetDesc(hW * 0.76f, hChamferY, -hD * 0.76f, cos30 * sqrt2Inv, sin30, -cos30 * sqrt2Inv, true)
            4 -> FacetDesc(0f, hMid * 0.5f, -hD, 0f, 0f, -1f, false)
            5 -> FacetDesc(-hW * 0.76f, hChamferY, -hD * 0.76f, -cos30 * sqrt2Inv, sin30, -cos30 * sqrt2Inv, true)
            6 -> FacetDesc(-hW, hMid * 0.5f, 0f, -1f, 0f, 0f, false)
            7 -> FacetDesc(-hW * 0.76f, hChamferY, hD * 0.76f, -cos30 * sqrt2Inv, sin30, -cos30 * sqrt2Inv, true)
            8 -> FacetDesc(0f, prismHeight, 0f, 0f, 1f, 0f, false)
            else -> FacetDesc(0f, 0f, 0f, 0f, -1f, 0f, false)
        }
    }

    private fun rotateByAzimuth(x: Float, y: Float, z: Float): FloatArray {
        val c = cos(initialAzimuth)
        val s = sin(initialAzimuth)
        return floatArrayOf(x * c + z * s, y, -x * s + z * c)
    }

    private fun localToWorld(lx: Float, ly: Float, lz: Float, anchorPose: com.google.ar.core.Pose): FloatArray {
        val rotated = rotateByAzimuth(lx, ly, lz)
        return anchorPose.transformPoint(rotated)
    }

    private fun normalToWorld(nx: Float, ny: Float, nz: Float, anchorPose: com.google.ar.core.Pose): FloatArray {
        val rotated = rotateByAzimuth(nx, ny, nz)
        return anchorPose.rotateVector(rotated)
    }

    // Lightweight primitive list to avoid boxing overhead in PCA calculation loop
    private class IntArrayList(capacity: Int = 512) {
        var data = IntArray(capacity)
        var size = 0
        fun add(element: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = element
        }
        fun get(index: Int): Int = data[index]
    }

    inner class PrismOverlayView(context: Context) : View(context) {

        var isAligned = false

        private val wirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }

        private val perforatedMeshPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }

        private val fillReticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val centerGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        private val viewMatrix = FloatArray(16)
        private val projMatrix = FloatArray(16)
        private val vpMatrix = FloatArray(16)

        private val redMeshShader = createPerforatedShader(Color.parseColor("#F85149"))
        private val greenMeshShader = createPerforatedShader(Color.parseColor("#3FB950"))
        private val greyMeshShader = createPerforatedShader(Color.parseColor("#8B949E"))

        private fun createPerforatedShader(baseColor: Int): BitmapShader {
            val tileSize = 24
            val bmp = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)

            val bodyPaint = Paint().apply {
                color = Color.argb(175, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                style = Paint.Style.FILL
            }
            cv.drawRect(0f, 0f, tileSize.toFloat(), tileSize.toFloat(), bodyPaint)

            val clearHolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            val holeR = 5.2f
            cv.drawCircle(tileSize / 2f, tileSize / 2f, holeR, clearHolePaint)
            cv.drawCircle(0f, 0f, holeR, clearHolePaint)
            cv.drawCircle(tileSize.toFloat(), 0f, holeR, clearHolePaint)
            cv.drawCircle(0f, tileSize.toFloat(), holeR, clearHolePaint)
            cv.drawCircle(tileSize.toFloat(), tileSize.toFloat(), holeR, clearHolePaint)

            val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(90, 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = 1.0f
            }
            cv.drawCircle(tileSize / 2f, tileSize / 2f, holeR, rimPaint)
            cv.drawCircle(0f, 0f, holeR, rimPaint)
            cv.drawCircle(tileSize.toFloat(), 0f, holeR, rimPaint)
            cv.drawCircle(0f, tileSize.toFloat(), holeR, rimPaint)
            cv.drawCircle(tileSize.toFloat(), tileSize.toFloat(), holeR, rimPaint)

            return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }

        private fun getMeshShaderForColor(color: Int): BitmapShader {
            return when (color) {
                Color.parseColor("#3FB950") -> greenMeshShader
                Color.parseColor("#8B949E") -> greyMeshShader
                else -> redMeshShader
            }
        }

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

            if (!isBoxPlaced) {
                val hitResults = frame.hitTest(screenW / 2f, screenH / 2f)
                val hit = hitResults.firstOrNull()
                if (hit != null) {
                    val p = projectPoint(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz(), screenW, screenH)
                    if (p != null) {
                        reticlePaint.color = Color.parseColor("#58A6FF")
                        canvas.drawCircle(p.x, p.y, 60f, reticlePaint)
                        canvas.drawCircle(p.x, p.y, 18f, reticlePaint)
                    }
                }
                return
            }

            val anchor = prismAnchor ?: return
            val anchorPose = anchor.pose

            canvas.drawCircle(screenW / 2f, screenH / 2f, 75f, centerGuidePaint)
            canvas.drawCircle(screenW / 2f, screenH / 2f, 8f, centerGuidePaint)

            val hW = prismWidth / 2f
            val hD = prismDepth / 2f
            val r = min(hW, hD) * 0.36f
            val hMid = prismHeight * 0.50f
            val tan30 = tan(Math.toRadians(30.0)).toFloat()
            val dChamfer = min((prismHeight - hMid) * tan30, r * 0.82f)
            val rTop = max(r - dChamfer * 0.45f, 0.02f)

            val baseLoop = generateFilletedSquircleLoop(hW, hD, r)
            val midLoop = generateFilletedSquircleLoop(hW, hD, r)
            val topLoop = generateFilletedSquircleLoop(hW - dChamfer, hD - dChamfer, rTop)

            val greyColor = Color.parseColor("#8B949E")
            val activeRed = Color.parseColor("#F85149")
            val capturedGreen = Color.parseColor("#3FB950")

            // Lower Tier
            for (sector in 0 until 4) {
                val facetIdx = sector * 2
                val isCaptured = capturedThumbnails.containsKey(facetIdx)
                val isActiveStep = currentStepIdx == facetIdx
                val wallColor = when {
                    isCaptured -> capturedGreen
                    isActiveStep && isAligned -> capturedGreen
                    else -> activeRed
                }
                drawFilletedWallSector(canvas, sector, baseLoop, midLoop, 0f, hMid, anchorPose, screenW, screenH, wallColor, true)
                drawFilletedCornerSector(canvas, sector, baseLoop, midLoop, 0f, hMid, anchorPose, screenW, screenH, greyColor, false)
            }

            // Upper Tier
            for (sector in 0 until 4) {
                drawFilletedWallSector(canvas, sector, midLoop, topLoop, hMid, prismHeight, anchorPose, screenW, screenH, greyColor, false)

                val facetIdx = sector * 2 + 1
                val isCaptured = capturedThumbnails.containsKey(facetIdx)
                val isActiveStep = currentStepIdx == facetIdx
                val chamferColor = when {
                    isCaptured -> capturedGreen
                    isActiveStep && isAligned -> capturedGreen
                    else -> activeRed
                }
                drawFilletedCornerSector(canvas, sector, midLoop, topLoop, hMid, prismHeight, anchorPose, screenW, screenH, chamferColor, true)
            }

            // Top Cap
            val isTopCaptured = capturedThumbnails.containsKey(8)
            val isTopActiveStep = currentStepIdx == 8
            val topColor = when {
                isTopCaptured -> capturedGreen
                isTopActiveStep && isAligned -> capturedGreen
                else -> activeRed
            }
            drawTopCap(canvas, topLoop, prismHeight, anchorPose, screenW, screenH, topColor, true)

            // Reticle
            if (currentStepIdx < sequence.size) {
                val desc = getFacetDescriptor(currentStepIdx)
                val faceCenterWorld = localToWorld(desc.centerX, desc.centerY, desc.centerZ, anchorPose)
                val faceNormalWorld = normalToWorld(desc.normX, desc.normY, desc.normZ, anchorPose)

                val camPose = camera.pose
                val camToFaceX = camPose.tx() - faceCenterWorld[0]
                val camToFaceY = camPose.ty() - faceCenterWorld[1]
                val camToFaceZ = camPose.tz() - faceCenterWorld[2]
                val facingDot = camToFaceX * faceNormalWorld[0] + camToFaceY * faceNormalWorld[1] + camToFaceZ * faceNormalWorld[2]

                if (facingDot > 0.001f) {
                    drawPlanar3DReticle(canvas, desc, anchorPose, screenW, screenH)
                }
            }
        }

        private fun generateFilletedSquircleLoop(hW: Float, hD: Float, r: Float): List<FloatArray> {
            val loop = mutableListOf<FloatArray>()
            val xS = hW - r
            val zS = hD - r

            for (quad in 0 until 4) {
                when (quad) {
                    0 -> { loop.add(floatArrayOf(-xS, 0f, hD)); loop.add(floatArrayOf(xS, 0f, hD)) }
                    1 -> { loop.add(floatArrayOf(hW, 0f, zS)); loop.add(floatArrayOf(hW, 0f, -zS)) }
                    2 -> { loop.add(floatArrayOf(xS, 0f, -hD)); loop.add(floatArrayOf(-xS, 0f, -hD)) }
                    3 -> { loop.add(floatArrayOf(-hW, 0f, -zS)); loop.add(floatArrayOf(-hW, 0f, zS)) }
                }

                val cX = if (quad == 0 || quad == 1) xS else -xS
                val cZ = if (quad == 0 || quad == 3) zS else -zS
                val startAngle = Math.toRadians((90.0 - quad * 90.0))

                for (step in 1..4) {
                    val angle = startAngle - (Math.PI / 2.0) * (step / 5.0)
                    val px = cX + r * cos(angle).toFloat()
                    val pz = cZ + r * sin(angle).toFloat()
                    loop.add(floatArrayOf(px, 0f, pz))
                }
            }
            return loop
        }

        private fun drawFilletedWallSector(
            canvas: Canvas, sector: Int,
            base: List<FloatArray>, top: List<FloatArray>,
            y0: Float, y1: Float,
            anchorPose: com.google.ar.core.Pose,
            w: Float, h: Float, color: Int, isActive: Boolean
        ) {
            val i0 = sector * 6
            val i1 = (i0 + 1) % base.size

            val p0 = projectPoint(localToWorld(base[i0][0], y0, base[i0][2], anchorPose), w, h)
            val p1 = projectPoint(localToWorld(base[i1][0], y0, base[i1][2], anchorPose), w, h)
            val p2 = projectPoint(localToWorld(top[i1][0], y1, top[i1][2], anchorPose), w, h)
            val p3 = projectPoint(localToWorld(top[i0][0], y1, top[i0][2], anchorPose), w, h)

            if (p0 != null && p1 != null && p2 != null && p3 != null) {
                perforatedMeshPaint.shader = getMeshShaderForColor(color)
                val path = Path().apply {
                    moveTo(p0.x, p0.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); close()
                }
                canvas.drawPath(path, perforatedMeshPaint)

                wirePaint.color = color
                wirePaint.strokeWidth = if (isActive) 3.5f else 2.2f
                canvas.drawLine(p0.x, p0.y, p1.x, p1.y, wirePaint)
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, wirePaint)
                canvas.drawLine(p2.x, p2.y, p3.x, p3.y, wirePaint)
                canvas.drawLine(p3.x, p3.y, p0.x, p0.y, wirePaint)
            }
        }

        private fun drawFilletedCornerSector(
            canvas: Canvas, corner: Int,
            base: List<FloatArray>, top: List<FloatArray>,
            y0: Float, y1: Float,
            anchorPose: com.google.ar.core.Pose,
            w: Float, h: Float, color: Int, isActive: Boolean
        ) {
            val startIdx = corner * 6 + 1
            perforatedMeshPaint.shader = getMeshShaderForColor(color)

            wirePaint.color = color
            wirePaint.strokeWidth = if (isActive) 3.0f else 2.0f

            for (step in 0 until 5) {
                val iA = (startIdx + step) % base.size
                val iB = (startIdx + step + 1) % base.size

                val p0 = projectPoint(localToWorld(base[iA][0], y0, base[iA][2], anchorPose), w, h)
                val p1 = projectPoint(localToWorld(base[iB][0], y0, base[iB][2], anchorPose), w, h)
                val p2 = projectPoint(localToWorld(top[iB][0], y1, top[iB][2], anchorPose), w, h)
                val p3 = projectPoint(localToWorld(top[iA][0], y1, top[iA][2], anchorPose), w, h)

                if (p0 != null && p1 != null && p2 != null && p3 != null) {
                    val path = Path().apply {
                        moveTo(p0.x, p0.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); close()
                    }
                    canvas.drawPath(path, perforatedMeshPaint)

                    canvas.drawLine(p0.x, p0.y, p1.x, p1.y, wirePaint)
                    canvas.drawLine(p2.x, p2.y, p3.x, p3.y, wirePaint)
                    if (step == 0) canvas.drawLine(p0.x, p0.y, p3.x, p3.y, wirePaint)
                    if (step == 4) canvas.drawLine(p1.x, p1.y, p2.x, p2.y, wirePaint)
                }
            }
        }

        private fun drawTopCap(
            canvas: Canvas, top: List<FloatArray>, y: Float,
            anchorPose: com.google.ar.core.Pose,
            w: Float, h: Float, color: Int, fill: Boolean
        ) {
            val pts = top.map { projectPoint(localToWorld(it[0], y, it[2], anchorPose), w, h) }
            if (pts.all { it != null }) {
                val isTopCaptured = capturedThumbnails.containsKey(8)
                val isTopActiveStep = currentStepIdx == 8
                val isActive = isTopCaptured || isTopActiveStep

                if (fill) {
                    perforatedMeshPaint.shader = getMeshShaderForColor(color)
                    val path = Path().apply {
                        moveTo(pts[0]!!.x, pts[0]!!.y)
                        for (i in 1 until pts.size) lineTo(pts[i]!!.x, pts[i]!!.y)
                        close()
                    }
                    canvas.drawPath(path, perforatedMeshPaint)
                }

                wirePaint.color = color
                wirePaint.strokeWidth = if (isActive) 3.5f else 2.5f
                for (i in pts.indices) {
                    val next = (i + 1) % pts.size
                    canvas.drawLine(pts[i]!!.x, pts[i]!!.y, pts[next]!!.x, pts[next]!!.y, wirePaint)
                }
            }
        }

        private fun drawPlanar3DReticle(canvas: Canvas, desc: FacetDesc, anchorPose: com.google.ar.core.Pose, w: Float, h: Float) {
            val rColor = if (isAligned) Color.parseColor("#3FB950") else Color.parseColor("#F85149")
            reticlePaint.color = rColor
            fillReticlePaint.color = rColor

            val nx = desc.normX; val ny = desc.normY; val nz = desc.normZ
            val ux: Float; val uy: Float; val uz: Float

            if (abs(ny) > 0.95f) {
                ux = 1f; uy = 0f; uz = 0f
            } else {
                val len = sqrt(nz * nz + nx * nx)
                ux = nz / len; uy = 0f; uz = -nx / len
            }
            val vx = ny * uz - nz * uy
            val vy = nz * ux - nx * uz
            val vz = nx * uy - ny * ux

            val radius = min(prismWidth, prismDepth) * 0.20f

            val ringPts = mutableListOf<PointF?>()
            for (i in 0 until 16) {
                val angle = (2.0 * Math.PI * i / 16).toFloat()
                val px = desc.centerX + radius * (cos(angle) * ux + sin(angle) * vx) + nx * 0.003f
                val py = desc.centerY + radius * (cos(angle) * uy + sin(angle) * vy) + ny * 0.003f
                val pz = desc.centerZ + radius * (cos(angle) * uz + sin(angle) * vz) + nz * 0.003f
                ringPts.add(projectPoint(localToWorld(px, py, pz, anchorPose), w, h))
            }

            for (i in 0 until 16) {
                val next = (i + 1) % 16
                if (ringPts[i] != null && ringPts[next] != null) {
                    canvas.drawLine(ringPts[i]!!.x, ringPts[i]!!.y, ringPts[next]!!.x, ringPts[next]!!.y, reticlePaint)
                }
            }

            val ch = radius * 1.4f
            val h1 = projectPoint(localToWorld(desc.centerX - ux * ch + nx * 0.003f, desc.centerY - uy * ch + ny * 0.003f, desc.centerZ - uz * ch + nz * 0.003f, anchorPose), w, h)
            val h2 = projectPoint(localToWorld(desc.centerX + ux * ch + nx * 0.003f, desc.centerY + uy * ch + ny * 0.003f, desc.centerZ + uz * ch + nz * 0.003f, anchorPose), w, h)
            if (h1 != null && h2 != null) canvas.drawLine(h1.x, h1.y, h2.x, h2.y, reticlePaint)

            val centerPt = projectPoint(localToWorld(desc.centerX + nx * 0.003f, desc.centerY + ny * 0.003f, desc.centerZ + nz * 0.003f, anchorPose), w, h)
            if (centerPt != null) canvas.drawCircle(centerPt.x, centerPt.y, 14f, fillReticlePaint)
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
