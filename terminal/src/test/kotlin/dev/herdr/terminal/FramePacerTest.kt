package dev.herdr.terminal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FramePacerTest {
  private var posts = 0
  private val pacer = FramePacer { posts++ }

  @Test
  fun onScreenAFramePerVsyncHoweverManyAsk() {
    pacer.attach()
    repeat(5) { pacer.request() }
    assertEquals(1, posts)
    assertTrue(pacer.fired())
    pacer.request()
    assertEquals(2, posts)
  }

  @Test
  fun offScreenNoFramesUntilShownAgain() {
    pacer.attach()
    pacer.detach()
    // Output keeps coming to a kept terminal: no frames for it.
    repeat(100) { pacer.request() }
    assertEquals(0, posts)
    // Shown again: it missed some, so one now, of every row.
    assertTrue(pacer.attach())
    assertFalse(pacer.attach(), "only once")
  }

  @Test
  fun aFramePostedBeforeGoingOffScreenIsSkipped() {
    pacer.attach()
    pacer.request()
    pacer.detach()
    assertFalse(pacer.fired())
  }
}
