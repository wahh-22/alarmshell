import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

// Keep even the generated debug key in the checkout, not ~/.android, and outside build/:
// a clean must not change the signature, or installed builds lose their stored session.
val localDebugKeystore = rootProject.layout.projectDirectory.file(".tools/signing/debug.keystore")

// Release signing is local-only: .tools/signing/release.properties (ignored) names the key and
// its passwords. Without it, assembleRelease produces an unsigned APK instead of failing.
val releaseSigning =
    rootProject.layout.projectDirectory
        .file(".tools/signing/release.properties")
        .asFile
        .takeIf { it.isFile }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "dev.wahh.alarmshell"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.wahh.alarmshell"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs.getByName("debug") {
        storeFile = localDebugKeystore.asFile
    }
    releaseSigning?.let { props ->
        signingConfigs.create("release") {
            storeFile = rootProject.file(".tools/signing/${props.getProperty("storeFile")}")
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            // The phone runs release builds: run-as must never expose this app's session.
            isDebuggable = false
            isMinifyEnabled = false
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}
dependencies { implementation(project(":core")) }

// AGP only auto-generates the default home-directory debug key. Generate our
// explicitly relocated, development-only key before signing validation instead.
val generateLocalDebugKeystore =
    tasks.register<Exec>("generateLocalDebugKeystore") {
        outputs.file(localDebugKeystore)
        onlyIf { !localDebugKeystore.asFile.exists() }
        doFirst {
            localDebugKeystore.asFile.parentFile
                .mkdirs()
        }
        commandLine(
            "${System.getProperty("java.home")}/bin/keytool",
            "-genkeypair",
            "-keystore",
            localDebugKeystore.asFile.absolutePath,
            "-storetype",
            "PKCS12",
            "-storepass",
            "android",
            "-keypass",
            "android",
            "-alias",
            "androiddebugkey",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "10000",
            "-dname",
            "CN=Android Debug,O=Android,C=US",
            "-noprompt",
        )
    }
tasks.matching { it.name == "validateSigningDebug" }.configureEach {
    dependsOn(generateLocalDebugKeystore)
}
