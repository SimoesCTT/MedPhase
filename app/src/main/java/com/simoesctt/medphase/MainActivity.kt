package com.simoesctt.medphase

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var txtFile: TextView
    private lateinit var txtMetrics: TextView
    private lateinit var spinnerMethod: Spinner
    private lateinit var btnProcess: Button
    private lateinit var imgPreview: ImageView

    private var loaded: DicomLoader.DicomImage? = null
    private var normalized: FloatArray? = null

    private val methods = listOf(
        "Original (no filter)",
        "Gaussian blur",
        "Median 3x3",
        "Non-local means",
        "Riemann comb filter"
    )

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        android.util.Log.d("MedPhase", "Picker returned: $uri")
        if (uri != null) loadFile(uri)
        else android.util.Log.w("MedPhase", "Picker returned null URI")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtFile = findViewById(R.id.txtFile)
        txtMetrics = findViewById(R.id.txtMetrics)
        spinnerMethod = findViewById(R.id.spinnerMethod)
        btnProcess = findViewById(R.id.btnProcess)
        imgPreview = findViewById(R.id.imgPreview)

        spinnerMethod.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, methods
        )

        findViewById<Button>(R.id.btnLoad).setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }

        btnProcess.setOnClickListener { process() }
    }

    private fun loadFile(uri: Uri) {
        android.util.Log.d("MedPhase", "loadFile called with $uri")
        txtFile.text = "Loading DICOM…"
        btnProcess.isEnabled = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dcm = DicomLoader.load(this@MainActivity, uri)
                withContext(Dispatchers.Main) {
                    loaded = dcm
                    // Apply window/level if provided, else min-max normalize
                    val n = dcm.pixels.size
                    val norm = if (dcm.windowWidth > 0f && dcm.windowCenter != 0f) {
                        PhaseEngine.applyWindow(
                            dcm.pixels,
                            dcm.windowCenter,
                            dcm.windowWidth
                        )
                    } else {
                        var mn = Float.MAX_VALUE
                        var mx = -Float.MAX_VALUE
                        for (p in dcm.pixels) {
                            if (p < mn) mn = p
                            if (p > mx) mx = p
                        }
                        val range = if (mx > mn) mx - mn else 1f
                        FloatArray(n) { i -> (dcm.pixels[i] - mn) / range }
                    }
                    normalized = norm
                    txtFile.text = "${dcm.width} x ${dcm.height}  |  " +
                            "${dcm.bitsAllocated}-bit  |  ${dcm.modality}  |  " +
                            "${dcm.photometricInterpretation}"
                    imgPreview.setImageBitmap(toBitmap(norm, dcm.width, dcm.height))
                    btnProcess.isEnabled = true
                    txtMetrics.text = "—"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.util.Log.e("MedPhase", "LOAD FAILED", e)
                    txtFile.text = "Error: ${e.message}"
                    Toast.makeText(this@MainActivity, "Load failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun process() {
        val dcm = loaded ?: return
        val norm = normalized ?: return
        val methodIdx = spinnerMethod.selectedItemPosition
        val method = methods[methodIdx]

        btnProcess.isEnabled = false
        btnProcess.text = "Processing…"

        CoroutineScope(Dispatchers.Default).launch {
            val start = System.currentTimeMillis()
            val result = when (method) {
                "Gaussian blur" -> PhaseEngine.gaussianBlur(norm, dcm.width, dcm.height, 1.5f)
                "Median 3x3" -> PhaseEngine.median3x3(norm, dcm.width, dcm.height)
                "Non-local means" -> PhaseEngine.nlMeans(norm, dcm.width, dcm.height)
                "Riemann comb filter" -> PhaseEngine.riemannCombFilter(norm, dcm.width, dcm.height)
                else -> norm.copyOf()
            }
            val elapsed = System.currentTimeMillis() - start
            val p = PhaseEngine.psnr(norm, result)

            withContext(Dispatchers.Main) {
                imgPreview.setImageBitmap(toBitmap(result, dcm.width, dcm.height))
                txtMetrics.text = buildString {
                    appendLine("Method: $method")
                    appendLine("PSNR vs original: ${"%.2f".format(p)} dB")
                    appendLine("Time: ${elapsed} ms")
                    appendLine("Pixels: ${dcm.width * dcm.height}")
                }
                btnProcess.isEnabled = true
                btnProcess.text = "Process"
            }
        }
    }

    private fun toBitmap(arr: FloatArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (i in arr.indices) {
            val v = (arr[i].coerceIn(0f, 1f) * 255f).toInt()
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }
}
