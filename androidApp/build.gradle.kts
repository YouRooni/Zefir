import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use(::load) }
val zefirVersionName = rootProject.extra["zefirVersionName"] as String
val zefirVersionCode = rootProject.extra["zefirVersionCode"] as Int

abstract class PrepareLegal : Sync() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

val prepareLegal = tasks.register<PrepareLegal>("prepareLegal") {
    from(rootProject.files("LICENSE", "NOTICE"))
    from(rootProject.file("licenses"))
    from(rootProject.file("core/prebuilt/legal")) { include("core-revisions.txt") }
    outputDirectory.set(layout.buildDirectory.dir("generated/legal"))
    into(outputDirectory)
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareLegal, PrepareLegal::outputDirectory)
}

check(System.getenv("CI") != "true" || keystoreFile.exists() || !gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
    "Release builds on CI require keystore.properties."
}

android {
    namespace = "dev.rooni.zefir"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.rooni.zefir"
        minSdk = 26
        targetSdk = 36
        versionCode = zefirVersionCode
        versionName = zefirVersionName
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                val storePath = keystore.getProperty("KEYSTORE_FILE") ?: "release.jks"
                storeFile = rootProject.file(storePath)
                storePassword = keystore.getProperty("KEYSTORE_PASSWORD")
                keyAlias = keystore.getProperty("KEY_ALIAS")
                keyPassword = keystore.getProperty("KEY_PASSWORD")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets["main"].jniLibs.directories.add("../core/prebuilt/jniLibs")
    sourceSets["main"].assets.directories.add("../core/prebuilt/geo")

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        noCompress += listOf("metadb", "dat")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(files("../core/prebuilt/vpncore-classes.jar"))
    implementation(libs.activity.compose)
    implementation(libs.work)
    implementation(libs.serialization)
}
