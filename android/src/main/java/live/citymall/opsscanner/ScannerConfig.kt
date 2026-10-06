package live.citymall.opsscanner

import android.content.Intent
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * What one scanner launch is for. Arrives from JS as a plain map (see
 * src/index.ts) and travels to the activity as intent extras.
 *
 * [mode] set → the screen is locked to it and the QR / barcode switcher is
 * hidden. [mode] null → the switcher is shown and the DB picks, because on the
 * ground some labels carry a QR and others a barcode.
 *
 * Every field is optional and an unknown mode reads as "not set": the config
 * can originate from the server, and a typo there must not stop a DB scanning.
 *
 * [acceptValues] set → the screen stays open until it reads one of them; any
 * other code shows [rejectHints] / [invalidText] in a chip and scanning goes on.
 * Null → the first code inside the frame closes it. Values are trimmed and
 * upper-cased on both sides ([normalize]).
 */
data class ScannerConfig(
    val mode: Mode?,
    val title: String?,
    val helperText: String?,
    val acceptValues: Set<String>? = null,
    val rejectHints: Map<String, String> = emptyMap(),
    val invalidText: String? = null,
) {

    enum class Mode {
        /** QR codes only. Square frame. */
        QR,

        /** 1D symbologies only — printed shipment / box labels. Wide rectangle frame. */
        BARCODE;

        /**
         * ML Kit formats for this mode. Narrow on purpose: fewer decoders per
         * frame is the cheapest speed win there is.
         */
        val formats: IntArray
            get() = when (this) {
                QR -> intArrayOf(Barcode.FORMAT_QR_CODE)
                BARCODE -> intArrayOf(
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_CODABAR,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_ITF,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                )
            }

        val frameShape: ScanFrameOverlayView.Shape
            get() = if (this == BARCODE) ScanFrameOverlayView.Shape.RECTANGLE else ScanFrameOverlayView.Shape.SQUARE
    }

    /** The switcher is the DB's to drive only when the caller didn't pin a mode. */
    val showsSwitcher: Boolean get() = mode == null

    fun writeTo(intent: Intent): Intent = intent
        .putExtra(EXTRA_MODE, mode?.name)
        .putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_HELPER_TEXT, helperText)
        .putExtra(EXTRA_ACCEPT, acceptValues?.let { ArrayList(it) })
        .putExtra(EXTRA_REJECT_KEYS, ArrayList(rejectHints.keys))
        .putExtra(EXTRA_REJECT_TEXTS, ArrayList(rejectHints.values))
        .putExtra(EXTRA_INVALID_TEXT, invalidText)

    companion object {
        private const val EXTRA_MODE = "scanner_mode"
        private const val EXTRA_TITLE = "scanner_title"
        private const val EXTRA_HELPER_TEXT = "scanner_helper_text"
        private const val EXTRA_ACCEPT = "scanner_accept_values"
        private const val EXTRA_REJECT_KEYS = "scanner_reject_keys"
        private const val EXTRA_REJECT_TEXTS = "scanner_reject_texts"
        private const val EXTRA_INVALID_TEXT = "scanner_invalid_text"

        fun normalize(value: String): String = value.trim().uppercase()

        fun from(map: ReadableMap?): ScannerConfig {
            fun str(key: String): String? =
                if (map != null && map.hasKey(key) && !map.isNull(key)) map.getString(key) else null

            val accept = if (map != null && map.hasKey("accept_values") &&
                map.getType("accept_values") == ReadableType.Array
            ) {
                val array = map.getArray("accept_values")!!
                (0 until array.size())
                    .mapNotNull { i -> if (array.getType(i) == ReadableType.String) array.getString(i) else null }
            } else {
                null
            }
            val hints = mutableMapOf<String, String>()
            if (map != null && map.hasKey("reject_hints") && map.getType("reject_hints") == ReadableType.Map) {
                val hintMap = map.getMap("reject_hints")!!
                val keys = hintMap.keySetIterator()
                while (keys.hasNextKey()) {
                    val key = keys.nextKey()
                    if (hintMap.getType(key) == ReadableType.String) hints[key] = hintMap.getString(key)!!
                }
            }

            return build(
                mode = str("mode"),
                title = str("title"),
                helperText = str("helper_text"),
                acceptValues = accept,
                rejectHints = hints,
                invalidText = str("invalid_text"),
            )
        }

        fun from(intent: Intent?): ScannerConfig {
            val keys = intent?.getStringArrayListExtra(EXTRA_REJECT_KEYS).orEmpty()
            val texts = intent?.getStringArrayListExtra(EXTRA_REJECT_TEXTS).orEmpty()
            return build(
                mode = intent?.getStringExtra(EXTRA_MODE),
                title = intent?.getStringExtra(EXTRA_TITLE),
                helperText = intent?.getStringExtra(EXTRA_HELPER_TEXT),
                acceptValues = intent?.getStringArrayListExtra(EXTRA_ACCEPT),
                rejectHints = keys.zip(texts).toMap(),
                invalidText = intent?.getStringExtra(EXTRA_INVALID_TEXT),
            )
        }

        fun parseMode(value: String?): Mode? =
            value?.trim()?.uppercase()?.let { v -> Mode.values().firstOrNull { it.name == v } }

        private fun build(
            mode: String?,
            title: String?,
            helperText: String?,
            acceptValues: List<String>? = null,
            rejectHints: Map<String, String> = emptyMap(),
            invalidText: String? = null,
        ) = ScannerConfig(
            mode = parseMode(mode),
            title = title?.takeIf { it.isNotBlank() },
            helperText = helperText?.takeIf { it.isNotBlank() },
            acceptValues = acceptValues?.map(::normalize)?.filter { it.isNotEmpty() }?.toSet(),
            rejectHints = rejectHints.mapKeys { normalize(it.key) },
            invalidText = invalidText?.takeIf { it.isNotBlank() },
        )
    }
}
