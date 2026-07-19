import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
    `maven-publish`
    signing
}

// Read from gradle.properties, which is the single source of truth for the published coordinates.
// These are NOT restated here: the two used to disagree (0.4.0 there, 0.5.0 here), and a fact
// maintained in two places drifts the moment one of them is edited alone.
group = property("group") as String
version = property("version") as String

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
    // `-PnvTag=<tag>` runs ONLY the tests carrying that JUnit tag. This is what the non-vacuity
    // harness (scripts/non-vacuity.py) uses to select the single test that guards one protection.
    //
    // The harness must be able to prove a selector is LIVE — the Node SDK's equivalent harness
    // caught selectors that matched ZERO tests while the runner still exited 0, which reads as
    // "the mutation was not caught" when in truth nothing ever ran. So this task PRINTS the counts
    // it actually executed, on a machine-readable line, and refuses to be trusted otherwise.
    val nvTag = providers.gradleProperty("nvTag").orNull
    useJUnitPlatform {
        if (nvTag != null) includeTags(nvTag)
    }
    // A tag that matches nothing must not silently succeed.
    filter { isFailOnNoMatchingTests = false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }

    var ran = 0
    var failed = 0
    var skipped = 0
    afterTest(
        KotlinClosure2<TestDescriptor, TestResult, Unit>({ _, result ->
            ran++
            if (result.resultType == TestResult.ResultType.FAILURE) failed++
            if (result.resultType == TestResult.ResultType.SKIPPED) skipped++
        }),
    )
    doLast {
        logger.lifecycle("NVCOUNT tests=$ran failed=$failed skipped=$skipped")
    }
    // The count line must be emitted even when the run failed, since that is exactly the case the
    // harness is measuring.
    ignoreFailures = (nvTag != null)
    doLast {
        // A TAG THAT SELECTED NOTHING IS NOT A PASS.
        //
        // `isFailOnNoMatchingTests = false` (set above so the harness can inspect the counters
        // itself) means a tag matching zero tests exits 0 and printed `NVRESULT PASSED` — a
        // green build that executed nothing, which reads as "the mutation was not caught" when in
        // truth nothing ever ran. The harness has its own `ran == 0` gate, but a build that can
        // report success for a run that measured nothing is a hazard for anyone invoking gradle
        // directly, and the harness should not be the only thing standing between a typo'd tag and
        // a clean bill of health. Requirement 8.3.
        if (nvTag != null && ran == 0) {
            logger.lifecycle("NVRESULT NOTESTS")
            throw GradleException(
                "-PnvTag=$nvTag selected 0 tests. A selector that matches nothing cannot catch " +
                    "anything, and must never be reported as a pass.",
            )
        }
        if (nvTag != null && failed > 0) {
            logger.lifecycle("NVRESULT FAILED")
        } else if (nvTag != null) {
            logger.lifecycle("NVRESULT PASSED")
        }
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
            groupId = project.group.toString()
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
