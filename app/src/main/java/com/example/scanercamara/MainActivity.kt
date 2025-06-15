package com.example.scanercamara

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector as TFLiteObjectDetector
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var objectDetector: TFLiteObjectDetector
    private var detectedObjects by mutableStateOf<List<Detection>>(emptyList())
    private var isProcessing = false

    private lateinit var vibrator: Vibrator
    private lateinit var soundPool: SoundPool
    private var alertSoundId = 0
    private var soundLoaded = false
    private var isAlertPlaying = false


    private val specialObjects = setOf("person", "desk", "wall", "door", "stairs", "chair", "tv", "laptop")


    private val vibrationPatterns = mapOf(
        "person" to longArrayOf(0, 200, 100, 200),
        "desk" to longArrayOf(0, 400),
        "wall" to longArrayOf(0, 100, 50, 100, 50, 100),
        "door" to longArrayOf(0, 300, 100, 300),
        "stairs" to longArrayOf(0, 150, 100, 150, 100, 150, 100, 150),
        "chair" to longArrayOf(0, 500),
        "tv" to longArrayOf(0, 200, 100, 400),
        "laptop" to longArrayOf(0, 100, 50, 100)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        // Configurar SoundPool para alertas
        soundPool = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(audioAttributes)
                .build()
        } else {
            SoundPool(1, AudioManager.STREAM_MUSIC, 0)
        }


        alertSoundId = soundPool.load(this, R.raw.alert_sound, 1)

        soundPool.setOnLoadCompleteListener { _, _, status ->
            soundLoaded = (status == 0)
        }

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) setupCamera()
            else Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            setupCamera()
        }

        initObjectDetector()
    }

    private fun initObjectDetector() {
        val options = TFLiteObjectDetector.ObjectDetectorOptions.builder()
            .setMaxResults(5)
            .setScoreThreshold(0.5f)
            .build()

        objectDetector = TFLiteObjectDetector.createFromFileAndOptions(
            this,
            "model.tflite",
            options
        )
    }

    private fun setupCamera() {
        setContent {
            CameraPreview(detectedObjects)
        }
    }

    @Composable
    fun CameraPreview(detections: List<Detection>) {
        var previewSize by remember { mutableStateOf(IntSize.Zero) }
        var imageAnalysisSize by remember { mutableStateOf(IntSize.Zero) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    previewSize = it
                }
        ) {

            AndroidView(
                factory = { context ->
                    val previewView = PreviewView(context)

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                    val bitmap = imageProxy.toBitmap()
                                    imageAnalysisSize = IntSize(bitmap.width, bitmap.height)
                                    detectObjects(bitmap)
                                    imageProxy.close()
                                }
                            }

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            this@MainActivity,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    }, ContextCompat.getMainExecutor(context))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                if (previewSize.width == 0 || previewSize.height == 0) return@Canvas
                if (imageAnalysisSize.width == 0 || imageAnalysisSize.height == 0) return@Canvas

                val scaleX = previewSize.width.toFloat() / imageAnalysisSize.width.toFloat()
                val scaleY = previewSize.height.toFloat() / imageAnalysisSize.height.toFloat()

                detections.forEach { detection ->
                    val box = detection.boundingBox

                    val left = box.left * scaleX
                    val top = box.top * scaleY
                    val right = box.right * scaleX
                    val bottom = box.bottom * scaleY

                    drawRect(
                        color = Color.Red,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = 3f)
                    )
                }
            }
        }
    }

    private fun detectObjects(bitmap: Bitmap) {
        if (isProcessing) return
        isProcessing = true

        val image = TensorImage.fromBitmap(bitmap)
        val results = objectDetector.detect(image)

        // Umbral para considerar objeto "muy cerca" según área en imagen
        val closeObjects = results.filter {
            val box = it.boundingBox
            val boxArea = box.width() * box.height()
            val imageArea = bitmap.width * bitmap.height
            val boxSizeRatio = boxArea.toFloat() / imageArea.toFloat()
            boxSizeRatio > 0.2f // Cambia este valor para ajustar sensibilidad (0.2 = 20%)
        }

        runOnUiThread {
            if (closeObjects.isNotEmpty()) {
                detectedObjects = closeObjects
                processCloseDetections(closeObjects)
            } else {
                detectedObjects = emptyList()
                stopAlertSound()
            }
        }

        isProcessing = false
    }

    private fun processCloseDetections(closeObjects: List<Detection>) {
        val foundObjects = mutableSetOf<String>()

        closeObjects.forEach {
            val label = it.categories.firstOrNull()?.label?.lowercase() ?: "unknown"
            foundObjects.add(label)
        }

        if (foundObjects.isNotEmpty()) {
            for (obj in foundObjects) {
                Toast.makeText(this, "¡Objeto muy cerca: $obj!", Toast.LENGTH_SHORT).show()
                vibrateForObject(obj)
            }
            playAlertSound()
        } else {
            stopAlertSound()
        }
    }

    private fun vibrateForObject(obj: String) {
        val pattern = vibrationPatterns[obj] ?: longArrayOf(0, 300)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val vibrationEffect = VibrationEffect.createWaveform(pattern, -1)
            vibrator.vibrate(vibrationEffect)
        } else {
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun playAlertSound() {
        if (soundLoaded && !isAlertPlaying) {
            soundPool.play(alertSoundId, 1f, 1f, 1, -1, 1f) // Loop infinito
            isAlertPlaying = true
        }
    }

    private fun stopAlertSound() {
        if (isAlertPlaying) {
            soundPool.stop(alertSoundId)
            isAlertPlaying = false
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val yuvBytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(yuvBytes, 0, yuvBytes.size)

        val rotationDegrees = imageInfo.rotationDegrees
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPool.release()
    }
}
