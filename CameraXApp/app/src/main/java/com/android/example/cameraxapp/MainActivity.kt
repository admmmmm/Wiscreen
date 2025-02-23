package com.android.example.cameraxapp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.android.example.cameraxapp.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.widget.Toast
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.PermissionChecker
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import android.content.Context

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private var captureCount = 0
    private val faceAreaRatios = mutableListOf<Double>()
    private var currentFaceRatio = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    class FaceAnalyzer(private val listener: (Double) -> Unit) : ImageAnalysis.Analyzer {
        private val faceDetector = FaceDetection.getClient()

        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        @OptIn(ExperimentalGetImage::class)
        override fun analyze(image: ImageProxy) {
            val rotationDegrees = image.imageInfo.rotationDegrees
            val mediaImage = image.image
            val inputImage = InputImage.fromMediaImage(mediaImage!!, rotationDegrees)

            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        val face = faces[0]
                        val boundingBox = face.boundingBox
                        val faceArea = boundingBox.width() * boundingBox.height()
                        val imageArea = image.width * image.height
                        val faceAreaRatio = faceArea.toDouble() / imageArea

                        // 调用 listener 更新 UI
                        listener(faceAreaRatio)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Face detection failed", e)
                }
                .addOnCompleteListener {
                    image.close()
                }
        }
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 1. 初始化 Preview
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider) }

            // 2. 初始化 ImageCapture（拍照功能）
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // 3. 初始化 ImageAnalysis（人脸检测）
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer { ratio ->
                        currentFaceRatio = ratio // 直接更新变量
                        runOnUiThread {
                            viewBinding.faceAreaRatioTextView.text = "Face Area Ratio: %.2f".format(ratio)
                        }
                    })
                }
            // 4. 选择前置摄像头
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                // 绑定所有用例到相机
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }


    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    private fun calculateAndSaveBaseline() {
        if (faceAreaRatios.isEmpty()) return

        // 计算平均值
        val averageRatio = faceAreaRatios.average()

        // 存储到 SharedPreferences
        val prefs = getSharedPreferences("WiscreenPrefs", Context.MODE_PRIVATE)
        prefs.edit().putFloat("BASELINE_RATIO", averageRatio.toFloat()).apply()

        // 重置计数
        captureCount = 0
        faceAreaRatios.clear()

        // 提示用户
        runOnUiThread {
            Toast.makeText(
                this@MainActivity,
                "基准值已设定：${"%.2f".format(averageRatio)}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // 1. 创建文件名和存储路径
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Wiscreen")
            }
        }

        // 2. 创建输出选项
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        // 3. 执行拍照
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // 获取当前人脸占比（需从 TextView 获取）
                    val currentRatio = viewBinding.faceAreaRatioTextView.text
                        .toString()
                        .replace("Face Area Ratio: ", "")
                        .toDoubleOrNull() ?: 0.0

                    // 记录数据
                    faceAreaRatios.add(currentRatio)
                    captureCount++

                    // 更新 UI
                    runOnUiThread {
                        val msg = "已拍摄第 $captureCount/3 张 (当前值: ${"%.2f".format(currentRatio)})"
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }

                    // 如果拍满3张，计算基准值
                    if (captureCount >= 3) {
                        calculateAndSaveBaseline()
                    }
                }

                override fun onError(ex: ImageCaptureException) {
                    Log.e(TAG, "拍照失败: ${ex.message}", ex)
                }
            }
        )
    }
}


