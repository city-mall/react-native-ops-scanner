package live.citymall.opsscanner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * Scrim with a cut-out scan window, corner brackets and a sweeping laser line.
 *
 * SQUARE suits QR codes; RECTANGLE is wide and short for 1D barcodes, which also
 * nudges the DB to hold the label level. [frameRect] is in view coordinates —
 * the same space the activity's view-referenced ML Kit boxes arrive in, so the
 * activity can accept only codes that sit inside the window.
 */
class ScanFrameOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Shape { SQUARE, RECTANGLE }

    var shape: Shape = Shape.SQUARE
        set(value) {
            if (field == value) return
            field = value
            if (width > 0 && height > 0) {
                layoutFrame(width, height)
                onFrameChanged?.invoke(RectF(frame))
            }
            invalidate()
        }

    /** Fires whenever the frame moves — the activity pins the helper line under it. */
    var onFrameChanged: ((RectF) -> Unit)? = null

    /** Turns the brackets green the instant a code is accepted. */
    var locked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            bracketPaint.color = if (value) colorLocked else colorFrame
            if (value) laserAnimator.cancel()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val cornerRadius = dp(14f)
    private val armLength = dp(28f)

    private val colorFrame = ContextCompat.getColor(context, R.color.ops_scanner_frame)
    private val colorLocked = ContextCompat.getColor(context, R.color.ops_scanner_frame_locked)
    private val colorLaser = ContextCompat.getColor(context, R.color.ops_scanner_laser)

    private val scrimPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.ops_scanner_scrim)
    }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(4f)
        color = colorFrame
    }
    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val frame = RectF()
    private val scrimPath = Path()
    private val bracketPath = Path()
    private var laserPosition = 0f

    private val laserAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600L
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            laserPosition = it.animatedValue as Float
            invalidate()
        }
    }

    fun frameRect(): RectF = RectF(frame)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!locked) laserAnimator.start()
    }

    override fun onDetachedFromWindow() {
        laserAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            layoutFrame(w, h)
            onFrameChanged?.invoke(RectF(frame))
        }
    }

    private fun layoutFrame(w: Int, h: Int) {
        val (frameWidth, frameHeight) = when (shape) {
            Shape.SQUARE -> {
                val side = min(w * SQUARE_WIDTH_FRACTION, h * MAX_HEIGHT_FRACTION)
                side to side
            }
            Shape.RECTANGLE -> {
                val width = w * RECT_WIDTH_FRACTION
                width to width * RECT_ASPECT
            }
        }
        val left = (w - frameWidth) / 2f
        // Slightly above centre leaves room for the helper line underneath.
        val top = (h - frameHeight) / 2f - h * CENTRE_LIFT_FRACTION
        frame.set(left, top, left + frameWidth, top + frameHeight)

        scrimPath.reset()
        scrimPath.fillType = Path.FillType.EVEN_ODD
        scrimPath.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        scrimPath.addRoundRect(frame, cornerRadius, cornerRadius, Path.Direction.CW)

        buildBrackets()

        laserPaint.shader = LinearGradient(
            frame.left, 0f, frame.right, 0f,
            intArrayOf(Color.TRANSPARENT, colorLaser, Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
    }

    private fun buildBrackets() {
        val r = cornerRadius
        val a = min(armLength, min(frame.width(), frame.height()) / 3f)
        bracketPath.reset()
        // top-left
        bracketPath.moveTo(frame.left, frame.top + a)
        bracketPath.lineTo(frame.left, frame.top + r)
        bracketPath.quadTo(frame.left, frame.top, frame.left + r, frame.top)
        bracketPath.lineTo(frame.left + a, frame.top)
        // top-right
        bracketPath.moveTo(frame.right - a, frame.top)
        bracketPath.lineTo(frame.right - r, frame.top)
        bracketPath.quadTo(frame.right, frame.top, frame.right, frame.top + r)
        bracketPath.lineTo(frame.right, frame.top + a)
        // bottom-right
        bracketPath.moveTo(frame.right, frame.bottom - a)
        bracketPath.lineTo(frame.right, frame.bottom - r)
        bracketPath.quadTo(frame.right, frame.bottom, frame.right - r, frame.bottom)
        bracketPath.lineTo(frame.right - a, frame.bottom)
        // bottom-left
        bracketPath.moveTo(frame.left + a, frame.bottom)
        bracketPath.lineTo(frame.left + r, frame.bottom)
        bracketPath.quadTo(frame.left, frame.bottom, frame.left, frame.bottom - r)
        bracketPath.lineTo(frame.left, frame.bottom - a)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frame.isEmpty) return

        canvas.drawPath(scrimPath, scrimPaint)

        if (!locked) {
            val inset = dp(10f)
            val y = frame.top + inset + (frame.height() - 2 * inset) * laserPosition
            canvas.drawRect(frame.left + inset, y - dp(1f), frame.right - inset, y + dp(1f), laserPaint)
        }

        canvas.drawPath(bracketPath, bracketPaint)
    }

    private companion object {
        const val SQUARE_WIDTH_FRACTION = 0.68f
        const val MAX_HEIGHT_FRACTION = 0.5f
        const val RECT_WIDTH_FRACTION = 0.86f

        /** Height / width of the barcode window — roughly a shipping label's barcode strip. */
        const val RECT_ASPECT = 0.42f
        const val CENTRE_LIFT_FRACTION = 0.06f
    }
}
