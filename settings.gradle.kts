pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // PREFER_SETTINGS: Flutter の include_flutter.groovy がプロジェクトレベルで
    // リポジトリを追加するため FAIL_ON_PROJECT_REPOS は使えない
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
    }
}

rootProject.name = "ProPaint"
include(":app")

// Flutter module 統合
apply(from = "propaint_flutter/.android/include_flutter.groovy")
