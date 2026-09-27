package io.github.zhanry.hometunnel.remote

import kotlin.test.assertEquals
import kotlin.test.assertFails
import org.junit.Test

class RemoteViewportTest {
    @Test fun `zoom preserves finger focus and panning stays inside the video`() {
        val viewport = RemoteViewport()
        viewport.configure(1000, 800, 1920, 1080)
        assertEquals(1000f, viewport.contentWidth, 0.01f); assertEquals(562.5f, viewport.contentHeight, 0.01f)
        viewport.zoom(2f, 750f, 400f)
        assertEquals(-250f, viewport.offsetX); assertEquals(0f, viewport.offsetY)
        viewport.pan(10000f, -10000f)
        assertEquals(500f, viewport.offsetX, 0.01f); assertEquals(-162.5f, viewport.offsetY, 0.01f)
        viewport.zoom(1f)
        assertEquals(0f, viewport.offsetX); assertEquals(0f, viewport.offsetY)
    }
    @Test fun `rotation clamps pan and reset restores fit without invalid geometry`() {
        val viewport = RemoteViewport()
        viewport.configure(800, 1000, 1920, 1080)
        viewport.zoom(20f); assertEquals(4f, viewport.scale)
        viewport.pan(-10000f, 10000f)
        viewport.configure(1000, 800, 1920, 1080)
        assertEquals(-1200f, viewport.offsetX); assertEquals(400f, viewport.offsetY)
        viewport.reset(); assertEquals(1f, viewport.scale); assertEquals(0f, viewport.offsetX)
        assertFails { viewport.zoom(Float.NaN) }; assertFails { viewport.pan(Float.POSITIVE_INFINITY, 0f) }
        assertFails { viewport.configure(0, 800, 1920, 1080) }
    }
}
