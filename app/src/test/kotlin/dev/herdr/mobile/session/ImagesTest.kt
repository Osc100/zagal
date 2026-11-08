package dev.herdr.mobile.session

import kotlin.test.Test
import kotlin.test.assertEquals

class ImagesTest {
    @Test
    fun keepsTheNameWithWhatItIsNow() {
        assertEquals("PXL_20261108.png", named("PXL_20261108.png", "png", 0))
        // A camera's HEIC goes as a JPEG: its name says so.
        assertEquals("IMG_0042.jpg", named("IMG_0042.HEIC", "jpg", 0))
        assertEquals("archive.tar.jpg", named("archive.tar.gz", "jpg", 0))
    }

    @Test
    fun noNameIsMadeUp() {
        assertEquals("image-1700000000000.png", named(null, "png", 1_700_000_000_000))
        assertEquals("image-5.jpg", named(".jpg", "jpg", 5))
    }

    @Test
    fun aNameIsOneFile() {
        assertEquals("a_b.png", named("a/b.png", "png", 0))
    }
}
