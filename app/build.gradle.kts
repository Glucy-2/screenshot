import java.io.FileInputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.agp.app)
}

// 从本地 signing.properties 读取 release 签名（该文件与 release.keystore 均被
// .gitignore 忽略，不提交）。文件不存在时为 null，release 回退 debug 签名。
val releaseSigning = file("signing.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "io.github.a422015028.screenshot"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.a422015028.screenshot"
        minSdk = 26
        targetSdk = 37
        versionCode = 31
        versionName = "3.1.0"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("STORE_FILE"))
                storePassword = releaseSigning.getProperty("STORE_PASSWORD")
                keyAlias = releaseSigning.getProperty("KEY_ALIAS")
                keyPassword = releaseSigning.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = if (releaseSigning != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs["debug"]
            }
            // AGP 8.3+ 默认把 Git revision 写进 version-control-info.textproto，发布包不需要
            vcsInfo {
                include = false
            }
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
}

// =============================================================================
// 移除 AGP 强制注入的 app-metadata.properties（重签名后处理）
// =============================================================================
// META-INF/com/android/build/gradle/app-metadata.properties 由 packageRelease
// 直接注入 APK，绕过资源合并流程，packaging.resources.excludes 对其无效
// （AGP 9.2.1 实测确认），也没有官方 DSL 可以关闭。
// version-control-info.textproto 已由 buildType 的 vcsInfo { include = false }
// 从源头关闭，不需要在这里处理。
// 方案：在 packageRelease 之后用 Zip API 从 APK 删除该 entry，
//       再用 zipalign + apksigner 重新对齐和签名。
// =============================================================================

// 需要从 APK 中移除的文件
private val metadataToStrip = setOf(
    "META-INF/com/android/build/gradle/app-metadata.properties"
)

// 在 doLast 中执行外部命令（Gradle exec 在 doLast 闭包中不可用，改用 ProcessBuilder）
fun runCommand(vararg args: String) {
    val process = ProcessBuilder(*args)
        .redirectErrorStream(true)
        .start()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        val output = process.inputStream.bufferedReader().readText()
        throw GradleException("命令执行失败 (exit=$exitCode): ${args.joinToString(" ")}\n$output")
    }
}

// 从 local.properties 或环境变量读取 Android SDK 路径
fun findSdkDir(): java.io.File {
    val localProps = file("${rootDir}/local.properties")
    if (localProps.exists()) {
        val props = Properties()
        FileInputStream(localProps).use { props.load(it) }
        val sdkDir = props.getProperty("sdk.dir")
        if (sdkDir != null) {
            return file(sdkDir.replace("\\:", ":"))
        }
    }
    val env = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (env != null) {
        return file(env)
    }
    throw GradleException("找不到 Android SDK 路径，请设置 local.properties 中的 sdk.dir 或 ANDROID_HOME")
}

tasks.matching { it.name == "packageRelease" }.configureEach {
    doLast {
        // 使用与 release 构建一致的签名（release 签名，或回退 debug 签名）
        val signing = android.signingConfigs.findByName("release")
            ?: android.signingConfigs.findByName("debug")
        if (signing == null || signing.storeFile == null) {
            return@doLast
        }
        val apk = outputs.files.asFileTree.files.first { it.extension == "apk" }
        val sdkDir = findSdkDir()
        val btDir = file("$sdkDir/build-tools/${android.buildToolsVersion}")
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val zipalign = file("$btDir/${if (isWindows) "zipalign.exe" else "zipalign"}")
        val apksigner = file("$btDir/${if (isWindows) "apksigner.bat" else "apksigner"}")

        val stripped = file("${apk.parentFile}/${apk.nameWithoutExtension}-stripped.apk")
        val aligned = file("${apk.parentFile}/${apk.nameWithoutExtension}-aligned.apk")

        try {
            // 1. 从 APK 中删除无用的元数据 entry
            val zipIn = ZipFile(apk)
            try {
                val zipOut = ZipOutputStream(stripped.outputStream())
                try {
                    val entries = zipIn.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name !in metadataToStrip) {
                            val newEntry = ZipEntry(entry.name)
                            newEntry.time = entry.time
                            // 保持原始压缩方法：Android 11+ 要求 resources.arsc
                            // 未压缩（STORED）并在 4 字节边界对齐，否则安装失败
                            if (entry.method == ZipEntry.STORED) {
                                newEntry.method = ZipEntry.STORED
                                newEntry.size = entry.size
                                newEntry.crc = entry.crc
                                newEntry.compressedSize = entry.size
                            } else {
                                newEntry.method = ZipEntry.DEFLATED
                            }
                            zipOut.putNextEntry(newEntry)
                            if (!entry.isDirectory) {
                                zipIn.getInputStream(entry).copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }
                    }
                } finally {
                    zipOut.close()
                }
            } finally {
                zipIn.close()
            }

            // 2. zipalign 对齐
            runCommand(zipalign.absolutePath, "-f", "4", stripped.absolutePath, aligned.absolutePath)

            // 3. apksigner 重新签名（覆盖原始 APK）
            runCommand(
                apksigner.absolutePath, "sign",
                "--ks", signing.storeFile!!.absolutePath,
                "--ks-pass", "pass:${signing.storePassword!!}",
                "--ks-key-alias", signing.keyAlias!!,
                "--key-pass", "pass:${signing.keyPassword!!}",
                "--out", apk.absolutePath,
                aligned.absolutePath
            )
        } finally {
            stripped.delete()
            aligned.delete()
        }

        logger.lifecycle("已从 APK 中移除 AGP 构建元数据: ${metadataToStrip.joinToString()}")
    }
}
