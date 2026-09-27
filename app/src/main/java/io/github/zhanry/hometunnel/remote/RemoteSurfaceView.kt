package io.github.zhanry.hometunnel.remote

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import kotlin.math.hypot

/** The native buffer is fitted to the selected display; bars never receive input. */
@SuppressLint("ViewConstructor") // Compose constructs it with its application-scoped controller; XML cannot supply one.
class RemoteSurfaceView(context: Context, private val controller: RemoteController,
    private val onZoom: (Float) -> Unit = {}) : FrameLayout(context) {
    private var display: RemoteDisplay? = null
    private val viewport = RemoteViewport()
    private var transforming = false
    private var resumeInput = false
    private var span = 0f
    private var focusX = 0f
    private var focusY = 0f
    private val video = object : SurfaceView(context) {
        private var dragging = false
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!controller.canUse("input.pointer")) return false
            return try {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragging = controller.pointer(event.x, event.y, width, height, true)
                        if (dragging) parent.requestDisallowInterceptTouchEvent(true)
                        dragging
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (dragging && !controller.pointer(event.x, event.y, width, height)) cancelDrag()
                        dragging
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            if (!controller.pointer(event.x, event.y, width, height, false)) controller.releaseControl()
                            performClick()
                        }
                        dragging = false; parent.requestDisallowInterceptTouchEvent(false); true
                    }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { cancelDrag(); true }
                    else -> dragging
                }
            } catch (_: RuntimeException) { cancelDrag(); false }
        }
        private fun cancelDrag() {
            dragging = false; parent.requestDisallowInterceptTouchEvent(false)
            runCatching { controller.releaseControl() }
        }
    }.apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { controller.setSurface(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { controller.setSurface(holder.surface) }
            override fun surfaceDestroyed(holder: SurfaceHolder) { controller.setSurface(null) }
        })
    }
    init { setBackgroundColor(Color.BLACK); addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)) }
    fun display(value: RemoteDisplay?) {
        if (display != value) {
            if (display?.id != value?.id) { viewport.reset(); onZoom(1f) }
            display = value; fit()
        }
    }
    fun zoom(value: Float) { if (value != viewport.scale) { viewport.zoom(value); transform() } }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { transforming = false; span = 0f }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) beginTransform()
        return transforming
    }
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Block outer scrolling while retaining our ability to cancel a one-finger drag for a pinch.
        parent?.requestDisallowInterceptTouchEvent(disallowIntercept)
    }
    private fun beginTransform() {
        if (transforming) return
        resumeInput = controller.state.value.inputEnabled
        transforming = true
        runCatching { controller.releaseControl() }
        parent?.requestDisallowInterceptTouchEvent(true)
    }
    @SuppressLint("ClickableViewAccessibility") // The slider and Fit action expose the same zoom operation to accessibility services.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) beginTransform()
        if (!transforming) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) {
                val x = (event.getX(0) + event.getX(1)) / 2
                val y = (event.getY(0) + event.getY(1)) / 2
                val distance = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                if (span > 0f && distance > 0f) {
                    viewport.pan(x - focusX, y - focusY)
                    viewport.zoom(viewport.scale * distance / span, x, y)
                    transform(); onZoom(viewport.scale)
                }
                focusX = x; focusY = y; span = distance
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> span = 0f
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                transforming = false; span = 0f; parent?.requestDisallowInterceptTouchEvent(false)
                if (resumeInput && event.actionMasked == MotionEvent.ACTION_UP) runCatching { controller.requestControl() }
                resumeInput = false
            }
        }
        return true
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); fit() }
    private fun fit() {
        val target = display ?: return
        if (width <= 0 || height <= 0) return
        viewport.configure(width, height, target.width, target.height)
        video.layoutParams = LayoutParams(maxOf(1, viewport.contentWidth.toInt()), maxOf(1, viewport.contentHeight.toInt()), Gravity.CENTER)
        transform()
    }
    private fun transform() {
        // View transforms also map touch coordinates back to the fitted surface before RemoteWire sees them.
        video.scaleX = viewport.scale; video.scaleY = viewport.scale
        video.translationX = viewport.offsetX; video.translationY = viewport.offsetY
    }
}
