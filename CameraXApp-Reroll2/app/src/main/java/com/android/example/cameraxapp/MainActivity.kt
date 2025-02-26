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
import android.view.View
import android.view.Menu
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog


typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private var captureCount = 0
    private val faceAreaRatios = mutableListOf<Double>()
    private var currentFaceRatio = 0.0
    private var isBaselineSet = false
    private var isEyeModeEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // 显示ActionBar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 新增：初始化按钮状态
        val prefs = getSharedPreferences("WiscreenPrefs", Context.MODE_PRIVATE)
        isBaselineSet = prefs.contains("BASELINE_RATIO")

        initBlurLayer()
    }

    // 创建菜单
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    // 处理菜单点击
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reset -> {
                showResetConfirmationDialog()
                true
            }

            R.id.action_about -> {
                showAboutDialog()
                true
            }
            R.id.action_toggle_eye_mode -> {  // 修改：切换护眼模式
                toggleEyeMode()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleEyeMode() {
        isEyeModeEnabled = !isEyeModeEnabled // 切换护眼模式的状态

        // 更新模糊效果状态
        if (isEyeModeEnabled) {
            // 开启护眼模式时应用模糊
            updateBlurEffect(currentFaceRatio)
        } else {
            // 关闭护眼模式时移除模糊
            removeBlurEffect()
        }

        // 显示状态
        val status = if (isEyeModeEnabled) "开启护眼模式" else "关闭护眼模式"
        Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
    }

    private fun removeBlurEffect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 清除模糊效果
            viewBinding.blurContainer.setRenderEffect(null)
        } else {
            // 低版本清除效果
            viewBinding.blurContainer.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_confirm_title)
            .setMessage(R.string.reset_confirm_message)
            .setPositiveButton(R.string.confirm) { _, _ ->
                resetBaseline()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.version_info))
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    //修改基准值
    private fun resetBaseline() {
        val prefs = getSharedPreferences("WiscreenPrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        runOnUiThread {
            viewBinding.apply {
                imageCaptureButton.visibility = View.VISIBLE
                progressTextView.visibility = View.VISIBLE  // 恢复显示
                faceAreaRatioTextView.visibility = View.VISIBLE
                guideTextView.visibility = View.VISIBLE
                progressTextView.text = getString(R.string.progress_template, 0)
            }
            Toast.makeText(this, R.string.baseline_reset_toast, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
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
                        val faceArea = boundingBox.width() * boundingBox.height().toDouble() // 转为 Double
                        val imageArea = image.width * image.height.toDouble()     // 转为 Double
                        val faceAreaRatio = faceArea / imageArea

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

                    // 修改人脸检测UI更新
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer { ratio ->
                        currentFaceRatio = ratio
                        runOnUiThread {
                            currentFaceRatio = ratio
                            updateBlurEffect(ratio) // 动态更新模糊
                            viewBinding.faceAreaRatioTextView.text =
                                getString(R.string.face_ratio_template, ratio)
                            viewBinding.progressTextView.text =
                                getString(R.string.progress_template, captureCount)
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
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
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

    private fun calculateAndSaveBaseline() {
        if (faceAreaRatios.isEmpty()) return

        val averageRatio = faceAreaRatios.average()
        val prefs = getSharedPreferences("WiscreenPrefs", Context.MODE_PRIVATE)
        prefs.edit().putFloat("BASELINE_RATIO", averageRatio.toFloat()).apply()

        runOnUiThread {
            // 隐藏所有相关UI元素
            viewBinding.apply {
                imageCaptureButton.visibility = View.GONE
                progressTextView.visibility = View.GONE  // 新增：隐藏进度提示
                faceAreaRatioTextView.visibility = View.GONE  // 可选：隐藏占比显示
                guideTextView.visibility = View.GONE
            }

            // 显示完成提示（新增）
            Toast.makeText(
                this,
                getString(R.string.baseline_set_success, averageRatio),
                Toast.LENGTH_LONG
            ).show()
        }

        // 重置计数器（新增）
        captureCount = 0
        faceAreaRatios.clear()
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
                    // 使用currentFaceRatio而不是从TextView解析
                    faceAreaRatios.add(currentFaceRatio)
                    captureCount++
                    // 更新 UI
                    runOnUiThread {
                        viewBinding.progressTextView.text =
                            getString(R.string.progress_template, captureCount)
                        val msg =
                            getString(R.string.capture_progress, captureCount, currentFaceRatio)
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        viewBinding.progressTextView.text =
                            getString(R.string.progress_template, captureCount)
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

    private fun initBlurLayer() {
        viewBinding.blurContainer.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            viewBinding.blurContainer.setRenderEffect(null) // 初始化清空效果
        }
    }

    private fun getBaselineRatio(): Float {
        val prefs = getSharedPreferences("WiscreenPrefs", Context.MODE_PRIVATE)
        return prefs.getFloat("BASELINE_RATIO", 0.15f) // 默认15%
    }
    private fun updateBlurEffect(faceRatio: Double) {
        // 获取存储的基准值
        val baseline = getBaselineRatio().toDouble()

        // 计算增强系数（示例：非线性映射）
        val enhancement = when {
            faceRatio < baseline -> 0.0
            else -> (faceRatio - baseline) / (1 - baseline)
        }.coerceIn(0.0, 1.0)

        // 转换为实际模糊半径（最大25f）
        val blurRadius = (25 * enhancement).toFloat()

        // 应用模糊效果
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyBlurEffect(blurRadius)
        } else {
            // 低版本处理：透明度替代
            viewBinding.blurContainer.setBackgroundColor(
                Color.argb((enhancement * 180).toInt(), 255, 255, 255)
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyBlurEffect(radius: Float) {
        val blurEffect = RenderEffect.createBlurEffect(
            radius,
            radius,
            Shader.TileMode.MIRROR
        )
        viewBinding.blurContainer.setRenderEffect(blurEffect)
    }

}







