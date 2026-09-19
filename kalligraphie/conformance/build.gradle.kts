plugins {
    id("ygdrasil.conventions.kmp-library")
}

kotlin {
    explicitApi()
    android {
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            managedDevices {
                localDevices {
                    create("mediumPhone") {
                        device = "Medium Phone"
                        apiLevel = 35
                        systemImageSource = "aosp"
                    }
                }
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
        }
        commonTest.dependencies {
            implementation(project(":kalligraphie"))
            implementation(kotlin("test"))
        }
        val androidDeviceTest by getting {
            dependencies {
                implementation(project(":kalligraphie"))
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.androidx.test.runner)
                implementation(kotlin("test"))
            }
        }
    }
}
