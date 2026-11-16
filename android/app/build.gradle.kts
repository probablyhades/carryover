import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The sync engine is the Go program in ../cmd/carryover-agent, built for
// Android without cgo (no NDK needed) and packaged as a native library so
// Android extracts it to an executable location. Go only links android/arm64
// without cgo, so that is the one ABI.
val repoRoot: File = rootDir.parentFile
val agentOut = layout.buildDirectory.dir("agent/jniLibs")

val buildAgent by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Go sync engine as libcarryover.so"
    val out = agentOut.get().file("arm64-v8a/libcarryover.so").asFile
    inputs.files(fileTree(repoRoot) {
        include("go.mod", "go.sum", "cmd/**/*.go", "internal/**/*.go", "internal/**/*.gz")
        exclude("**/*_test.go")
    })
    outputs.file(out)
    workingDir = repoRoot
    environment("GOOS", "android")
    environment("GOARCH", "arm64")
    environment("CGO_ENABLED", "0")
    commandLine(
        providers.environmentVariable("GO").getOrElse("go"),
        "build", "-trimpath", "-ldflags", "-s -w", "-o", out.absolutePath, "./cmd/carryover-agent",
    )
}

// Optional release signing: android/keystore.properties (storeFile,
// storePassword, keyAlias, keyPassword) or the CARRYOVER_KEYSTORE_* variables.
val keystore = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    System.getenv("CARRYOVER_KEYSTORE_FILE")?.let { put("storeFile", it) }
    System.getenv("CARRYOVER_KEYSTORE_PASSWORD")?.let { put("storePassword", it) }
    System.getenv("CARRYOVER_KEY_ALIAS")?.let { put("keyAlias", it) }
    System.getenv("CARRYOVER_KEY_PASSWORD")?.let { put("keyPassword", it) }
}

android {
    namespace = "com.akfaiz.carryover"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.akfaiz.carryover"
        minSdk = 26
        targetSdk = 35
        // CI passes the release tag (-PversionName=0.3.0 -PversionCode=...).
        versionName = (project.findProperty("versionName") as String?) ?: "dev"
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystore.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    sourceSets["main"].jniLibs.srcDir(agentOut)

    packaging {
        // Extract libcarryover.so to nativeLibraryDir: it is run as a program.
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    lint {
        // Go only links android/arm64 without cgo (see buildAgent).
        disable += "ChromeOsAbiSupport"
    }
}

tasks.named("preBuild") { dependsOn(buildAgent) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
