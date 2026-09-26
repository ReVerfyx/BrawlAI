import java.net.URI

plugins {
    id("com.android.application")
}

android {
    namespace = "com.reverfyx.brawlai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.reverfyx.brawlai"
        minSdk = 26
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0"

        vectorDrawables.useSupportLibrary = false
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
}

val downloadPylaModels = tasks.register("downloadPylaModels") {
    group = "pyla"
    description = "Downloads the pinned upstream PylaAI ONNX models into app assets."
    doLast {
        data class ModelSpec(val url: String, val bytes: Long)
        val revision = "203aab8556fb9e957eeac6df74f761cc1dc918d5"
        val base = "https://raw.githubusercontent.com/PylaAI/PylaAI/$revision/models"
        val models = mapOf(
            "mainInGameModel.onnx" to ModelSpec("$base/mainInGameModel.onnx", 10_585_057L),
            "closeTileDetector.onnx" to ModelSpec("$base/closeTileDetector.onnx", 10_606_119L),
            "tileDetector.onnx" to ModelSpec("$base/tileDetector.onnx", 10_606_102L)
        )
        val dir = file("src/main/assets/models")
        dir.mkdirs()

        models.forEach { (name, spec) ->
            val out = dir.resolve(name)
            if (!out.exists() || out.length() != spec.bytes) {
                logger.lifecycle("Downloading pinned PylaAI model: $name")
                val tmp = dir.resolve("$name.part")
                if (tmp.exists()) tmp.delete()
                val connection = URI(spec.url).toURL().openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    setRequestProperty("User-Agent", "BrawlAI-Gradle")
                }
                connection.getInputStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                check(tmp.length() == spec.bytes) {
                    "Model $name has unexpected size ${tmp.length()} (expected ${spec.bytes})"
                }
                if (out.exists()) out.delete()
                check(tmp.renameTo(out)) { "Could not move $tmp to $out" }
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadPylaModels)
}
