package live.citymall.opsscanner

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap

/**
 * JS entry point for the native QR / barcode scanner.
 *
 * Old-bridge module (no codegen); the New Architecture interop layer loads it. Resolves
 * `{ didCancel, value, format }`; rejects only when the screen could not run,
 * which JS treats as "fall back to the in-sheet scanner / manual entry".
 */
class CodeScannerModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    private var pendingPromise: Promise? = null

    private val activityEventListener: ActivityEventListener =
        object : BaseActivityEventListener() {
            override fun onActivityResult(
                activity: Activity,
                requestCode: Int,
                resultCode: Int,
                data: Intent?,
            ) {
                if (requestCode != REQUEST_CODE) return
                val promise = pendingPromise ?: return
                pendingPromise = null

                when (resultCode) {
                    Activity.RESULT_OK -> promise.resolve(
                        Arguments.createMap().apply {
                            putBoolean("didCancel", false)
                            putString("value", data?.getStringExtra(CodeScannerActivity.EXTRA_RESULT_VALUE))
                            putString("format", data?.getStringExtra(CodeScannerActivity.EXTRA_RESULT_FORMAT))
                        },
                    )

                    Activity.RESULT_CANCELED -> promise.resolve(
                        Arguments.createMap().apply { putBoolean("didCancel", true) },
                    )

                    CodeScannerActivity.RESULT_SCAN_ERROR -> promise.reject(
                        E_SCAN_FAILED,
                        data?.getStringExtra(CodeScannerActivity.EXTRA_RESULT_ERROR) ?: "scan_failed",
                    )

                    else -> promise.reject(E_SCAN_FAILED, "unexpected_result_$resultCode")
                }
            }
        }

    init {
        reactContext.addActivityEventListener(activityEventListener)
    }

    override fun getName(): String = NAME

    override fun invalidate() {
        reactContext.removeActivityEventListener(activityEventListener)
        // A pending promise outliving the module would hang the caller's await.
        pendingPromise?.reject(E_SCAN_FAILED, "module_invalidated")
        pendingPromise = null
        super.invalidate()
    }

    /**
     * @param config `{ mode?: "QR" | "BARCODE" | "ANY", frame_shape?: "SQUARE" |
     *        "RECTANGLE", title?: string, helper_text?: string }` — see
     *        src/index.ts.
     */
    @ReactMethod
    fun scan(config: ReadableMap?, promise: Promise) {
        if (pendingPromise != null) {
            promise.reject(E_ALREADY_RUNNING, "a scan is already in progress")
            return
        }

        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject(E_NO_ACTIVITY, "no foreground activity to launch the scanner from")
            return
        }

        pendingPromise = promise
        try {
            activity.startActivityForResult(
                CodeScannerActivity.intent(activity, ScannerConfig.from(config)),
                REQUEST_CODE,
            )
            // No transition: ops tap scan dozens of times a shift.
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        } catch (e: Exception) {
            pendingPromise = null
            promise.reject(E_SCAN_FAILED, e)
        }
    }

    companion object {
        const val NAME = "CodeScannerModule"

        /**
         * Must stay unique across every module that calls startActivityForResult
         * from the host activity: 0x9A01 partner-db proof capture, 0x9A02 / 0x9A03
         * react-native-face-capture.
         */
        private const val REQUEST_CODE = 0x9A04

        private const val E_NO_ACTIVITY = "E_NO_ACTIVITY"
        private const val E_ALREADY_RUNNING = "E_ALREADY_RUNNING"
        private const val E_SCAN_FAILED = "E_SCAN_FAILED"
    }
}
