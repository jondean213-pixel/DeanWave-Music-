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
        // FrostWire hosts jlibtorrent in its own repo, not Maven Central.
        // Content filter keeps this repo from touching anything else.
        maven {
            url = uri("https://dl.frostwire.com/maven")
            content {
                includeGroup("com.frostwire")
            }
        }
    }
}

rootProject.name = "DeanWave-Music"
include(":app", ":dsp")

