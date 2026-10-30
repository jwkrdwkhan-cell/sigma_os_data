package com.example.iphonecamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!cameraGranted || !audioGranted) {
            Toast.makeText(this, "أذونات الكاميرا والميكروفون مطلوبة لتشغيل التطبيق", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        requestPermissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                IPhoneCameraCompleteScreen(cameraExecutor, ::spoofExifToIPhone)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // دالة خداع بيانات الـ EXIF لتسجيل الصورة رسمياً كأنها ملتقطة بـ iPhone 15 Pro Max
    private fun spoofExifToIPhone(imageFile: File) {
        try {
            val exif = ExifInterface(imageFile.absolutePath)
            exif.setAttribute(ExifInterface.TAG_MAKE, "Apple")
            exif.setAttribute(ExifInterface.TAG_MODEL, "iPhone 15 Pro Max")
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "iOS 17.5.1")
            exif.setAttribute(ExifInterface.TAG_F_NUMBER, "1.78")
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/60")
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "6.86")
            exif.saveAttributes()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun IPhoneCameraCompleteScreen(cameraExecutor: ExecutorService, onImageCaptured: (File) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var cameraProvider: ProcessCameraProvider? by remember { mutableStateOf(null) }
    
    // حالات التحكم الكاملة (الآيفون)
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    var isLivePhotoActive by remember { mutableStateOf(false) }
    var selectedTimer by remember { mutableStateOf(0) } // 0, 3, 10 ثواني
    var selectedAspectRatio by remember { mutableStateOf("4:3") } // 4:3, 1:1, Full
    var selectedMode by remember { mutableStateOf("PHOTO") } // PHOTO, VIDEO, PORTRAIT, PANO, CINEMATIC
    var currentZoom by remember { mutableStateOf(1f) } // 0.5x, 1x, 3x
    var isTopMenuExpanded by remember { mutableStateOf(false) } // القائمة العلوية المنسدلة

    // زر الالتقاط وأنيميشن النبض
    var isCapturing by remember { mutableStateOf(false) }
    val shutterScale by animateFloatAsState(
        targetValue = if (isCapturing) 0.85f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ShutterAnim"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // 1. معاينة الكاميرا (Camera Preview)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    imageCapture = ImageCapture.Builder()
                        .setFlashMode(flashMode)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()

                    val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

                    try {
                        cameraProvider?.unbindAll()
                        val camera = cameraProvider?.bindToLifecycle(
                            lifecycleOwner, cameraSelector, preview, imageCapture
                        )
                    } catch (exc: Exception) {
                        exc.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        // 2. شريط التحكم العلوي (Top Control Bar & Dropdown Chevron)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 20.dp, end = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // سهم الخيارات العلوية (Chevron)
                IconButton(onClick = { isTopMenuExpanded = !isTopMenuExpanded }) {
                    Icon(
                        imageVector = if (isTopMenuExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand Menu",
                        tint = Color.White
                    )
                }

                // زر الفلاش (Flash)
                IconButton(onClick = {
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                        ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                    imageCapture?.flashMode = flashMode
                }) {
                    Icon(
                        imageVector = when (flashMode) {
                            ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                            ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                            else -> Icons.Default.FlashOff
                        },
                        contentDescription = "Flash",
                        tint = if (flashMode != ImageCapture.FLASH_MODE_OFF) Color.Yellow else Color.White
                    )
                }

                // زر الصور الحية (Live Photos - الدائرة المتقطعة)
                Box(
                    modifier = Modifier
                        .clickable { isLivePhotoActive = !isLivePhotoActive }
                        .padding(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RadioButtonChecked,
                        contentDescription = "Live Photo",
                        tint = if (isLivePhotoActive) Color.Yellow else Color.White
                    )
                }

                // مؤقت التصوير (Timer)
                IconButton(onClick = {
                    selectedTimer = when (selectedTimer) {
                        0 -> 3
                        3 -> 10
                        else -> 0
                    }
                }) {
                    Text(
                        text = if (selectedTimer == 0) "⏱️ OFF" else "${selectedTimer}s",
                        color = if (selectedTimer > 0) Color.Yellow else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // نسبة الأبعاد (Aspect Ratio: 4:3 / 1:1)
                IconButton(onClick = {
                    selectedAspectRatio = if (selectedAspectRatio == "4:3") "1:1" else "4:3"
                }) {
                    Text(
                        text = selectedAspectRatio,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // لوحة الخيارات المنسدلة عند الضغط على السهم العلوي
            if (isTopMenuExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Text("RAW Max", color = Color.Yellow, fontSize = 14.sp)
                    Text("Exposure +0.3", color = Color.White, fontSize = 14.sp)
                    Text("Filters: Vivid", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        // 3. الجزء السفلي المجمع (أزرار الزوم + أوضاع التصوير + شريط الالتقاط)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.3f))
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // أزرار التبديل السريع بين العدسات (0.5x, 1x, 3x) المطابقة للآيفون
            Row(
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(0.5f, 1.0f, 3.0f).forEach { zoom ->
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                if (currentZoom == zoom) Color.Yellow.copy(alpha = 0.3f) else Color.Transparent,
                                CircleShape
                            )
                            .clickable { currentZoom = zoom },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${zoom}x",
                            color = if (currentZoom == zoom) Color.Yellow else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // عجلة أوضاع التصوير الأفقية (Modes Wheel: Pano, Portrait, Photo, Video, Cinematic)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("TIME-LAPSE", "SLO-MO", "CINEMATIC", "VIDEO", "PHOTO", "PORTRAIT", "PANO").forEach { mode ->
                    Text(
                        text = mode,
                        color = if (selectedMode == mode) Color.Yellow else Color.LightGray,
                        fontSize = 13.sp,
                        fontWeight = if (selectedMode == mode) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clickable { selectedMode = mode }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // شريط الالتقاط السفلي الرئيسي (معرض الصور + زر الالتقاط الآيفوني + قلب الكاميرا)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // الألبوم المصغر على اليسار (Gallery Thumbnail)
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Color.DarkGray)
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Default.Image, contentDescription = "Gallery", tint = Color.White)
                }

                // زر الالتقاط الأساسي بتصميم الآيفون (Shutter Button)
                Box(
                    modifier = Modifier
                        .scale(shutterScale)
                        .size(80.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .background(if (selectedMode == "VIDEO") Color.Red else Color.White, CircleShape)
                        .clickable {
                            if (isCapturing) return@clickable
                            isCapturing = true

                            coroutineScope.launch(Dispatchers.IO) {
                                // التأخير في حال تفعيل المؤقت الزمني (Timer)
                                if (selectedTimer > 0) {
                                    delay((selectedTimer * 1000).toLong())
                                }

                                // محاكاة تتابع الإطارات (Multi-frame burst processing)
                                for (i in 1..3) {
                                    delay(30)
                                }

                                imageCapture?.let { capture ->
                                    val photoFile = File(
                                        context.externalMediaDirs.firstOrNull() ?: context.filesDir,
                                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
                                    )

                                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                                    capture.takePicture(
                                        outputOptions,
                                        ContextCompat.getMainExecutor(context),
                                        object : ImageCapture.OnImageSavedCallback {
                                            override fun onError(exc: ImageCaptureException) {
                                                coroutineScope.launch(Dispatchers.Main) {
                                                    Toast.makeText(context, "فشل الالتقاط: ${exc.message}", Toast.LENGTH_SHORT).show()
                                                    isCapturing = false
                                                }
                                            }

                                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                                // حقن بيانات الآيفون (EXIF Spoofing)
                                                onImageCaptured(photoFile)

                                                coroutineScope.launch(Dispatchers.Main) {
                                                    Toast.makeText(context, "تم حفظ الصورة بجودة iPhone 15 Pro Max ✨", Toast.LENGTH_SHORT).show()
                                                    isCapturing = false
                                                }
                                            }
                                        }
                                    )
                                } ?: run {
                                    isCapturing = false
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {}

                // زر تبديل الكاميرا (Flip Camera: Front / Rear)
                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }

                        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                        cameraProviderFuture.addListener({
                            val provider = cameraProviderFuture.get()
                            provider.unbindAll()
                            val preview = Preview.Builder().build()
                            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                            try {
                                provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }, ContextCompat.getMainExecutor(context))
                    },
                    modifier = Modifier
                        .size(50.dp)
                        .background(Color.DarkGray.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
