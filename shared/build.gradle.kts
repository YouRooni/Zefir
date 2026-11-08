plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
}

val zefirVersionName = rootProject.extra["zefirVersionName"] as String
val zefirChannel = (findProperty("zefir.channel") as String?) ?: "github"
val generateAppVersion = tasks.register("generateAppVersion") {
    val destination = layout.buildDirectory.dir("generated/appVersion")
    inputs.property("versionName", zefirVersionName)
    inputs.property("channel", zefirChannel)
    outputs.dir(destination)
    doLast {
        destination.get().file("dev/rooni/zefir/ui/AppVersion.kt").asFile.apply {
            parentFile.mkdirs()
            // play = Zefir X для Google Play: без подмены устройства
            val play = zefirChannel == "play"
            val name = if (play) "Zefir X" else "Zefir"
            writeText("package dev.rooni.zefir.ui\n\nconst val APP_VERSION = \"$zefirVersionName\"\nconst val APP_CHANNEL = \"$zefirChannel\"\nconst val APP_NAME = \"$name\"\nconst val PLAY_BUILD = $play\n")
        }
    }
}
kotlin.sourceSets.commonMain { kotlin.srcDir(generateAppVersion) }

kotlin {
    androidLibrary {
        namespace = "dev.rooni.zefir.shared"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
    }
    jvm("desktop")
    jvmToolchain(21)

    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "org.jetbrains.compose.resources.ExperimentalResourceApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
        )
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            api(compose.components.resources)
            implementation("org.jetbrains.compose.ui:ui-backhandler:1.12.2")
            api(libs.material3)
            implementation(libs.icons)
            implementation(libs.kolor)
            implementation(libs.qrose)
            implementation(libs.snakeyaml)
            implementation(libs.ktor.core)
            implementation(libs.ktor.okhttp)
            api(libs.coroutines)
            api(libs.serialization)
        }
        androidMain.dependencies {
            compileOnly(files("../core/prebuilt/vpncore-classes.jar"))
            implementation(libs.activity.compose)
            implementation(libs.core.ktx)
            implementation(libs.scanner)
            implementation(libs.zxing)
            implementation(libs.coroutines.play)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
                implementation(libs.jna)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.desktop.uiTestJUnit4)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "dev.rooni.zefir.res"
}
