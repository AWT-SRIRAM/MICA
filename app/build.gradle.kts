import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val isPublicRelease = project.findProperty("publicRelease")?.toString()?.toBoolean() == true
fun localSecret(name: String): String {
    if (isPublicRelease) return ""
    val raw = localProps.getProperty(name)
        ?: localProps.getProperty(name.replace("MICA_", "MSPLAYER_"))
        ?: System.getenv(name)
        ?: System.getenv(name.replace("MICA_", "MSPLAYER_"))
        ?: ""
    return raw.replace("\\", "\\\\").replace("\"", "\\\"")
}

android {
    namespace = "app.mica"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.mica"
        minSdk = 26
        targetSdk = 36
        versionCode = 31
        versionName = "0.8.3"
        ndk { abiFilters += (project.findProperty("testAbi")?.toString() ?: "arm64-v8a") }
        testInstrumentationRunner = "app.mica.SafeTestRunner"
        manifestPlaceholders["appLabel"] = "MICA"
        buildConfigField("String", "DEFAULT_GEMINI_KEY", "\"${localSecret("MICA_GEMINI_KEY")}\"")
        buildConfigField("String", "DEFAULT_GROQ_KEY", "\"${localSecret("MICA_GROQ_KEY")}\"")
        buildConfigField("String", "DEFAULT_DEEPGRAM_KEY", "\"${localSecret("MICA_DEEPGRAM_KEY")}\"")
        buildConfigField("String", "DEFAULT_CLOUDFLARE_TOKEN", "\"${localSecret("MICA_CLOUDFLARE_TOKEN")}\"")
        buildConfigField("String", "DEFAULT_OPENSUBTITLES_KEY", "\"${localSecret("MICA_OPENSUBTITLES_KEY")}\"")
        buildConfigField("String", "DEFAULT_SUBDL_KEY", "\"${localSecret("MICA_SUBDL_KEY")}\"")
        buildConfigField("String", "DEFAULT_SUBSOURCE_KEY", "\"${localSecret("MICA_SUBSOURCE_KEY")}\"")
        buildConfigField("String", "DEFAULT_WYZIE_KEY", "\"${localSecret("MICA_WYZIE_KEY")}\"")
        buildConfigField("String", "DEFAULT_TMDB_KEY", "\"${localSecret("MICA_TMDB_KEY")}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.useLegacyPackaging = false; resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    signingConfigs {
        val ks = System.getenv("MICA_KEYSTORE") ?: System.getenv("MSPLAYER_KEYSTORE")
        val pass = System.getenv("MICA_STORE_PASSWORD") ?: System.getenv("MSPLAYER_STORE_PASSWORD")
        if (ks != null && pass != null) create("localTest") {
            storeFile = file(ks)
            storePassword = pass
            keyAlias = "mica"
            keyPassword = pass
        }
    }
    buildTypes {
        debug { manifestPlaceholders["appLabel"] = "MICA" }
        release { isMinifyEnabled = false; if(signingConfigs.findByName("localTest") != null) signingConfig=signingConfigs.getByName("localTest") } }
    lint { abortOnError = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.github.kyant0:backdrop:1.0.0")
    implementation("androidx.room:room-runtime:2.7.1")
    annotationProcessor("androidx.room:room-compiler:2.7.1")
    if(project.findProperty("localVlcAar") != null) {
        implementation(files(project.property("localVlcAar")))
        implementation("androidx.legacy:legacy-support-v4:1.0.0")
    } else implementation("org.videolan.android:libvlc-all:3.7.7")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
