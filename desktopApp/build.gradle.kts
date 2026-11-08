import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.coroutines.swing)
    implementation(libs.jna)
    implementation(libs.composenativetray)
}

val hostLinux = org.gradle.internal.os.OperatingSystem.current().isLinux
val appResources = layout.buildDirectory.dir("appResources")
val prepareCore = tasks.register<Sync>("prepareCore") {
    from(rootProject.file("core/prebuilt")) {
        include(if (hostLinux) "vpncore-linux-amd64" else "vpncore-windows-amd64.exe", "geo/**", "legal/**")
        if (hostLinux) filePermissions { unix("rwxr-xr-x") }
    }
    into(appResources.map { it.dir(if (hostLinux) "linux-x64" else "windows-x64") })
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareCore) }

val zefirVersionName = rootProject.extra["zefirVersionName"] as String
val appVersion = zefirVersionName.substringBefore('-')

compose.desktop.application {
    mainClass = "dev.rooni.zefir.MainKt"
    if (hostLinux) jvmArgs += listOf("--add-opens", "java.desktop/sun.awt.X11=ALL-UNNAMED")
    nativeDistributions {
        modules("java.instrument", "java.management", "jdk.unsupported", "java.naming", "jdk.crypto.ec", "jdk.accessibility")
        appResourcesRootDir.set(appResources)
        packageName = "Zefir"
        packageVersion = appVersion
        vendor = "YouRooni"
        description = "Zefir"
        licenseFile.set(rootProject.file("LICENSE"))
        linux {
            iconFile.set(project.file("icons/zefir.png"))
            packageName = "zefir"
            appCategory = "Network"
            menuGroup = "Network"
        }
        windows {
            iconFile.set(project.file("icons/zefir.ico"))
            upgradeUuid = "6f4c1d3a-8b27-4e59-a1c6-2d7e90b3f514"
            menuGroup = "Zefir"
            shortcut = true
            perUserInstall = true
            dirChooser = false
        }
    }
}

tasks.register<Zip>("packagePortable") {
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    from("portable") { into("Zefir") }
    from(rootProject.files("LICENSE", "NOTICE")) { into("Zefir/legal") }
    from(rootProject.file("licenses")) { into("Zefir/legal") }
    archiveFileName.set("Zefir-$zefirVersionName-windows-x64-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/portable"))
}

tasks.register<Tar>("packageLinux") {
    dependsOn("createDistributable")
    group = "compose desktop"
    description = "Packages Zefir for Linux as tar.gz"
    compression = Compression.GZIP
    from(layout.buildDirectory.dir("compose/binaries/main/app")) {
        filesMatching(listOf("Zefir/bin/**", "Zefir/lib/app/resources/vpncore-*", "Zefir/lib/runtime/bin/**", "Zefir/lib/runtime/lib/jspawnhelper")) {
            permissions { unix("rwxr-xr-x") }
        }
    }
    from("linux") {
        into("Zefir")
        filesMatching("*.sh") { permissions { unix("rwxr-xr-x") } }
    }
    from(project.file("icons/zefir.png")) { into("Zefir") }
    from(rootProject.files("LICENSE", "NOTICE")) { into("Zefir/legal") }
    from(rootProject.file("licenses")) { into("Zefir/legal") }
    archiveFileName.set("Zefir-$zefirVersionName-linux-x64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/linux"))
}

val packageCustomInstaller = tasks.register("packageCustomInstaller") {
    dependsOn("createDistributable")
    group = "compose desktop"
    description = "Packages custom Go-based Zefir setup installer"
    doLast {
        val installerDir = rootProject.file("installer")
        val appDir = layout.buildDirectory.dir("compose/binaries/main/app/Zefir").get().asFile
        val outDir = layout.buildDirectory.dir("compose/binaries/main/installer").get().asFile
        outDir.mkdirs()
        val outExe = File(outDir, "Zefir-$zefirVersionName-windows-x64-setup.exe")
        val stubExe = File(installerDir, "zefir-stub.exe")

        fun runCmd(vararg args: String) {
            val pb = ProcessBuilder(*args).directory(installerDir).inheritIO()
            val code = pb.start().waitFor()
            if (code != 0) throw GradleException("Command failed with exit code $code: ${args.joinToString(" ")}")
        }

        runCmd("go", "build", "-ldflags", "-s -w -H=windowsgui", "-o", stubExe.absolutePath, ".")
        runCmd(
            "go", "run", "./cmd/pack",
            "-stub", stubExe.absolutePath,
            "-dir", appDir.absolutePath,
            "-out", outExe.absolutePath,
            "-version", zefirVersionName
        )
        stubExe.delete()
        println("Custom installer built: ${outExe.absolutePath} (${outExe.length()} bytes)")
    }
}

tasks.register("packageInstaller") {
    dependsOn(packageCustomInstaller)
    group = "compose desktop"
    description = "Alias for packageCustomInstaller"
}
