package com.android.example.cameraxapp

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.appcompat.app.AlertDialog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.widget.ImageView
import androidx.room.util.copy
import com.google.mlkit.vision.face.FaceDetector
import java.io.ByteArrayOutputStream


typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var processedImageView: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        processedImageView = findViewById(R.id.processedImageView)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }


        cameraExecutor = Executors.newSingleThreadExecutor()

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720)) // 可选，设置图像分辨率
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER) // 设置背压策略
            .build()

// 设置图像分析器
        val imageAnalyzer = ImageAnalyzer(processedImageView) // 传递一个 ImageView 或其他必需参数

// 设置图像分析器
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), imageAnalyzer)

    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Set up the preview use case
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
            }

            try {
                // Select the back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Unbind all use cases before rebinding
                cameraProvider.unbindAll()

                // Bind the camera use cases to the lifecycle
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview
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

    private fun requestPermissions() {
        // Check if we should show a rationale to the user
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.CAMERA)) {
            // Show an explanation to the user
            AlertDialog.Builder(this)
                .setTitle("Camera Permission Required")
                .setMessage("This app needs the Camera permission to function properly.")
                .setPositiveButton("OK") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
                    )
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                    finish() // Close the app if permissions are denied
                }
                .create()
                .show()
        } else {
            // Request permissions
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    // Handle the result of the permissions request
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish() // Close the app if permissions are not granted
            }
        }
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
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}

// ImageProxy 转换为 Bitmap
fun ImageProxy.toBitmap(): Bitmap? {
    val plane = this.planes[0]
    val buffer = plane.buffer
    val size = buffer.remaining()
    val yuvData = ByteArray(size)
    buffer.get(yuvData)

    // YUV 数据转换成 NV21 格式
    val yuvImage = YuvImage(yuvData, android.graphics.ImageFormat.NV21, this.width, this.height, null)
    val outputStream = ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, this.width, this.height), 100, outputStream)
    val jpegData = outputStream.toByteArray()

    // 使用 BitmapFactory 解码 JPEG 数据为 Bitmap
    return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
}

// 绘制人脸矩形框
fun drawFaceRectangles(bitmap: Bitmap, boundingBox: Rect): Bitmap {
    // 创建一个可变的 Bitmap，用于绘制矩形
    val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

    // 创建 Canvas，准备在 Bitmap 上绘制
    val canvas = Canvas(mutableBitmap)
    val paint = Paint()

    // 设置矩形框的颜色和宽度
    paint.color = Color.RED
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 8f

    // 在 Bitmap 上绘制矩形框
    canvas.drawRect(boundingBox, paint)

    return mutableBitmap
}

// Analyzer 类，用于进行人脸检测
class ImageAnalyzer(private val processedImageView: ImageView) : ImageAnalysis.Analyzer {
    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
    )

    // 实现 Analyzer 接口中的抽象方法 analyze
    override fun analyze(image: ImageProxy) {
        // 将 ImageProxy 转换为 Bitmap
        val bitmap = image.toBitmap()

        bitmap?.let {
            // 进行人脸检测
            val inputImage = InputImage.fromBitmap(it, 0)
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    // 处理每个人脸
                    for (face in faces) {
                        // 获取人脸的边界框
                        val boundingBox = face.boundingBox

                        // 在 Bitmap 上绘制矩形框
                        val processedBitmap = drawFaceRectangles(it, boundingBox)

                        // 在主线程中更新 UI
                        (processedImageView.context as Activity).runOnUiThread {
                            processedImageView.setImageBitmap(processedBitmap)
                        }
                    }
                }
                .addOnFailureListener {
                    // 处理检测失败
                    Log.e("ImageAnalyzer", "Face detection failed", it)
                }
        }

        // 完成后记得关闭图像
        image.close()
    }
}


