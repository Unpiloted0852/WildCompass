package io.github.unpiloted0852.wildcompass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * An image that fits the view and can be pinched, double-tapped and dragged to look closer.
 *
 * While zoomed in it keeps touches to itself, so dragging pans the picture; at normal size
 * it lets them through, so a pager around it can swipe to the next picture.
 */
class ZoomImageView(context: Context) : AppCompatImageView(context) {

    var onTap: (() -> Unit)? = null

    private val drawMatrix = Matrix()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (zoom <= 1f) return false
                panX -= dx
                panY -= dy
                update()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomTo(if (zoom > 1f) 1f else DOUBLE_TAP_ZOOM, e.x, e.y)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.invoke()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(zoom > 1f || event.pointerCount > 1)
        return true
    }

    private fun reset() {
        zoom = 1f
        panX = 0f
        panY = 0f
        update()
    }

    /** Changes the zoom while keeping the picture under ([focusX], [focusY]) in place. */
    private fun zoomTo(target: Float, focusX: Float, focusY: Float) {
        val newZoom = target.coerceIn(1f, MAX_ZOOM)
        val fromCenterX = focusX - width / 2f
        val fromCenterY = focusY - height / 2f
        panX = (panX - fromCenterX) * (newZoom / zoom) + fromCenterX
        panY = (panY - fromCenterY) * (newZoom / zoom) + fromCenterY
        zoom = newZoom
        update()
    }

    private fun update() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0 || width == 0 || height == 0) return

        val fit = minOf(width / dw, height / dh)
        val shownW = dw * fit * zoom
        val shownH = dh * fit * zoom
        // The picture may not be dragged further than its own edge.
        val maxPanX = maxOf(0f, (shownW - width) / 2f)
        val maxPanY = maxOf(0f, (shownH - height) / 2f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)

        drawMatrix.reset()
        drawMatrix.postScale(fit * zoom, fit * zoom)
        drawMatrix.postTranslate((width - shownW) / 2f + panX, (height - shownH) / 2f + panY)
        imageMatrix = drawMatrix
    }

    private companion object {
        const val MAX_ZOOM = 6f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
