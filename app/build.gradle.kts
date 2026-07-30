plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.ksp) // 启用 KSP 插件
}

val repositoryControlAssets = layout.buildDirectory.dir("generated/repositoryControlAssets")
val generateRepositoryControlAssets by tasks.registering(org.gradle.api.tasks.Sync::class) {
    from(rootProject.file("repository-format/v1/schemas")) {
        include("*.schema.json")
        into("repository-control/ebbinghaus")
    }
    from(rootProject.file("scripts/review-sync.sh")) {
        into("repository-control/scripts")
    }
    into(repositoryControlAssets)
}

android {
    namespace = "com.ebbinghaus.review"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ebbinghaus.review"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "com.ebbinghaus.review.TestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    sourceSets.getByName("main").assets.srcDir(repositoryControlAssets)
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.all {
            it.systemProperty(
                "robolectric.dependency.repo.url",
                "https://repo.maven.apache.org/maven2"
            )
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateRepositoryControlAssets)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material)

    // === 核心业务依赖 ===
    // Room 数据库 (≈ MyBatis + SQLite)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler) // 注解处理器
    kspTest(libs.room.compiler)

    // WorkManager (≈ Quartz/Spring Scheduled)
    implementation(libs.work.runtime.ktx)

    // Navigation (≈ Controller 路由跳转)
    implementation(libs.navigation.compose)

    // Coil (图片加载)
    implementation(libs.coil.compose)

    // Gson
    implementation(libs.gson)
    implementation(libs.snakeyaml)
    implementation(libs.commonmark)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
