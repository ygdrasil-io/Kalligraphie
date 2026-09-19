pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "KffiSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            content {
                includeModule("org.graphiks", "kffi-coretext")
                includeModule("org.graphiks", "kffi-coretext-jvm")
                includeModule("org.graphiks", "kffi")
                includeModule("org.graphiks", "kffi-jvm")
            }
        }
    }
}

rootProject.name = "Kalligraphie"
include(":docs")
include(":kalligraphie")
include(":kalligraphie:api")
include(":kalligraphie:unicode")
include(":kalligraphie:shaping")
include(":kalligraphie:layout")
include(":kalligraphie:conformance")
include(":kalligraphie:font:core")
include(":kalligraphie:font:sfnt")
include(":kalligraphie:font:scaler")
include(":kalligraphie:font:glyph")
include(":kalligraphie:platform:apple")
include(":kalligraphie:raster-cpu")
