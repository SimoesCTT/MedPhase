package com.simoesctt.medphase

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal DICOM loader.
 * Buffers the entire file into memory first, then parses.
 * Supports Explicit VR LE and Implicit VR LE.
 * Does not decode compressed pixel data.
 */
object DicomLoader {

    data class DicomImage(
        val pixels: FloatArray,
        val width: Int,
        val height: Int,
        val bitsAllocated: Int,
        val pixelRepresentation: Int,
        val photometricInterpretation: String,
        val modality: String,
        val rescaleSlope: Float,
        val rescaleIntercept: Float,
        val windowCenter: Float,
        val windowWidth: Float
    )

    private val LONG_VRS = setOf("OB", "OW", "OF", "SQ", "UT", "UN")

    fun load(ctx: Context, uri: Uri): DicomImage {
        val bytes = ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open file" }
            input.readBytes()
        }
        return parse(bytes)
    }

    private fun parse(b: ByteArray): DicomImage {
        require(b.size > 132) { "File too small (${b.size} bytes)" }

        val magic = String(b, 128, 4, Charsets.US_ASCII)
        require(magic == "DICM") { "Not a DICOM file (missing DICM magic)" }

        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(132)

        // File Meta group (always Explicit VR LE)
        var transferSyntax = "1.2.840.10008.1.2.1"
        var firstDatasetTag: Pair<Int, Int>? = null

        while (buf.remaining() >= 8) {
            val startPos = buf.position()
            val group = buf.short.toInt() and 0xFFFF
            val element = buf.short.toInt() and 0xFFFF
            if (group != 0x0002) {
                // Not File Meta — rewind and let parseDataset read this tag
                buf.position(startPos)
                firstDatasetTag = null
                break
            }
            val vr = readVR(buf)
            val len = readLength(buf, vr)
            if (len < 0 || len > buf.remaining()) {
                throw IllegalArgumentException("Bad File Meta length at ($group,$element): $len")
            }
            val valueStart = buf.position()
            if (element == 0x0010) {
                transferSyntax = String(b, valueStart, len, Charsets.US_ASCII).trim('\u0000').trim()
            }
            buf.position(valueStart + len)
        }

        return parseDataset(b, buf, transferSyntax, firstDatasetTag)
    }

    private fun parseDataset(
        b: ByteArray,
        buf: ByteBuffer,
        transferSyntax: String,
        firstTag: Pair<Int, Int>?
    ): DicomImage {
        val implicit = transferSyntax == "1.2.840.10008.1.2" ||
                transferSyntax.startsWith("1.2.840.10008.1.2\u0000")

        var width = 0
        var height = 0
        var bitsAllocated = 0
        var pixelRepresentation = 0
        var photometric = ""
        var modality = ""
        var rescaleSlope = 1.0f
        var rescaleIntercept = 0.0f
        var windowCenter = 0.0f
        var windowWidth = 0.0f
        var pixelOffset = -1
        var pixelLength = 0

        var next = firstTag

        while (true) {
            val tag: Pair<Int, Int>
            if (next != null) {
                tag = next
                next = null
            } else {
                if (buf.remaining() < 8) break
                val g = buf.short.toInt() and 0xFFFF
                val e = buf.short.toInt() and 0xFFFF
                tag = g to e
            }
            val (group, element) = tag

            val vr: String
            val len: Int
            if (implicit) {
                // Defensive: peek 2 bytes; if they look like a valid VR, this is
                // actually an Explicit VR file. Fall through to explicit parse.
                val peekPos = buf.position()
                val peek0 = if (buf.remaining() >= 2) buf.get(peekPos).toInt().toChar() else '\u0000'
                val peek1 = if (buf.remaining() >= 2) buf.get(peekPos + 1).toInt().toChar() else '\u0000'
                val peekVR = "$peek0$peek1"
                val knownVRs = setOf("CS","SH","LO","ST","LT","UT","UN","OB","OW","OF",
                                     "SQ","AE","AS","AT","DA","DS","DT","FL","FD",
                                     "IS","SL","SS","TM","UI","UL","US")
                if (peekVR in knownVRs) {
                    // Explicit VR after all — parse as explicit
                    vr = readVR(buf)
                    len = readLength(buf, vr)
                } else {
                    vr = ""
                    len = buf.int
                }
            } else {
                vr = readVR(buf)
                len = readLength(buf, vr)
            }

            if (len < 0 || len > buf.remaining()) {
                throw IllegalArgumentException(
                    "Bad length at (${"%04X".format(group)},${"%04X".format(element)}): $len, remaining ${buf.remaining()}"
                )
            }

            val valueStart = buf.position()

            if (group == 0x7FE0 && element == 0x0010) {
                // Pixel data — record position, don't copy yet
                pixelOffset = valueStart
                pixelLength = len
                buf.position(valueStart + len)
                break
            }

            val value = ByteArray(len)
            System.arraycopy(b, valueStart, value, 0, len)
            buf.position(valueStart + len)

            when (group to element) {
                // Dimension and bit-depth fields are stored as binary US values,
                // not ASCII. Read them as little-endian unsigned shorts.
                0x0028 to 0x0010 -> height = readBinaryInt(value, vr)
                0x0028 to 0x0011 -> width = readBinaryInt(value, vr)
                0x0028 to 0x0100 -> bitsAllocated = readBinaryInt(value, vr)
                0x0028 to 0x0103 -> pixelRepresentation = readBinaryInt(value, vr)
                // Text fields are stored as ASCII
                0x0028 to 0x0004 -> photometric = String(value, Charsets.US_ASCII).trim('\u0000').trim()
                0x0008 to 0x0060 -> modality = String(value, Charsets.US_ASCII).trim('\u0000').trim()
                0x0028 to 0x1053 -> rescaleSlope = parseFloatString(value, 1.0f)
                0x0028 to 0x1052 -> rescaleIntercept = parseFloatString(value, 0.0f)
                0x0028 to 0x1050 -> windowCenter = parseFloatString(value, 0.0f)
                0x0028 to 0x1051 -> windowWidth = parseFloatString(value, 0.0f)
            }
        }

        require(width > 0 && height > 0) { "Missing dimensions (w=$width, h=$height)" }
        require(pixelOffset >= 0) { "Missing pixel data" }

        val nPixels = width * height
        val expectedBytes = when (bitsAllocated) {
            8 -> nPixels
            16 -> nPixels * 2
            32 -> nPixels * 4
            else -> throw IllegalArgumentException("Unsupported bitsAllocated: $bitsAllocated")
        }
        require(pixelLength >= expectedBytes) {
            "Pixel data too short: got $pixelLength, need $expectedBytes"
        }

        val pixels = FloatArray(nPixels)
        val pdBuf = ByteBuffer.wrap(b, pixelOffset, expectedBytes).order(ByteOrder.LITTLE_ENDIAN)
        when (bitsAllocated) {
            8 -> for (i in 0 until nPixels) pixels[i] = (b[pixelOffset + i].toInt() and 0xFF).toFloat()
            16 -> for (i in 0 until nPixels) pixels[i] = pdBuf.short.toInt().toFloat()
            32 -> if (pixelRepresentation == 1) {
                for (i in 0 until nPixels) pixels[i] = pdBuf.int.toFloat()
            } else {
                for (i in 0 until nPixels) pixels[i] = (pdBuf.int.toLong() and 0xFFFFFFFFL).toFloat()
            }
        }

        return DicomImage(
            pixels, width, height, bitsAllocated, pixelRepresentation,
            photometric, modality, rescaleSlope, rescaleIntercept,
            windowCenter, windowWidth
        )
    }

    private fun readVR(buf: ByteBuffer): String {
        val c0 = buf.get().toInt().toChar()
        val c1 = buf.get().toInt().toChar()
        return "$c0$c1"
    }

    private fun readLength(buf: ByteBuffer, vr: String): Int {
        return if (vr in LONG_VRS) {
            buf.short // reserved
            buf.int
        } else {
            buf.short.toInt() and 0xFFFF
        }
    }

    private fun readBinaryInt(b: ByteArray, vr: String): Int {
        return when (vr) {
            "US" -> if (b.size >= 2) ((b[1].toInt() and 0xFF) shl 8) or (b[0].toInt() and 0xFF) else 0
            "UL" -> if (b.size >= 4) ((b[3].toInt() and 0xFF) shl 24) or ((b[2].toInt() and 0xFF) shl 16) or
                    ((b[1].toInt() and 0xFF) shl 8) or (b[0].toInt() and 0xFF) else 0
            "SS" -> if (b.size >= 2) {
                val v = ((b[1].toInt() and 0xFF) shl 8) or (b[0].toInt() and 0xFF)
                if (v > 32767) v - 65536 else v
            } else 0
            "SL" -> if (b.size >= 4) ((b[3].toInt() and 0xFF) shl 24) or ((b[2].toInt() and 0xFF) shl 16) or
                    ((b[1].toInt() and 0xFF) shl 8) or (b[0].toInt() and 0xFF) else 0
            else -> parseIntString(b)
        }
    }

    private fun parseIntString(b: ByteArray): Int =
        String(b, Charsets.US_ASCII).trim('\u0000').trim().toIntOrNull() ?: 0

    private fun parseFloatString(b: ByteArray, default: Float): Float =
        String(b, Charsets.US_ASCII).trim('\u0000').trim()
            .split("\\")[0].toFloatOrNull() ?: default
}
