package io.github.zhanry.hometunnel.remote

/** Fit-centred video geometry in view pixels. Panning never exposes an extra blank edge. */
internal class RemoteViewport {
    var width = 0f; private set
    var height = 0f; private set
    var contentWidth = 0f; private set
    var contentHeight = 0f; private set
    var scale = 1f; private set
    var offsetX = 0f; private set
    var offsetY = 0f; private set

    fun configure(width: Int, height: Int, videoWidth: Int, videoHeight: Int) {
        require(minOf(width, height, videoWidth, videoHeight) > 0)
        this.width = width.toFloat(); this.height = height.toFloat()
        val fit = minOf(this.width / videoWidth, this.height / videoHeight)
        contentWidth = videoWidth * fit; contentHeight = videoHeight * fit
        clamp()
    }
    fun zoom(value: Float, focusX: Float = width / 2, focusY: Float = height / 2) {
        require(value.isFinite() && focusX.isFinite() && focusY.isFinite())
        val replacement = value.coerceIn(1f, 4f)
        val ratio = replacement / scale
        offsetX = (offsetX - (focusX - width / 2)) * ratio + (focusX - width / 2)
        offsetY = (offsetY - (focusY - height / 2)) * ratio + (focusY - height / 2)
        scale = replacement; clamp()
    }
    fun pan(dx: Float, dy: Float) {
        require(dx.isFinite() && dy.isFinite())
        offsetX += dx; offsetY += dy; clamp()
    }
    fun reset() { scale = 1f; offsetX = 0f; offsetY = 0f }
    private fun clamp() {
        val horizontal = maxOf(0f, (contentWidth * scale - width) / 2)
        val vertical = maxOf(0f, (contentHeight * scale - height) / 2)
        offsetX = if (horizontal == 0f) 0f else offsetX.coerceIn(-horizontal, horizontal)
        offsetY = if (vertical == 0f) 0f else offsetY.coerceIn(-vertical, vertical)
    }
}
