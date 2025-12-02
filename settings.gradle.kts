pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "Applikasi_Real"

include(":app", ":opencv")
project(":opencv").projectDir =
    file("C:/Users/Phasya/OneDrive/Desktop/opencv-4.12.0-android-sdk/OpenCV-android-sdk/sdk")

