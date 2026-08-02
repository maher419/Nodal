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
        maven("https://dl.cloudsmith.io/public/libp2p/jvm-libp2p/maven/")
        maven("https://jitpack.io")
        maven("https://artifacts.consensys.net/public/maven/maven/")
    }
}

rootProject.name = "Nodal"
include(":core")
include(":bootstrap")
include(":android")
