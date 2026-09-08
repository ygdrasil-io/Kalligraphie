plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
            implementation(project(":kalligraphie:font:core"))
            implementation(project(":kalligraphie:font:sfnt"))
            api(project(":kalligraphie:unicode"))
            api(project(":kalligraphie:shaping"))
            api(project(":kalligraphie:layout"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val glyphMaterializationBenchmarkClass = "org.graphiks.kalligraphie.GlyphMaterializationBenchmarkTest"
val jvmTestTask = tasks.named<Test>("jvmTest")

jvmTestTask.configure {
    filter.excludeTestsMatching(glyphMaterializationBenchmarkClass)
}

tasks.register<Test>("glyphMaterializationMeasurement") {
    group = "verification"
    description = "Runs the opt-in glyph materialization measurement outside the functional test suite."
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = jvmTestTask.get().classpath
    filter.includeTestsMatching("$glyphMaterializationBenchmarkClass.runsEveryConfiguredMaterializationProfileOnlyWhenExplicitlyEnabled")
}
