dependencies {
    // CameraX core libraries
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")

    // ExifInterface لتعديل بيانات الآيفون
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    
    // الأيقونات الشاملة المستخدمة في الواجهة
    implementation("androidx.compose.material.icons-extended:icons-extended")
}
