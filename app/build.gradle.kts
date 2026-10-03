plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildVersionCode: Int = run {
    try {
        val p = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        out.toIntOrNull() ?: 1
    } catch (e: Exception) {
        1
    }
}

android {
    namespace = "com.streamxhd.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.streamxhd.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = buildVersionCode
        versionName = "1.2-build$buildVersionCode"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

tasks.register("writeVersionFile") {
    doLast {
        val out = File(rootProject.projectDir, "releases/version.txt")
        out.parentFile.mkdirs()
        out.writeText(buildVersionCode.toString())
        println("version.txt -> ${buildVersionCode}")
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("writeVersionFile")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
