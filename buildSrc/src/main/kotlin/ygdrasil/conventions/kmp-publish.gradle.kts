package ygdrasil.conventions

import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.w3c.dom.Element

plugins {
    id("com.vanniktech.maven.publish")
}

group = "org.graphiks"
version = (project.findProperty("releaseVersion") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: "1.0.0-SNAPSHOT"

val isPublishing = project.findProperty("signingInMemoryKey")?.toString()?.isNotBlank() == true
    || project.findProperty("signing.keyId")?.toString()?.isNotBlank() == true

mavenPublishing {
    if (isPublishing) {
        publishToMavenCentral()
        signAllPublications()
    }
    coordinates(group.toString(), project.name, version.toString())

    pom {
        name.set(project.name)
        description.set("Kalligraphie portable font pipeline")
        url.set("https://github.com/Graphiks-org/Kalligraphie")

        licenses {
            license {
                name.set("The MIT License")
                url.set("https://opensource.org/license/mit/")
            }
        }

        developers {
            developer {
                id.set("ygdrasil-io")
                name.set("Ygdrasil team")
                email.set("contact@ygdrasil.com")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/Graphiks-org/Kalligraphie.git")
            developerConnection.set("scm:git:ssh://github.com/Graphiks-org/Kalligraphie.git")
            url.set("https://github.com/Graphiks-org/Kalligraphie")
        }
    }
}

// The POM Gradle generates for a Kotlin multiplatform module maps every commonMain dependency to
// runtime scope, because the POM is derived from the runtime usage of the root variant while the
// compile/runtime split lives only in the Gradle module metadata. Maven, sbt and repositories or
// proxies that serve the POM alone resolve none of the api surface at compile time, so every
// dependency the module declares as api — and the Kotlin stdlib its signatures are written
// against — must also carry compile scope in the POM.
val commonMainApiModules = provider {
    val apiDependencies = configurations.findByName("commonMainApi")?.dependencies ?: return@provider emptySet<String>()
    buildSet {
        for (dependency in apiDependencies) {
            when (dependency) {
                is ProjectDependency -> add("${dependency.group}:${dependency.name}")
                is ExternalModuleDependency -> add("${dependency.group}:${dependency.name}")
                else -> {}
            }
        }
        add("org.jetbrains.kotlin:kotlin-stdlib")
    }
}

plugins.withId("maven-publish") {
    extensions.configure<PublishingExtension>("publishing") {
        publications.withType<MavenPublication>().configureEach {
            pom.withXml {
                val apiModules = commonMainApiModules.get()
                val dependencyNodes = asElement().getElementsByTagName("dependency")
                var index = 0
                while (index < dependencyNodes.length) {
                    val dependency = dependencyNodes.item(index) as Element
                    val group = dependency.getElementsByTagName("groupId").item(0)?.textContent
                    val artifact = dependency.getElementsByTagName("artifactId").item(0)?.textContent
                    val scope = dependency.getElementsByTagName("scope").item(0) as? Element
                    if (scope != null && scope.textContent == "runtime" && group != null && artifact != null && "$group:$artifact" in apiModules) {
                        scope.textContent = "compile"
                    }
                    index++
                }
            }
        }
    }
}
