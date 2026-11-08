pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
rootProject.name = "TikTokLiveReader"
include(":app")

// Piper is a composite build so its native CMake paths resolve relative to the
// Piper repository root (where third-party/ and its pinned submodules live).
includeBuild("piper-kotlin") {
    dependencySubstitution {
        substitute(module("dev.ihorshevchuk.piper:piper-engine")).using(project(":piper-engine"))
        substitute(module("dev.ihorshevchuk.piper:piper-utils")).using(project(":piper-utils"))
    }
}
