package io.github.zhanry.hometunnel.remote

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout

/** The native buffer is fitted to the selected display; bars never receive input. */
@SuppressLint("ViewConstructor") // Compose constructs it with its application-scoped controller; XML cannot supply one.
class RemoteSurfaceView(context: Context, private val controller: RemoteController) : FrameLayout(context) {
    private var display: RemoteDisplay? = null
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
    fun display(value: RemoteDisplay?) { if (display != value) { display = value; fit() } }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); fit() }
    private fun fit() {
        val target = display ?: return
        if (width <= 0 || height <= 0) return
        val scale = minOf(width.toDouble() / target.width, height.toDouble() / target.height)
        video.layoutParams = LayoutParams(maxOf(1, (target.width * scale).toInt()), maxOf(1, (target.height * scale).toInt()), Gravity.CENTER)
    }
}
