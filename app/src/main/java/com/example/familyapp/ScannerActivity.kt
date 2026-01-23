package com.example.familyapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.familyapp.databinding.ActivityScannerBinding
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 检查并请求相机权限
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    // 配置 CameraX 预览流与图像分析器
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val scanner = BarcodeScanning.getClient()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImageProxy(scanner, imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e("Scanner", "Binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // 使用 ML Kit 处理相机帧数据并识别条码
    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(scanner: BarcodeScanner, imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        val barcodeValue = barcodes[0].rawValue ?: ""
                        if (barcodeValue.isNotEmpty()) {
                            val intent = Intent().apply {
                                putExtra("SCAN_RESULT", barcodeValue)
                            }
                            setResult(RESULT_OK, intent)
                            imageProxy.close()
                            finish()
                        }
                    }
                }
                .addOnFailureListener {
                    Log.e("Scanner", "Scanning failed", it)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    // 检查权限是否已授予
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // 处理权限请求结果回调
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, rs: IntArray) {
        super.onRequestPermissionsResult(rc, p, rs)
        if (rc == 10 && allPermissionsGranted()) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required to scan barcodes.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // 销毁 Activity 时关闭线程池
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}