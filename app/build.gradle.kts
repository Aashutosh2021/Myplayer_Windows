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

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Copy> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

compose.desktop {
    application {
        mainClass = "com.example.myplayer.MainKt"
        javaHome = "C:/Program Files/Microsoft/jdk-21.0.10.7-hotspot"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe)
            packageName = "MyPlayer"
            packageVersion = "2.0.0"
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
                menuGroup = "MyPlayer"
                upgradeUuid = "68c22756-3474-4ec5-b006-258d4a9ec110"
            }
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