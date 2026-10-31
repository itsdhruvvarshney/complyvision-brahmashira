import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Supabase credentials live in local.properties (git-ignored), never in source control:
//   supabase.url=https://<project-ref>.supabase.co
//   supabase.anonKey=<publishable / anon key>
// The package analyzer (FastAPI service in /analyzer) is configured the same way:
//   analyzer.url=http://10.0.2.2:8000      (emulator -> host; use the PC's LAN IP for a real phone)
//   analyzer.apiKey=<optional, must match ANALYZER_API_KEYS on the server>
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// A -P<key>=<value> Gradle property overrides local.properties (handy for pointing a build at a test backend)
fun config(key: String, default: String = ""): String =
    (project.findProperty(key) as String?) ?: localProps.getProperty(key, default)

android {
    namespace = "com.example.complyvision"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.complyvision"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${config("supabase.url")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${config("supabase.anonKey")}\"")
        buildConfigField("String", "ANALYZER_URL", "\"${config("analyzer.url", "http://10.0.2.2:8000")}\"")
        buildConfigField("String", "ANALYZER_API_KEY", "\"${config("analyzer.apiKey")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Stable pinned versions — Zero AAR Metadata Conflicts!
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // In-app package scanner
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}