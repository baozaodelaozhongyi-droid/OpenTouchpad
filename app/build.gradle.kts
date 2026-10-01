plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

android {
    namespace = "io.github.opentouchpad"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.opentouchpad"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.0"
    }

    // 仓库里绝不存密钥。CI 通过 GitHub Secrets 注入以下环境变量即可产出正式签名包：
    //   RELEASE_KEYSTORE_PATH / RELEASE_KEYSTORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
    signingConfigs {
        // 只在 CI 提供了密钥路径、且文件真实存在时才创建 release 签名配置；
        // 本地没有密钥时也能 assembleRelease（产出未签名包）。
        val ksPath = System.getenv("RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
        if (ksPath != null && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 没配签名时就是"未签名 APK"，先保证能编译能装（debug 包）
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
