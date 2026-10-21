plugins {
    id("com.android.application")
}

android {
    namespace = "com.minervim"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.minervim"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Neovim cannot restart in-process, so each device test gets a fresh process and app data.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
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
    testOptions { execution = "ANDROIDX_TEST_ORCHESTRATOR" }
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
    androidTestUtil("androidx.test:orchestrator:1.6.1")
}
