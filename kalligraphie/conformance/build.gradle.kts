plugins {
    id("ygdrasil.conventions.kmp-library")
}

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
