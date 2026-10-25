import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Date-based version, same format and timezone as the release tag the CI publishes (v20261025).
// CI exports DATE, so the APK version always equals the tag; local builds use the Beijing date.
val buildDate: String = System.getenv("DATE")
    ?.takeIf { it.matches(Regex("""\d{8}""")) }
    ?: LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.BASIC_ISO_DATE)

android {
    namespace = "com.hexo141.liquidglass"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hexo141.liquidglass"
        minSdk = 24
        targetSdk = 36
        // A date versionCode also lets a newer APK install over an older one.
        versionCode = buildDate.toInt()
        versionName = "v$buildDate"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        jniLibs {
            // conscrypt-android ships .so files that are not 16KB-page aligned; extracting them
            // at install time avoids load failures on 16KB page size devices
            useLegacyPackaging = true
        }
    }
}

// ---------------------------------------------------------------------------
// Native payloads
//
// Four engines ship as arm64 executables in jniLibs:
//   native/      kernel-profile payload (libghostlock.so)
//   native-or/   page cache payload     (libopenroot.so + libopenbootstrap.so)
//   native-gpu/  Adreno GPU payloads    (libcheese-quest.so + libcheese-snapdragon.so)
//   native-pct/  posix-cpu-timers UAF   (libpct-opace6.so)
// All are compiled with the NDK, copied into jniLibs by a single task and
// stripped afterwards; a single writer keeps Gradle's output validation happy.
// ---------------------------------------------------------------------------

/** NDK location: local.properties -> ANDROID_NDK_HOME -> NDK_ROOT -> D:/android-ndk-r27d. */
fun resolveNdkDir(): String {
    val properties = Properties()
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use(properties::load)
    return sequenceOf(
        properties.getProperty("ndk.dir"),
        System.getenv("ANDROID_NDK_HOME"),
        System.getenv("NDK_ROOT"),
        "D:/android-ndk-r27d",
    ).firstOrNull { !it.isNullOrBlank() }!!
}

val ndkDir: String = resolveNdkDir()
val ndkPrebuilt: String = when {
    System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
    System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val ndkBinDir: File = File(ndkDir, "toolchains/llvm/prebuilt/$ndkPrebuilt/bin")
val ndkMake: String = File(ndkDir, "prebuilt/$ndkPrebuilt/bin/${if (ndkPrebuilt == "windows-x86_64") "make.exe" else "make"}").absolutePath
val ndkStrip: String = File(ndkBinDir, if (ndkPrebuilt == "windows-x86_64") "llvm-strip.exe" else "llvm-strip").absolutePath

val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")

val buildNativeExploit = tasks.register<Exec>("buildNativeExploit") {
    group = "build"
    description = "Compiles the arm64 kernel-profile payload with the NDK"
    workingDir = rootProject.layout.projectDirectory.dir("native").asFile
    commandLine(ndkMake, "ghostlock", "-j4")
    environment("NDK_ROOT", ndkDir)
    inputs.dir(rootProject.layout.projectDirectory.dir("native/core"))
    inputs.file(rootProject.layout.projectDirectory.file("native/Makefile"))
    outputs.file(rootProject.layout.buildDirectory.file("native/ghostlock"))
}

val buildNativePageCache = tasks.register<Exec>("buildNativePageCache") {
    group = "build"
    description = "Compiles the arm64 page cache payload with the NDK"
    workingDir = rootProject.layout.projectDirectory.dir("native-or").asFile
    commandLine(ndkMake, "payload", "-j4")
    environment("NDK_ROOT", ndkDir)
    inputs.dir(rootProject.layout.projectDirectory.dir("native-or"))
    outputs.dir(rootProject.layout.buildDirectory.dir("or"))
}

val buildNativeGpu = tasks.register<Exec>("buildNativeGpu") {
    group = "build"
    description = "Compiles the arm64 Adreno GPU payloads with the NDK"
    workingDir = rootProject.layout.projectDirectory.dir("native-gpu").asFile
    commandLine(ndkMake, "payload", "-j4")
    environment("NDK_ROOT", ndkDir)
    inputs.dir(rootProject.layout.projectDirectory.dir("native-gpu"))
    outputs.dir(rootProject.layout.buildDirectory.dir("gpu"))
}

val buildNativePct = tasks.register<Exec>("buildNativePct") {
    group = "build"
    description = "Compiles the arm64 posix-cpu-timers payload with the NDK"
    workingDir = rootProject.layout.projectDirectory.dir("native-pct").asFile
    commandLine(ndkMake, "payload", "-j4")
    environment("NDK_ROOT", ndkDir)
    inputs.dir(rootProject.layout.projectDirectory.dir("native-pct"))
    outputs.dir(rootProject.layout.buildDirectory.dir("pct"))
}

/** The only task that writes into jniLibs. */
val prepareNativePayloads = tasks.register<Copy>("prepareNativePayloads") {
    group = "build"
    description = "Copies every payload into jniLibs"
    dependsOn(buildNativeExploit, buildNativePageCache, buildNativeGpu, buildNativePct)
    from(rootProject.layout.buildDirectory.file("native/ghostlock")) {
        rename { "libghostlock.so" }
    }
    from(rootProject.layout.buildDirectory.dir("or")) {
        include("libopenroot.so", "libopenbootstrap.so")
    }
    from(rootProject.layout.buildDirectory.dir("gpu")) {
        include("libcheese-quest.so", "libcheese-snapdragon.so")
    }
    from(rootProject.layout.buildDirectory.dir("pct")) {
        include("libpct-opace6.so")
    }
    into(jniLibsDir)
}

val stripNativePayloads = tasks.register<Exec>("stripNativePayloads") {
    group = "build"
    description = "Strips the packaged payloads"
    dependsOn(prepareNativePayloads)
    val targets = listOf(
        "libghostlock.so",
        "libopenroot.so",
        "libopenbootstrap.so",
        "libcheese-quest.so",
        "libcheese-snapdragon.so",
        "libpct-opace6.so",
    ).map { jniLibsDir.file(it).asFile.absolutePath }
    commandLine(listOf(ndkStrip, "--strip-all") + targets)
    inputs.files(targets.map { File(it) })
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(stripNativePayloads)
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Compose basics: backdrop exposes its Compose deps at runtime scope, so they have to be
    // declared here for compilation.
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)

    // Liquid Glass button (Compose, also pulls in shapes)
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)

    // Wireless debugging pairing (SPAKE2 / HKDF / AES-GCM)
    implementation(libs.androidx.annotation)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    // TLSv1.3 exportKeyingMaterial without relying on hidden APIs
    implementation(libs.conscrypt.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
