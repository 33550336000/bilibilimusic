plugins {
    id("com.android.application")
}

android {
    namespace = "com.tilixibiesi"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tilixibiesi"
        minSdk = 30
        targetSdk = 37
        versionCode = 13
        versionName = "33550336"
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    buildFeatures {
        buildConfig = false
        resValues = false
        shaders = false
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
        dex {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootDir}/yuanshen.keystore")
            storePassword = providers.gradleProperty("signing.password").get()
            keyAlias = providers.gradleProperty("signing.alias").get()
            keyPassword = providers.gradleProperty("signing.password").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_26
        targetCompatibility = JavaVersion.VERSION_26
    }
}

dependencies {
}

val copyReleaseApk = tasks.register("copyReleaseApk") {
    group = "build"
    description = "将 release APK 复制为 tibao.apk 输出到模块目录"
    dependsOn(tasks.named("assembleRelease"))

    val sourceFile: File = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
    val destFile: File = layout.projectDirectory.file("tibao.apk").asFile

    doLast {
        if (sourceFile.exists()) {
            sourceFile.copyTo(destFile, overwrite = true)
            println("\u001B[32mAPK 已复制到: ${destFile.absolutePath}\u001B[0m")
        } else {
            println("\u001B[31m未找到 APK 文件: ${sourceFile.absolutePath}\u001B[0m")
        }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(copyReleaseApk)
}
