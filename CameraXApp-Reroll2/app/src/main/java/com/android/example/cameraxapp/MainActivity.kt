package com.android.example.cameraxapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.android.example.cameraxapp.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    // region [1. 常量与配置]
    companion object {
        private const val TAG = "WiscreenApp"  // 修改TAG使其更容易识别
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_CODE_OVERLAY = 20
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SYSTEM_ALERT_WINDOW
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
    // endregion

    // region [2. 核心组件]
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private lateinit var overlayController: SystemOverlayController
    // endregion

    // region [3. 业务状态]
    private var captureCount = 0
    private val faceAreaRatios = mutableListOf<Double>()
    private var currentFaceRatio = 0.0
    private var isBaselineSet = false
    private var isEyeModeEnabled = false  // 默认关闭护眼模式
    // endregion

    // region [4. 生命周期]
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "应用启动")

        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        viewBinding.viewFinder.apply {
            elevation = 0f
            translationZ = 0f
        }

        overlayController = SystemOverlayController(this)
        initActionBar()
        setupCameraExecutor()
        setupClickListeners()

        checkAndRequestPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "应用销毁")
        overlayController.destroyOverlay()
        cameraExecutor.shutdown()
    }
    // endregion

    // region [5. 权限管理]
    private fun checkAndRequestPermissions() {
        Log.d(TAG, "开始检查权限...")
        
        // 首先检查相机权限
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (cameraPermission != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "请求相机权限")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CODE_PERMISSIONS
            )
            return
        }

        // 然后检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "请求悬浮窗权限")
            requestOverlayPermission()
            return
        }

        // 所有权限都已获得，初始化系统
        Log.i(TAG, "所有权限已获得，开始初始化系统")
        initializeSystem()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
                startActivityForResult(this, REQUEST_CODE_OVERLAY)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "相机权限已授予")
                // 相机权限获得后，检查悬浮窗权限
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlayPermission()
                } else {
                    initializeSystem()
                }
            } else {
                Log.e(TAG, "相机权限被拒绝")
                Toast.makeText(this, "需要相机权限才能使用此功能", Toast.LENGTH_LONG).show()
                showPermissionExplanationDialog()
            }
        }
    }

    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要权限")
            .setMessage("此应用需要相机权限和录音权限来实现人脸检测功能，需要悬浮窗权限来实现屏幕模糊效果。请在设置中授予这些权限。")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OVERLAY) {
            // 无论结果如何，都重新检查权限
            checkAndRequestPermissions()
        }
    }

    private fun initializeSystem() {
        try {
            Log.i(TAG, "开始初始化系统组件")
            
            // 先启动相机
            startCamera()
            
            // 确保相机预览可见
            viewBinding.viewFinder.visibility = View.VISIBLE
            
            // 然后创建悬浮窗
            overlayController.createOverlay()
            
            Log.i(TAG, "系统初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "系统初始化失败", e)
            Toast.makeText(this, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    // endregion

    // region [6. 相机管理]
    private fun startCamera() {
        Log.d(TAG, "开始初始化相机...")
        
        // 再次确认相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "没有相机权限，无法启动相机")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // 检查是否有前置相机
                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    Log.e(TAG, "设备没有前置相机")
                    Toast.makeText(this, "设备没有前置相机", Toast.LENGTH_LONG).show()
                    return@addListener
                }

                val preview = Preview.Builder()
                    .build()
                    .also { 
                        it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                    }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, FaceAnalyzer())
                    }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageCapture,
                        imageAnalyzer
                    )
                    
                    Log.i(TAG, "相机初始化成功")
                    
                    // 打印预览视图的状态
                    viewBinding.viewFinder.post {
                        Log.d(TAG, "预览视图状态: visible=${viewBinding.viewFinder.visibility == View.VISIBLE}, " +
                                 "width=${viewBinding.viewFinder.width}, " +
                                 "height=${viewBinding.viewFinder.height}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "相机绑定失败", e)
                    Toast.makeText(this, "相机绑定失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "相机初始化失败", e)
                Toast.makeText(this, "相机初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private inner class FaceAnalyzer : ImageAnalysis.Analyzer {
        private val faceDetector = FaceDetection.getClient()

        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                Log.w(TAG, "图像为空，跳过分析")
                imageProxy.close()
                return
            }

            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        Log.v(TAG, "检测到人脸")
                        handleFaceDetection(faces[0], imageProxy)
                    } else {
                        Log.v(TAG, "未检测到人脸")
                    }
                }
                .addOnFailureListener { e -> 
                    Log.e(TAG, "人脸检测失败", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }

        private fun handleFaceDetection(face: com.google.mlkit.vision.face.Face, image: ImageProxy) {
            val boundingBox = face.boundingBox
            val faceArea = boundingBox.width() * boundingBox.height().toDouble()
            val imageArea = image.width * image.height.toDouble()
            currentFaceRatio = faceArea / imageArea

            runOnUiThread {
                updateBlurEffect(currentFaceRatio)
            }
        }
    }

    private fun updateBlurEffect(faceRatio: Double) {
        if (!isEyeModeEnabled) {
            clearBlurEffects()
            if (isBaselineSet) {
                viewBinding.faceAreaRatioTextView.text = """
                    护眼模式已关闭
                    面部占比: ${(faceRatio * 100).roundToInt()}%
                    SDK版本: ${Build.VERSION.SDK_INT}
                    """.trimIndent()
            }
            return
        }

        val baseline = getBaselineRatio()
        val maxRatio = 0.7  // 最大人脸占比
        val enhancement = ((faceRatio - baseline) / (maxRatio - baseline)).coerceIn(0.0, 1.0)
        val blurRadius = (enhancement * 150).toFloat()  // 使用0-150的模糊半径范围

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                // 更新窗口属性
                window.attributes = window.attributes.apply {
                    // 设置背景模糊
                    flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    blurBehindRadius = blurRadius.toInt().coerceIn(0, 150)
                }

                // 设置半透明背景颜色
                val alpha = (enhancement * 80).toInt().coerceIn(0, 80)
                window.decorView.setBackgroundColor(Color.argb(alpha, 255, 255, 255))

                // 同时更新覆盖层的模糊效果
                overlayController.updateBlur(blurRadius)
            } catch (e: Exception) {
                Log.e(TAG, "更新模糊效果失败", e)
            }
        }

        if (isBaselineSet) {
            viewBinding.faceAreaRatioTextView.text = """
                护眼模式已开启
                面部占比: ${(faceRatio * 100).roundToInt()}%
                基准占比: ${(baseline * 100).roundToInt()}%
                增强系数: ${String.format("%.2f", enhancement)}
                模糊半径: ${String.format("%.1f", blurRadius)}
                SDK版本: ${Build.VERSION.SDK_INT}
                最大占比: 70%
                """.trimIndent()
        }
    }

    private fun clearBlurEffects() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                // 清除窗口模糊效果
                window.attributes = window.attributes.apply {
                    flags = flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                    blurBehindRadius = 0
                }
                // 清除背景颜色
                window.decorView.setBackgroundColor(Color.TRANSPARENT)
                // 清除覆盖层模糊效果
                overlayController.updateBlur(0f)
            } catch (e: Exception) {
                Log.e(TAG, "清除模糊效果失败", e)
            }
        }
    }

    private fun getBaselineRatio(): Float {
        return getSharedPreferences("WiscreenPrefs", Context.MODE_PRIVATE)
            .getFloat("BASELINE_RATIO", 0.15f)
    }
    // endregion

    // region [7. 系统覆盖层管理]
    class SystemOverlayController(private val context: Context) {
        private var overlayView: View? = null
        private var windowManager: WindowManager? = null
        private var currentRadius: Float = 0f

        fun createOverlay() {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                // 初始完全透明
                setBackgroundColor(Color.TRANSPARENT)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        or WindowManager.LayoutParams.FLAG_BLUR_BEHIND,  // 保留模糊标志
                PixelFormat.TRANSLUCENT
            ).apply {
                // 初始模糊半径设为0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    blurBehindRadius = 0
                }
            }

            try {
                windowManager?.addView(overlayView, params)
                Log.d(TAG, "系统级窗口创建成功")
            } catch (e: Exception) {
                Log.e(TAG, "系统级窗口创建失败", e)
            }
        }

        fun updateBlur(radius: Float) {
            if (radius == currentRadius) return

            overlayView?.let { view ->
                try {
                    val params = view.layoutParams as WindowManager.LayoutParams
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // 更新模糊半径
                        params.blurBehindRadius = radius.toInt().coerceIn(0, 150)
                    }
                    
                    // 更新背景透明度
                    val alpha = (radius / 150f * 60f).toInt().coerceIn(0, 60)
                    view.setBackgroundColor(Color.argb(alpha, 255, 255, 255))
                    
                    windowManager?.updateViewLayout(view, params)
                    currentRadius = radius
                    
                    Log.d(TAG, "更新系统级模糊效果: radius=$radius, alpha=$alpha")
                } catch (e: Exception) {
                    Log.e(TAG, "更新系统级模糊效果失败", e)
                    e.printStackTrace()
                }
            }
        }

        fun destroyOverlay() {
            try {
                Log.d(TAG, "清理系统级窗口...")
                overlayView?.let {
                    windowManager?.removeView(it)
                    overlayView = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "清理系统级窗口失败", e)
            }
        }
    }
    // endregion

    // region [8. 辅助功能]
    private fun initActionBar() {
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    private fun setupCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupClickListeners() {
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
    }

    private fun takePhoto() {
        Log.d(TAG, "开始拍照...")
        imageCapture?.let { capture ->
            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,
                        SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()))
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Wiscreen")
                    }
                }
            ).build()

            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        Log.d(TAG, "照片保存成功")
                        handlePhotoSaved()
                    }

                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "拍照失败", exc)
                        Toast.makeText(this@MainActivity, "拍照失败: ${exc.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    private fun handlePhotoSaved() {
        faceAreaRatios.add(currentFaceRatio)
        if (++captureCount >= 3) {
            calculateAndSaveBaseline()
        }
        updateProgressUI()
    }

    private fun calculateAndSaveBaseline() {
        val average = faceAreaRatios.average()
        Log.i(TAG, "计算基准值: $average (来自 ${faceAreaRatios.size} 个样本)")
        saveBaselineToPrefs(average)
        resetCaptureState()
        
        // 更新UI状态，但保留调试信息显示
        viewBinding.apply {
            imageCaptureButton.visibility = View.GONE
            progressTextView.visibility = View.GONE
            guideTextView.visibility = View.GONE
            // 保持faceAreaRatioTextView可见，用于显示调试信息
            faceAreaRatioTextView.visibility = View.VISIBLE
        }
        
        isBaselineSet = true
        Toast.makeText(this, "基准值设定: ${"%.2f".format(average)}", Toast.LENGTH_LONG).show()
    }

    private fun saveBaselineToPrefs(value: Double) {
        getSharedPreferences("WiscreenPrefs", Context.MODE_PRIVATE)
            .edit()
            .putFloat("BASELINE_RATIO", value.toFloat())
            .apply()
    }

    private fun resetCaptureState() {
        captureCount = 0
        faceAreaRatios.clear()
        updateProgressUI()
    }

    private fun updateProgressUI() {
        viewBinding.progressTextView.text = "进度: $captureCount/3"
    }
    // endregion

    // region [9. 菜单功能]
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reset -> showResetDialog().let { true }
            R.id.action_about -> showAboutDialog().let { true }
            R.id.action_toggle_eye_mode -> toggleEyeMode().let { true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showResetDialog() {
        AlertDialog.Builder(this)
            .setTitle("重置确认")
            .setMessage("确定要重置所有基准数据吗？")
            .setPositiveButton("确定") { _, _ -> resetBaselineData() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun resetBaselineData() {
        Log.i(TAG, "重置基准数据")
        getSharedPreferences("WiscreenPrefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        
        // 重置状态和UI
        isBaselineSet = false
        resetCaptureState()
        viewBinding.apply {
            imageCaptureButton.visibility = View.VISIBLE
            progressTextView.visibility = View.VISIBLE
            faceAreaRatioTextView.visibility = View.VISIBLE
            guideTextView.visibility = View.VISIBLE
            progressTextView.text = getString(R.string.progress_template, 0)
        }
        
        Toast.makeText(this, "基准值已重置", Toast.LENGTH_SHORT).show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("关于")
            .setMessage("版本: 1.0.0\n系统级护眼模糊解决方案")
            .setPositiveButton("确定", null)
            .show()
    }

    private fun toggleEyeMode() {
        isEyeModeEnabled = !isEyeModeEnabled
        if (isEyeModeEnabled) {
            updateBlurEffect(currentFaceRatio)
        } else {
            clearBlurEffects()
        }
        val status = if (isEyeModeEnabled) "护眼模式已开启" else "护眼模式已关闭"
        Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
    }
    // endregion
}
