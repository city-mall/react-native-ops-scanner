package live.citymall.opsscanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import live.citymall.opsscanner.databinding.ActivityOpsScannerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Native QR / barcode scanner for ops flows (dark-store rack + shipment picking).
 *
 * One launch = one code. The caller either pins a mode through
 * [ScannerConfig] (QR or 1D barcode — the switcher is hidden) or leaves it unset,
 * in which case a QR | Barcode switcher is shown because on the ground some
 * labels carry a QR and others a barcode. The active mode both narrows ML Kit's
 * decoders (fewer formats, faster frames) and picks the frame shape: square for
 * QR, a wide rectangle for barcodes.
 *
 * Built for speed on a budget fleet:
 *  - the first decode inside the frame wins. No multi-frame confirmation: every
 *    caller matches the value against ids it already holds, so a misread can't
 *    be recorded as a real box, only bounce off as "not recognised";
 *  - analysis runs at ~720p with keep-only-latest backpressure, which is ML
 *    Kit's recommended size for small 1D labels without stalling low-end CPUs;
 *  - LifecycleCameraController keeps continuous autofocus, tap-to-focus and
 *    pinch-to-zoom for free.
 *
 * Only codes whose centre is inside the drawn frame count, so the label on the
 * neighbouring rack can't be picked up by accident.
 *
 * Contract with CodeScannerModule:
 *   in  — ScannerConfig extras
 *   out — RESULT_OK with value + format, RESULT_CANCELED on close/back, or
 *         RESULT_SCAN_ERROR when the camera never came up.
 */
class CodeScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOpsScannerBinding
    private lateinit var config: ScannerConfig

    private var controller: LifecycleCameraController? = null
    private var scanner: BarcodeScanner? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Main-thread only. Guards against a second frame finishing the activity twice. */
    private var delivered = false
    private var lastOutsideHintAt = 0L
    private var lastRejectAt = 0L
    private var lastRejectValue: String? = null
    private lateinit var activeMode: ScannerConfig.Mode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOpsScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = ScannerConfig.from(intent)
        activeMode = config.mode ?: lastUsedMode()
        bindChrome()
        applyWindowInsets()

        binding.closeButton.setOnClickListener { cancel() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancel()
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // JS asks for CAMERA before launching; landing here without it is a
            // revoke race. Hand back so the caller can fall back to manual entry.
            finishWithError("camera_permission_denied")
            return
        }

        startCamera()
    }

    private fun bindChrome() {
        // The frame's size depends on the shape and the screen, so the helper
        // line is pinned under it whenever the overlay lays the frame out.
        binding.frameOverlay.onFrameChanged = { frame ->
            val offset = frame.bottom + HELPER_GAP_DP.dp()
            binding.helperText.translationY = offset
            binding.hintChip.translationY = offset
        }

        if (config.showsSwitcher) {
            binding.modeSwitcher.visibility = View.VISIBLE
            binding.modeQr.setOnClickListener { switchMode(ScannerConfig.Mode.QR) }
            binding.modeBarcode.setOnClickListener { switchMode(ScannerConfig.Mode.BARCODE) }
        }
        renderMode()
    }

    /** Title, helper line, frame shape and switcher highlight for [activeMode]. */
    private fun renderMode() {
        val mode = activeMode
        binding.titleText.text = config.title ?: getString(
            if (mode == ScannerConfig.Mode.QR) R.string.ops_scanner_title_qr else R.string.ops_scanner_title_barcode,
        )
        binding.helperText.text = config.helperText ?: getString(
            if (mode == ScannerConfig.Mode.QR) R.string.ops_scanner_helper_qr else R.string.ops_scanner_helper_barcode,
        )
        binding.frameOverlay.shape = mode.frameShape

        if (config.showsSwitcher) {
            styleSegment(binding.modeQr, selected = mode == ScannerConfig.Mode.QR)
            styleSegment(binding.modeBarcode, selected = mode == ScannerConfig.Mode.BARCODE)
        }
    }

    private fun styleSegment(segment: TextView, selected: Boolean) {
        segment.isSelected = selected
        segment.setBackgroundResource(if (selected) R.drawable.bg_ops_scanner_segment_selected else 0)
        segment.setTextColor(if (selected) Color.BLACK else Color.WHITE)
    }

    /**
     * Only the frame, copy and result filter change; the analyzer stays. A new
     * MlKitAnalyzer set on a running controller never receives the PreviewView
     * transform (CameraX pushes it only on preview layout changes), so it would
     * drop every frame as "Sensor-to-target transformation is null".
     */
    private fun switchMode(mode: ScannerConfig.Mode) {
        if (mode == activeMode || delivered) return
        activeMode = mode
        rememberMode(mode)
        renderMode()
        binding.hintChip.visibility = View.INVISIBLE
    }

    /** Edge-to-edge: keep the close button off the status bar and the bottom bar off the nav bar. */
    private fun applyWindowInsets() {
        val closeTop = (binding.closeButton.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        val bottomPadding = binding.bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            binding.closeButton.updateMargins(top = closeTop + bars.top)
            binding.bottomBar.setPadding(0, 0, 0, bottomPadding + bars.bottom)
            insets
        }
    }

    private fun startCamera() {
        try {
            val cameraController = LifecycleCameraController(this).apply {
                cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
                imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            ANALYSIS_SIZE,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build()
            }
            attachAnalyzer(cameraController)
            controller = cameraController
            binding.previewView.controller = cameraController
            cameraController.bindToLifecycle(this)

            cameraController.initializationFuture.addListener({
                try {
                    cameraController.initializationFuture.get()
                    setUpTorch(cameraController)
                } catch (e: Exception) {
                    Log.e(TAG, "camera init failed", e)
                    finishWithError("camera_unavailable")
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            // No back camera, another app holding it, an OEM HAL throwing, ML Kit
            // refusing to initialise. The caller falls back; never strand the DB.
            Log.e(TAG, "scanner start failed", e)
            finishWithError("camera_unavailable")
        }
    }

    /**
     * Attached once, before the PreviewView, so CameraX hands it the view
     * transform. A pinned mode decodes only its own formats; with the switcher
     * the client decodes both and [onBarcodes] keeps the active mode's codes.
     */
    private fun attachAnalyzer(cameraController: LifecycleCameraController) {
        val formats = config.mode?.formats
            ?: ScannerConfig.Mode.values().flatMap { it.formats.asList() }.toIntArray()
        val barcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(formats.first(), *formats.drop(1).toIntArray())
                .build(),
        )
        scanner = barcodeScanner
        cameraController.setImageAnalysisAnalyzer(
            analysisExecutor,
            MlKitAnalyzer(
                listOf(barcodeScanner),
                ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                ContextCompat.getMainExecutor(this),
            ) { result -> onBarcodes(result.getValue(barcodeScanner)) },
        )
    }

    private fun lastUsedMode(): ScannerConfig.Mode =
        ScannerConfig.parseMode(prefs().getString(PREF_LAST_MODE, null)) ?: ScannerConfig.Mode.QR

    /** Remembered only for switcher launches — labels tend to come in runs of one type. */
    private fun rememberMode(mode: ScannerConfig.Mode) {
        prefs().edit().putString(PREF_LAST_MODE, mode.name).apply()
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    /** Main thread (MlKitAnalyzer's consumer executor). */
    private fun onBarcodes(barcodes: List<Barcode>?) {
        if (delivered || isFinishing || barcodes.isNullOrEmpty()) return
        // isShutdown: onDestroy already ran; a late frame must not touch views.
        if (analysisExecutor.isShutdown) return

        val window = binding.frameOverlay.frameRect()
        if (window.isEmpty) return
        val area = RectF(window).apply {
            inset(-window.width() * FRAME_TOLERANCE, -window.height() * FRAME_TOLERANCE)
        }

        // Switcher launches decode both kinds; only the selected one counts.
        val allowed = activeMode.formats
        val readable = barcodes.filter { barcode ->
            barcode.format in allowed && !barcode.rawValue.isNullOrBlank() && barcode.boundingBox != null
        }
        if (readable.isEmpty()) return
        val inFrame = readable.filter { barcode ->
            val box = barcode.boundingBox!!
            area.contains(box.exactCenterX(), box.exactCenterY())
        }
        if (inFrame.isEmpty()) {
            showOutsideHint()
            return
        }

        val acceptValues = config.acceptValues
        val hit = if (acceptValues == null) {
            inFrame.first()
        } else {
            inFrame.firstOrNull { ScannerConfig.normalize(it.rawValue!!) in acceptValues }
        }
        if (hit == null) {
            showRejectHint(ScannerConfig.normalize(inFrame.first().rawValue!!))
            return
        }

        delivered = true
        binding.frameOverlay.locked = true
        confirmFeedback()
        finishOk(hit.rawValue!!.trim(), formatName(hit.format))
    }

    private fun showOutsideHint() {
        val now = System.currentTimeMillis()
        // A reject chip outranks "move it inside" while it is up.
        if (now - lastOutsideHintAt < OUTSIDE_HINT_MS || now - lastRejectAt < REJECT_HINT_MS) return
        lastOutsideHintAt = now
        showHint(getString(R.string.ops_scanner_hint_move_inside), OUTSIDE_HINT_MS)
    }

    /**
     * A wrong label inside the frame: say why and keep scanning. The label is
     * usually still in view, so the buzz fires once per value, not every frame.
     */
    private fun showRejectHint(value: String) {
        val now = System.currentTimeMillis()
        if (value == lastRejectValue && now - lastRejectAt < REJECT_HINT_MS) return
        lastRejectValue = value
        lastRejectAt = now
        val text = config.rejectHints[value]
            ?: config.invalidText
            ?: getString(R.string.ops_scanner_hint_not_valid)
        showHint("$text · $value", REJECT_HINT_MS)
        binding.root.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            },
        )
    }

    private fun showHint(text: String, durationMs: Long) {
        binding.hintChip.text = text
        binding.hintChip.visibility = View.VISIBLE
        main.removeCallbacks(hideHint)
        main.postDelayed(hideHint, durationMs)
    }

    private val hideHint = Runnable { binding.hintChip.visibility = View.INVISIBLE }

    private fun setUpTorch(cameraController: LifecycleCameraController) {
        if (cameraController.cameraInfo?.hasFlashUnit() != true) return
        binding.torchButton.visibility = View.VISIBLE
        binding.torchButton.setOnClickListener {
            val on = cameraController.torchState.value == TorchState.ON
            cameraController.enableTorch(!on)
        }
        cameraController.torchState.observe(this) { state ->
            binding.torchButton.setText(
                if (state == TorchState.ON) R.string.ops_scanner_torch_off else R.string.ops_scanner_torch_on,
            )
        }
    }

    /** Ops scan dozens of labels in a row; a beep + buzz confirms without looking. */
    private fun confirmFeedback() {
        binding.root.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            },
        )
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, BEEP_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
            // The handler outlives the activity, so the beep isn't cut by finish().
            main.postDelayed({ runCatching { tone.release() } }, BEEP_MS + 100L)
        }
    }

    private fun finishOk(value: String, format: String) {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_RESULT_VALUE, value)
                .putExtra(EXTRA_RESULT_FORMAT, format),
        )
        finish()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun finishWithError(reason: String) {
        if (isFinishing) return
        setResult(RESULT_SCAN_ERROR, Intent().putExtra(EXTRA_RESULT_ERROR, reason))
        finish()
    }

    /** No exit animation either — back to the sheet instantly. */
    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        main.removeCallbacks(hideHint)
        runCatching { controller?.unbind() }
        runCatching { scanner?.close() }
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun View.updateMargins(top: Int) {
        val params = layoutParams as ViewGroup.MarginLayoutParams
        params.topMargin = top
        layoutParams = params
    }

    private fun Int.dp(): Float = this * resources.displayMetrics.density

    companion object {
        private const val TAG = "CodeScanner"

        /** ML Kit's recommended input for small 1D labels; cheap enough for 2 GB devices. */
        private val ANALYSIS_SIZE = Size(1280, 720)

        /** A code whose centre is within this fraction outside the frame still counts. */
        private const val FRAME_TOLERANCE = 0.08f

        private const val OUTSIDE_HINT_MS = 1500L
        private const val REJECT_HINT_MS = 2000L
        private const val HELPER_GAP_DP = 20
        private const val BEEP_VOLUME = 80
        private const val BEEP_MS = 120

        private const val PREFS_NAME = "code_scanner"
        private const val PREF_LAST_MODE = "last_mode"

        /** Distinct from RESULT_CANCELED: the DB did not decline, the camera failed. */
        const val RESULT_SCAN_ERROR = 2

        const val EXTRA_RESULT_VALUE = "scanner_value"
        const val EXTRA_RESULT_FORMAT = "scanner_format"
        const val EXTRA_RESULT_ERROR = "scanner_error"

        fun intent(context: Context, config: ScannerConfig): Intent =
            config.writeTo(Intent(context, CodeScannerActivity::class.java))

        fun formatName(format: Int): String = when (format) {
            Barcode.FORMAT_QR_CODE -> "QR_CODE"
            Barcode.FORMAT_CODE_128 -> "CODE_128"
            Barcode.FORMAT_CODE_39 -> "CODE_39"
            Barcode.FORMAT_CODE_93 -> "CODE_93"
            Barcode.FORMAT_CODABAR -> "CODABAR"
            Barcode.FORMAT_EAN_13 -> "EAN_13"
            Barcode.FORMAT_EAN_8 -> "EAN_8"
            Barcode.FORMAT_ITF -> "ITF"
            Barcode.FORMAT_UPC_A -> "UPC_A"
            Barcode.FORMAT_UPC_E -> "UPC_E"
            Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
            Barcode.FORMAT_PDF417 -> "PDF417"
            Barcode.FORMAT_AZTEC -> "AZTEC"
            else -> "UNKNOWN"
        }
    }
}
