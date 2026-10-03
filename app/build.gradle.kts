plugins {
    id("com.android.application")
}

android {
    namespace = "net.fornwall.androidneovim"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "net.fornwall.androidneovim"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main") {
        jniLibs.directories += layout.buildDirectory.dir("generated/neovim/jniLibs").get().asFile.path
        assets.directories += layout.buildDirectory.dir("generated/neovim/assets").get().asFile.path
    }
    lint { disable += "UseKtx" }
}

val buildNeovim = tasks.register<Exec>("buildNeovim") {
    group = "build"
    description = "Builds the pinned Neovim core and runtime for Android."
    workingDir(rootProject.projectDir)
    commandLine("bash", "native/build.sh")
    inputs.files(rootProject.fileTree("native") {
        exclude("work/**")
    })
    outputs.dir(layout.buildDirectory.dir("generated/neovim"))
}

tasks.named("preBuild") { dependsOn(buildNeovim) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
