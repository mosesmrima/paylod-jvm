import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
    `maven-publish`
    signing
    // Sonatype's Central Portal is NOT the old OSSRH `nexus-staging` API: it takes a zipped
    // bundle uploaded to the Publisher API, not a plain Maven repository PUT. This plugin speaks
    // that protocol; hand-rolling it with `maven { url = ... }` cannot work.
    //
    // Pinned to 0.34.0, NOT the newest 0.37.0, and the two bounds that pick it are both hard:
    //   - 0.36.0+ requires Kotlin Gradle Plugin 2.2.0; this project builds on Kotlin 2.0.21.
    //   - 0.35.0  requires Gradle 8.13; the wrapper here is 8.10.2.
    // 0.34.0 is the newest release under both ceilings (Kotlin 1.9.20+, Gradle 8.5+), and it is
    // already past the point where the plugin dropped OSSRH and made the Central Portal its only
    // host — so pinning here gives up nothing that matters. Moving this version forward means
    // moving the Gradle wrapper, and then Kotlin, first.
    id("com.vanniktech.maven.publish") version "0.34.0"
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

// The sources jar and javadoc jar are produced by the publishing plugin (see `configure(KotlinJvm)`
// below) rather than by `java { withSourcesJar(); withJavadocJar() }`. Central rejects a release
// missing either, and letting one owner build them keeps them from being added twice.

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

// ── Signing: mandatory when a key is configured, absent when one is not ──────────────────────
//
// Central rejects unsigned artifacts, so a RELEASE must be signed or it must fail. But a
// developer running `./gradlew build` has no GPG key and must never be prompted for one. The old
// `signing { isRequired = false }` bought the second property by giving up the first: a release
// with a mis-set secret would have shipped unsigned artifacts and only failed later, at the
// Portal, with the tag already cut.
//
// So signing is wired CONDITIONALLY instead. `signAllPublications()` (which makes signing
// mandatory) is called only when a key is actually configured, by any of the three mechanisms
// Gradle and this plugin understand:
//
//   signingInMemoryKey       ASCII-armoured private key — what CI passes, as
//                            ORG_GRADLE_PROJECT_signingInMemoryKey
//   signing.keyId            a secring.gpg keyring
//   signing.gnupg.keyName    the local gpg agent, for a developer dry run:
//                            ./gradlew publishAllPublicationsToLocalStagingRepository \
//                              -Psigning.gnupg.keyName=<key id>
//
// With none of them set the signing plugin is never asked to sign anything, so there is nothing
// to prompt for. With any of them set, an unsignable artifact fails the build.
val inMemorySigningKey = providers.gradleProperty("signingInMemoryKey")
val keyringSigningKeyId = providers.gradleProperty("signing.keyId")
val gnupgSigningKeyName = providers.gradleProperty("signing.gnupg.keyName")
val signingKeyConfigured =
    inMemorySigningKey.isPresent || keyringSigningKeyId.isPresent || gnupgSigningKeyName.isPresent

if (gnupgSigningKeyName.isPresent) {
    // Delegate to the gpg binary/agent rather than to a keyring file, so a local dry run can use
    // the key already in the developer's keyring. No passphrase is read or stored by the build.
    signing { useGpgCmd() }
}

// ── Maven Central publishing ──────────────────────────────────────────────────────────────────
// Coordinates come from gradle.properties via project.group / project.version — the single source
// of truth — and are NOT restated here. The artifact id is the only piece this file owns.
mavenPublishing {
    // Sonatype Central Portal. `publishToMavenCentral()` uploads a zipped bundle to the Publisher
    // API; it deliberately does NOT auto-release, so a deployment lands in the Portal as a
    // reviewable draft and a human presses the button. A Maven Central release is permanent.
    publishToMavenCentral()

    if (signingKeyConfigured) {
        signAllPublications()
    }

    // Central rejects a release missing a sources jar or a javadoc jar.
    configure(KotlinJvm(javadocJar = JavadocJar.Javadoc()))

    coordinates(project.group.toString(), "paylod", project.version.toString())

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

publishing {
    repositories {
        // Kept alongside Central, not replaced by it. The release workflow stages here first and
        // uploads the result as a workflow artifact, so the exact bytes headed for Central can be
        // inspected before anyone publishes them. This is also the local dry-run target.
        maven {
            name = "localStaging"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}

// ── The Daraja catalog is VENDORED, not authored here ─────────────────────────────────────────
//
// `src/main/resources/dev/paylod/daraja-error-codes.json` is a physical COPY of the canonical table
// in the paylod monorepo. It has to be a copy: this is a separate git repo and a separate publish
// artifact, so it cannot import across the repo boundary.
//
// A hand-maintained copy is exactly how four SDKs ended up serving four different sentences for the
// same M-Pesa result code. paylod-sdk (Node) has carried a sync script for this since that bug;
// these tasks are its Gradle equivalent, and `check` depends on the verifying one so drift fails a
// build rather than waiting for a human to notice.
//
//   ./gradlew syncDarajaCatalog    # write the copy from canonical
//   ./gradlew checkDarajaCatalog   # fail if the copy has drifted
//
// The monorepo is found at ../mpesa by default; override with MPESA_REPO=/path.
val darajaCatalogCopy = layout.projectDirectory.file("src/main/resources/dev/paylod/daraja-error-codes.json")

fun canonicalDarajaCatalog(): File {
    val root = System.getenv("MPESA_REPO") ?: rootDir.resolveSibling("mpesa").path
    return File(root, "supabase/functions/_shared/daraja/daraja-error-codes.json")
}

tasks.register("syncDarajaCatalog") {
    group = "verification"
    description = "Copy the canonical Daraja error catalog from the paylod monorepo into this SDK."
    doLast {
        val from = canonicalDarajaCatalog()
        if (!from.isFile) {
            throw GradleException(
                "canonical Daraja catalog not found at $from (set MPESA_REPO=/path/to/mpesa)",
            )
        }
        val to = darajaCatalogCopy.asFile
        if (to.exists() && to.readBytes().contentEquals(from.readBytes())) {
            logger.lifecycle("[ok] up to date  ${to.relativeTo(projectDir)}")
        } else {
            from.copyTo(to, overwrite = true)
            logger.lifecycle("[sync] wrote     ${to.relativeTo(projectDir)}")
        }
    }
}

tasks.register("checkDarajaCatalog") {
    group = "verification"
    description = "Fail if the vendored Daraja error catalog has drifted from the canonical copy."
    doLast {
        val from = canonicalDarajaCatalog()
        // A CI job or a consumer without the monorepo cannot verify. The copy is committed, so that
        // is fine — but do NOT pretend we checked it.
        if (!from.isFile) {
            logger.warn(
                "[warn] skipping Daraja catalog drift check: monorepo not found at $from " +
                    "(set MPESA_REPO=/path/to/mpesa)",
            )
            return@doLast
        }
        val to = darajaCatalogCopy.asFile
        if (!to.exists() || !to.readBytes().contentEquals(from.readBytes())) {
            throw GradleException(
                "DRIFT: ${to.relativeTo(projectDir)} differs from the canonical Daraja catalog at " +
                    "$from.\nRun: ./gradlew syncDarajaCatalog",
            )
        }
        logger.lifecycle("[ok] Daraja catalog matches canonical.")
    }
}

tasks.named("check") { dependsOn("checkDarajaCatalog") }
