plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// O painel web (a pasta acima de android/) é copiado para dentro do APK a cada build.
// Assim o projeto web continua sendo o mesmo: nada dele é reescrito aqui.
val webRoot = rootProject.projectDir.parentFile
val webAssetsOut = layout.buildDirectory.dir("generated/webassets")

val copyWebApp by tasks.registering(Copy::class) {
    from(webRoot) {
        include("*.html", "*.js", "*.css", "*.svg", "*.png", "*.webp", "*.json", "*.webmanifest")
        exclude("bundle.mjs", "server.js", "tests-*.mjs", "package.json", "package-lock.json")
    }
    into(webAssetsOut.map { it.dir("www") })
}

android {
    namespace = "br.com.almadocarro"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.almadocarro"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "1.7.7"
    }

    signingConfigs {
        create("alma") {
            storeFile = file("alma.keystore")
            storePassword = "almadocarro"
            keyAlias = "alma"
            keyPassword = "almadocarro"
        }
    }

    // Uma versão do app por carro. Mesmo painel; muda só o perfil de fábrica (assets/native/profile.js).
    flavorDimensions += "carro"
    productFlavors {
        create("renegade") {
            dimension = "carro"
            resValue("string", "app_name", "Alma do Carro")
        }
        create("civic") {
            dimension = "carro"
            applicationIdSuffix = ".civic"
            resValue("string", "app_name", "Alma do Civic")
        }
        create("ecosport") {
            dimension = "carro"
            applicationIdSuffix = ".ecosport"
            resValue("string", "app_name", "Alma da EcoSport")
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("alma")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("alma")
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(webAssetsOut)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

tasks.named("preBuild") { dependsOn(copyWebApp) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-common:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata:2.8.7")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
}
