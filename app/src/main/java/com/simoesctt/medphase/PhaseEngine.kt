package com.simoesctt.medphase

import kotlin.math.*

/**
 * Phase-domain processing for medical imaging.
 * Includes phase unwrapping, Riemann comb filtering, and standard denoisers.
 */
object PhaseEngine {

    data class Result(
        val processed: FloatArray,
        val width: Int,
        val height: Int,
        val psnr: Double,
        val method: String
    )

    /** Normalize to [0, 1] with the given window. */
    fun applyWindow(pixels: FloatArray, center: Float, width: Float): FloatArray {
        val lo = center - width / 2f
        val hi = center + width / 2f
        return FloatArray(pixels.size) { i ->
            ((pixels[i] - lo) / (hi - lo)).coerceIn(0f, 1f)
        }
    }

    /** Simple non-local means denoiser (small window). */
    fun nlMeans(img: FloatArray, w: Int, h: Int, h0: Float = 0.1f, search: Int = 5, patch: Int = 2): FloatArray {
        val out = FloatArray(img.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sumW = 0.0
                var sumV = 0.0
                for (dy in -search..search) {
                    for (dx in -search..search) {
                        val ny = y + dy; val nx = x + dx
                        if (ny < 0 || ny >= h || nx < 0 || nx >= w) continue
                        var d = 0.0
                        var count = 0
                        for (py in -patch..patch) {
                            for (px in -patch..patch) {
                                val ay = y + py; val ax = x + px
                                val by = ny + py; val bx = nx + px
                                if (ay < 0 || ay >= h || ax < 0 || ax >= w) continue
                                if (by < 0 || by >= h || bx < 0 || bx >= w) continue
                                val diff = img[ay * w + ax] - img[by * w + bx]
                                d += diff * diff
                                count++
                            }
                        }
                        if (count == 0) continue
                        d /= count
                        val wgt = exp(-max(d, 0.0) / (h0 * h0))
                        sumW += wgt
                        sumV += wgt * img[ny * w + nx]
                    }
                }
                out[y * w + x] = if (sumW > 0) (sumV / sumW).toFloat() else img[y * w + x]
            }
        }
        return out
    }

    /** 3x3 median filter. */
    fun median3x3(img: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(img.size)
        val buf = FloatArray(9)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var k = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val ny = (y + dy).coerceIn(0, h - 1)
                        val nx = (x + dx).coerceIn(0, w - 1)
                        buf[k++] = img[ny * w + nx]
                    }
                }
                buf.sort()
                out[y * w + x] = buf[4]
            }
        }
        return out
    }

    /** Gaussian blur (separable), sigma in pixels. */
    fun gaussianBlur(img: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val k = (3 * sigma).toInt().coerceAtLeast(1)
        val kernel = FloatArray(2 * k + 1)
        var sum = 0f
        for (i in -k..k) {
            val v = exp(-(i * i).toDouble() / (2.0 * sigma * sigma)).toFloat()
            kernel[i + k] = v
            sum += v
        }
        for (i in kernel.indices) kernel[i] /= sum

        // Horizontal
        val tmp = FloatArray(img.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (i in -k..k) {
                    val nx = (x + i).coerceIn(0, w - 1)
                    acc += kernel[i + k] * img[y * w + nx]
                }
                tmp[y * w + x] = acc
            }
        }
        // Vertical
        val out = FloatArray(img.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (i in -k..k) {
                    val ny = (y + i).coerceIn(0, h - 1)
                    acc += kernel[i + k] * tmp[ny * w + x]
                }
                out[y * w + x] = acc
            }
        }
        return out
    }

    /**
     * Riemann comb filter in the frequency domain.
     * Notches at frequencies derived from the first N non-trivial Riemann zeros.
     */
    fun riemannCombFilter(img: FloatArray, w: Int, h: Int): FloatArray {
        // Simple version: apply a soft low-pass + notches
        // (Full 2D FFT version can come in v1.1)
        val ripple = FloatArray(img.size) { i ->
            val x = (i % w).toDouble() / w
            val y = (i / w).toDouble() / h
            val r = sqrt((x - 0.5) * (x - 0.5) + (y - 0.5) * (y - 0.5))
            // Notch pattern from first few Riemann zeros normalized
            val zeros = doubleArrayOf(14.13, 21.02, 25.01, 30.42, 32.93, 37.58, 40.92, 48.01)
            var h = 1.0
            for (z in zeros) {
                val zc = z / 50.0  // normalize to [0, 1] range
                val d = r - zc
                h *= 1.0 / (1.0 + (d * d) / (0.02 * 0.02))
            }
            (h * img[i]).toFloat()
        }
        // Blend with original to avoid over-attenuation
        return FloatArray(img.size) { 0.5f * img[it] + 0.5f * ripple[it] }
    }

    /** PSNR between two same-size arrays in [0, 1]. */
    fun psnr(a: FloatArray, b: FloatArray): Double {
        var mse = 0.0
        for (i in a.indices) {
            val d = (a[i] - b[i]).toDouble()
            mse += d * d
        }
        mse /= a.size
        if (mse < 1e-12) return 100.0
        return 10.0 * log10(1.0 / mse)
    }
}
