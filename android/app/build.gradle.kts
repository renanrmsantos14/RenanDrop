import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

// Versão própria do app (não é a do Git): android/version.properties, subida por scripts/bump-version.py --android.
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val appVersion: String = versionProps.getProperty("version", "0.0.0")
val appBuilt: String = versionProps.getProperty("built", "")
val (vMajor, vMinor, vPatch) = appVersion.split('.').map { it.toInt() }

// Chave de release: fora do repositório. Variáveis de ambiente ou ~/.config/RenanDrop-android/keystore.properties.
val releaseProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".config/RenanDrop-android/keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun signing(env: String, key: String): String? = System.getenv(env) ?: releaseProps.getProperty(key)
val releaseStore = signing("RENANDROP_KEYSTORE", "storeFile")

android {
    namespace = "dev.renandrop.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.renandrop.app"
        minSdk = 29
        targetSdk = 35
        versionCode = vMajor * 1_000_000 + vMinor * 1_000 + vPatch + 1
        versionName = appVersion
        buildConfigField("String", "BUILT_AT", "\"$appBuilt\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = signing("RENANDROP_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signing("RENANDROP_KEY_ALIAS", "keyAlias")
                keyPassword = signing("RENANDROP_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":protocol"))

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
}
