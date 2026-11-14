plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    sourceSets {
        main {
            kotlin.srcDirs("src/main/java")
            resources.srcDirs("src/main/resources")
        }
    }
}

// Enforce UTF-8 across all Java & Kotlin compilation tasks so Hindi/Urdu/Unicode never corrupts
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Copy> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

compose.desktop {
    application {
        mainClass = "com.example.myplayer.MainKt"
        // Dynamically resolve JDK 21 path so builds succeed on any Windows machine
        val preferredJdk = File("C:/Program Files/Microsoft/jdk-21.0.10.7-hotspot")
        javaHome = when {
            preferredJdk.exists() -> preferredJdk.absolutePath
            !System.getenv("JAVA_HOME").isNullOrBlank() && File(System.getenv("JAVA_HOME")).exists() ->
                System.getenv("JAVA_HOME")
            else -> System.getProperty("java.home")
        }
        // Fast-startup JVM arguments: TieredStopAtLevel=1 + G1GC cuts cold launch latency by ~4x
        jvmArgs(
            "-Xmx2G",
            "-Xms128M",
            "-XX:+UseG1GC",
            "-XX:TieredStopAtLevel=1",
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-Dsun.java2d.uiScale.enabled=true",
            "-Dsun.java2d.dpiaware=true"
        )
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe
            )
            packageName = "MyPlayer"
            packageVersion = "8.2.3"
            vendor = "MyPlayer"
            description = "MyPlayer Desktop Audiophile Music Player"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("package-resources"))
            modules(
                "java.base",
                "java.desktop",
                "java.datatransfer",
                "java.sql",
                "java.naming",
                "java.management",
                "java.instrument",
                "java.logging",
                "java.prefs",
                "java.xml",
                "java.net.http",
                "jdk.unsupported",
                "jdk.dynalink",
                "jdk.crypto.ec"
            )
            windows {
                iconFile.set(project.file("icons/MyPlayer.ico"))
                menuGroup = "MyPlayer"
                upgradeUuid = "68c22756-3474-4ec5-b006-258d4a9ec110"
                dirChooser = true
                shortcut = true
                menu = true
            }
        }
    }
}

tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    iconFile.set(project.file("icons/MyPlayer.ico"))
    if (name.contains("package", ignoreCase = true) && (name.contains("Exe") || name.contains("Msi"))) {
        freeArgs.add("--win-shortcut-prompt")
        // Explicitly pass --resource-dir so jpackage uses package-resources/windows/main.wxs & overrides.wxi
        val winResDir = project.file("package-resources/windows")
        if (winResDir.exists()) {
            freeArgs.addAll("--resource-dir", winResDir.absolutePath)
        }
    }
}

tasks.matching { it.name.contains("package", ignoreCase = true) && (it.name.contains("Exe") || it.name.contains("Msi")) }.configureEach {
    doFirst {
        val winResDir = file("package-resources/windows")
        winResDir.mkdirs()
        val ico = file("icons/MyPlayer.ico")
        if (ico.exists()) {
            ico.copyTo(File(winResDir, "MyPlayer.ico"), overwrite = true)
        }
        val resDir = project.layout.buildDirectory.dir("compose/tmp/resources").get().asFile
        resDir.mkdirs()
        winResDir.listFiles()?.forEach { f ->
            f.copyTo(File(resDir, f.name), overwrite = true)
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(compose.components.resources)

    // Core JVM & DI annotations
    implementation(libs.javax.inject)
    implementation(libs.org.json)
    implementation(libs.androidx.annotation)

    // Navigation & Lifecycle
    implementation(libs.navigation.compose.kmp)
    implementation(libs.lifecycle.viewmodel.compose.kmp)
    implementation(libs.coroutines.swing)

    // Audio Engine: VLCJ & mDNS Auto-Discovery: JmDNS
    implementation(libs.vlcj)
    implementation(libs.jmdns)

    // Persistence: SQLite JDBC & DataStore
    implementation(libs.sqlite.jdbc)
    implementation("androidx.datastore:datastore-preferences-core:1.1.1")

    // Image Loading: Coil 3 Multiplatform
    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.okhttp)

    // Networking & YouTube/Streaming
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")

    // Testing
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}