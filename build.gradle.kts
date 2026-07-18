import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
    `maven-publish`
    signing
}

group = "dev.paylod"
version = "0.2.0"

repositories {
    mavenCentral()
}

// paylod-jvm pulls in NO THIRD-PARTY runtime dependencies beyond the Kotlin standard library (which
// the published POM depends on). The wire shapes are small and flat, so a tiny internal JSON
// reader/writer (see internal/Json.kt) does the job without a serialization runtime or a compiler
// plugin. HTTP rides on the JDK's own java.net.http.HttpClient. That thin footprint matters for a
// library that sits in a payments path.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Emit JVM default methods for interface defaults so Java callers see clean signatures.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// ── Maven Central coordinates (prepared, NOT published) ─────────────────────────────────────
// Coordinates: dev.paylod:paylod:<version>. The publication is fully described here so that a
// future `publishToSonatype` (or the central-portal plugin) works without surgery, but no
// publishing repository is wired up and nothing is signed by default — running `publish` locally
// only writes to the in-project `build/repo` staging dir.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "dev.paylod"
            artifactId = "paylod"
            version = project.version.toString()

            pom {
                name.set("paylod")
                description.set(
                    "Official JVM (Kotlin/Java) client for the paylod API — hosted M-Pesa " +
                        "(Safaricom Daraja) STK Push, status polling, signed webhooks and offline " +
                        "error decoding, with no third-party runtime dependencies beyond the Kotlin " +
                        "standard library.",
                )
                url.set("https://paylod.dev/docs/sdk")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("paylod")
                        name.set("paylod / Moses Mrima")
                    }
                }
                scm {
                    url.set("https://github.com/mosesmrima/paylod-jvm")
                    connection.set("scm:git:https://github.com/mosesmrima/paylod-jvm.git")
                }
            }
        }
    }
    repositories {
        // Local staging only. Deliberately NOT Sonatype/Central — publishing is out of scope.
        maven {
            name = "localStaging"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}

// Sign only when signing keys are configured, so a normal build never asks for a GPG key.
signing {
    isRequired = false
    sign(publishing.publications["maven"])
}
