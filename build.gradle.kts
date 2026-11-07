import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
}

val appRelease = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
extra["zefirVersionName"] = appRelease.getProperty("versionName")
extra["zefirVersionCode"] = appRelease.getProperty("versionCode").toInt()
