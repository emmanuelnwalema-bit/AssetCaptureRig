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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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
        } catch (_: Exception) {}

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

            sceneView.planeRenderer.isEnabled = false
            sceneView.planeRenderer.isVisible = false

            actionButton.visibility = View.GONE
            scaleBar.visibility = View.VISIBLE
            previewScrollView.visibility = View.VISIBLE
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

        // 1. Backface Culling check
        val camToFaceX = camPose.tx() - faceCenterWorld[0]
        val camToFaceY = camPose.ty() - faceCenterWorld[1]
        val camToFaceZ = camPose.tz() - faceCenterWorld[2]
        val facingDot = camToFaceX * faceNormalWorld[0] + camToFaceY * faceNormalWorld[1] + camToFaceZ * faceNormalWorld[2]
        val isFacingCamera = facingDot > 0.001f

        // 2. Focal Distance
        val toFaceVecX = faceCenterWorld[0] - camPose.tx()
        val toFaceVecY = faceCenterWorld[1] - camPose.ty()
        val toFaceVecZ = faceCenterWorld[2] - camPose.tz()
        val distMeters = sqrt(toFaceVecX * toFaceVecX + toFaceVecY * toFaceVecY + toFaceVecZ * toFaceVecZ)
        val distCm = distMeters * 100f
        val isDistanceOptimal = distCm in 20.0f..38.0f

        val camForward = floatArrayOf(-camPose.zAxis[0], -camPose.zAxis[1], -camPose.zAxis[2])
        val actualTiltDeg = Math.toDegrees(asin((-camForward[1]).coerceIn(-1.0f, 1.0f).toDouble())).roundToInt()

        // 3. Collimation Alignment Error
        val dotNormal = -(camForward[0] * faceNormalWorld[0] + camForward[1] * faceNormalWorld[1] + camForward[2] * faceNormalWorld[2])
        val normAngleErr = Math.toDegrees(acos(dotNormal.coerceIn(-1.0f, 1.0f).toDouble())).toFloat()
        val isNormalAligned = isFacingCamera && (normAngleErr <= 10.0f)

        // 4. Centering Angle
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
        PixelCopy.request(sceneView, bitmap, { copyResult ->
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
        }, Handler(Looper.getMainLooper()))
    }

    private fun startBatchUpload() {
        isBatchUploading = true
        batchUploadBtn.isEnabled = false
        batchUploadBtn.text = "UPLOADING 0/${capturedThumbnails.size}..."

        Thread {
            val total = capturedThumbnails.size
            var uploaded = 0

            for ((idx, _) in capturedThumbnails.entries.sortedBy { it.key }) {
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
        } catch (_: Exception) {}

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
            strokeWidth = 6f
        }

        private val fillReticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val screenCenterGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(130, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3f
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

            if (!isBoxPlaced) {
                val hitResults = frame.hitTest(screenW / 2f, screenH / 2f)
                val hit = hitResults.firstOrNull()
                if (hit != null) {
                    val p = projectPoint(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz(), screenW, screenH)
                    if (p != null) {
                        reticlePaint.color = Color.parseColor("#58A6FF")
                        canvas.drawCircle(p.x, p.y, 60f, reticlePaint)
                        canvas.drawCircle(p.x, p.y, 20f, reticlePaint)
                    }
                }
                return
            }

            val anchor = prismAnchor ?: return
            val anchorPose = anchor.pose

            canvas.drawCircle(screenW / 2f, screenH / 2f, 75f, screenCenterGuidePaint)
            canvas.drawCircle(screenW / 2f, screenH / 2f, 8f, screenCenterGuidePaint)

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

            for (i in 0 until 8) {
                val next = (i + 1) % 8
                drawEdge(canvas, botPts[i], botPts[next], wirePaint)
            }
            drawEdge(canvas, botPts[1], topPts[1], wirePaint)
            drawEdge(canvas, botPts[0], topPts[0], wirePaint)
            drawEdge(canvas, topPts[1], topPts[0], wirePaint)

            drawEdge(canvas, botPts[7], topPts[7], wirePaint)
            drawEdge(canvas, botPts[6], topPts[6], wirePaint)
            drawEdge(canvas, topPts[7], topPts[6], wirePaint)

            drawEdge(canvas, botPts[5], topPts[5], wirePaint)
            drawEdge(canvas, botPts[4], topPts[4], wirePaint)
            drawEdge(canvas, topPts[5], topPts[4], wirePaint)

            drawEdge(canvas, botPts[3], topPts[3], wirePaint)
            drawEdge(canvas, botPts[2], topPts[2], wirePaint)
            drawEdge(canvas, topPts[3], topPts[2], wirePaint)

            drawEdge(canvas, botPts[0], cornerPts[0], wirePaint)
            drawEdge(canvas, botPts[7], cornerPts[0], wirePaint)
            drawEdge(canvas, botPts[6], cornerPts[3], wirePaint)
            drawEdge(canvas, botPts[5], cornerPts[3], wirePaint)
            drawEdge(canvas, botPts[4], cornerPts[2], wirePaint)
            drawEdge(canvas, botPts[3], cornerPts[2], wirePaint)
            drawEdge(canvas, botPts[2], cornerPts[1], wirePaint)
            drawEdge(canvas, botPts[1], cornerPts[1], wirePaint)

            if (currentStepIdx < sequence.size) {
                val desc = getFacetDescriptor(currentStepIdx)
                val faceCenterWorld = localToWorld(desc.centerX, desc.centerY, desc.centerZ, anchorPose)
                val faceNormalWorld = normalToWorld(desc.normX, desc.normY, desc.normZ, anchorPose)

                val camPose = camera.pose
                val camToFaceX = camPose.tx() - faceCenterWorld[0]
                val camToFaceY = camPose.ty() - faceCenterWorld[1]
                val camToFaceZ = camPose.tz() - faceCenterWorld[2]
                val facingDot = camToFaceX * faceNormalWorld[0] + camToFaceY * faceNormalWorld[1] + camToFaceZ * faceNormalWorld[2]
                val isFacing = facingDot > 0.001f

                if (isFacing) {
                    facetPaint.color = if (isAligned) Color.parseColor("#803FB950") else Color.parseColor("#4558A6FF")

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

                    val nx = desc.normX
                    val ny = desc.normY
                    val nz = desc.normZ
                    val ux: Float
                    val uy: Float
                    val uz: Float

                    if (abs(ny) > 0.95f) {
                        ux = 1f; uy = 0f; uz = 0f
                    } else {
                        val len = sqrt(nz * nz + nx * nx)
                        ux = nz / len; uy = 0f; uz = -nx / len
                    }
                    val vx = ny * uz - nz * uy
                    val vy = nz * ux - nx * uz
                    val vz = nx * uy - ny * ux

                    val rColor = if (isAligned) Color.parseColor("#3FB950") else Color.parseColor("#58A6FF")
                    reticlePaint.color = rColor
                    fillReticlePaint.color = rColor

                    val radius = min(prismWidth, prismDepth) * 0.22f

                    val ringPts = mutableListOf<PointF?>()
                    for (i in 0 until 16) {
                        val angle = (2.0 * Math.PI * i / 16).toFloat()
                        val px = desc.centerX + radius * (cos(angle) * ux + sin(angle) * vx) + nx * 0.003f
                        val py = desc.centerY + radius * (cos(angle) * uy + sin(angle) * vy) + ny * 0.003f
                        val pz = desc.centerZ + radius * (cos(angle) * uz + sin(angle) * vz) + nz * 0.003f
                        ringPts.add(projectPoint(localToWorld(px, py, pz, anchorPose), screenW, screenH))
                    }
                    for (i in 0 until 16) {
                        val next = (i + 1) % 16
                        drawEdge(canvas, ringPts[i], ringPts[next], reticlePaint)
                    }

                    val ch = radius * 1.5f
                    val h1 = projectPoint(localToWorld(desc.centerX - ux * ch + nx * 0.003f, desc.centerY - uy * ch + ny * 0.003f, desc.centerZ - uz * ch + nz * 0.003f, anchorPose), screenW, screenH)
                    val h2 = projectPoint(localToWorld(desc.centerX + ux * ch + nx * 0.003f, desc.centerY + uy * ch + ny * 0.003f, desc.centerZ + uz * ch + nz * 0.003f, anchorPose), screenW, screenH)
                    drawEdge(canvas, h1, h2, reticlePaint)

                    val v1 = projectPoint(localToWorld(desc.centerX - vx * ch + nx * 0.003f, desc.centerY - vy * ch + ny * 0.003f, desc.centerZ - vz * ch + nz * 0.003f, anchorPose), screenW, screenH)
                    val v2 = projectPoint(localToWorld(desc.centerX + vx * ch + nx * 0.003f, desc.centerY + vy * ch + ny * 0.003f, desc.centerZ + vz * ch + nz * 0.003f, anchorPose), screenW, screenH)
                    drawEdge(canvas, v1, v2, reticlePaint)

                    val centerPt = projectPoint(localToWorld(desc.centerX + nx * 0.003f, desc.centerY + ny * 0.003f, desc.centerZ + nz * 0.003f, anchorPose), screenW, screenH)
                    if (centerPt != null) {
                        canvas.drawCircle(centerPt.x, centerPt.y, 14f, fillReticlePaint)
                    }
                }
            }
        }

        private fun drawEdge(canvas: Canvas, p1: PointF?, p2: PointF?, paint: Paint) {
            if (p1 != null && p2 != null) {
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
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
