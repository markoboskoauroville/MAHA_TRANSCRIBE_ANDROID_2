import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One number, read from gradle.properties, turned into every other form of itself.
val appVersion = (project.findProperty("appVersion") as String? ?: "1").trim().toInt()

// The signing key is a repository secret restored by the workflow. Locally the file is absent and
// the release build falls back to unsigned, which is correct: a build made on a desk is not a
// build that may be delivered, and G1 of the delivery gate says so in as many words.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.mantra.mahatranscribe"
    compileSdk = 35

    defaultConfig {
        // PERMANENT. Android identifies an installed app by this string, so changing it makes
        // every future build a different app that cannot install over the old one. It is never
        // to be edited, not for a rename, not for a rewrite.
        applicationId = "com.mantra.mahatranscribe"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersion
        versionName = appVersion.toString()
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // G3: the machines already know things nobody has read. Fatal from the first build rather
    // than measured and deferred. If it ever cries wolf, delivery-gate.md 5.2 says narrow it in
    // the session that made the noise, never carry it.
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkReleaseBuilds = true
        htmlReport = false
        xmlReport = true
        // The launcher icon is a vector and there are no density buckets on purpose.
        disable += setOf("IconMissingDensityFolder", "UnusedResources")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // How the version number reaches the screen. versioning.md 3 wants it in three places
        // and the one that goes stale is always the one the person can see.
        buildConfig = true
    }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
}
