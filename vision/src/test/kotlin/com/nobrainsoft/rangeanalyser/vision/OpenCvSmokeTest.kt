package com.nobrainsoft.rangeanalyser.vision

import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the toolchain works end to end before any real algorithm depends on it: natives load,
 * `Mat` allocation works, and the imgproc module (which every detector here uses) is present.
 */
class OpenCvSmokeTest {
    @Test
    fun `natives load and report a version`() {
        TestOpenCv.ensureLoaded()
        assertTrue(Core.VERSION.startsWith("4."), "unexpected OpenCV version: ${Core.VERSION}")
    }

    @Test
    fun `imgproc is available and operates on a Mat`() {
        TestOpenCv.ensureLoaded()

        val image = Mat(20, 30, CvType.CV_8UC1, Scalar(0.0))
        Imgproc.circle(image, org.opencv.core.Point(15.0, 10.0), 4, Scalar(255.0), -1)

        val binary = Mat()
        Imgproc.threshold(image, binary, 127.0, 255.0, Imgproc.THRESH_BINARY)

        val contours = mutableListOf<org.opencv.core.MatOfPoint>()
        Imgproc.findContours(
            binary,
            contours,
            Mat(),
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )

        assertEquals(1, contours.size, "expected exactly one blob")
        assertTrue(Imgproc.contourArea(contours[0]) > 30.0)
    }
}
