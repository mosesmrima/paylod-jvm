import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

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
// HOW THE CHECK VERIFIES ITSELF WITHOUT THE MONOREPO. It used to locate the monorepo as a sibling
// directory and, when that directory was absent, log a warning and return. In CI the directory is
// never present -- the monorepo is private -- so the task warned and returned on every single run.
// It verified nothing, and the build was green anyway. A check that cannot distinguish "I did not
// look" from "I looked and it is fine" is not a check.
//
// The fix is `daraja-catalog.sha256`, a committed file recording the SHA-256 of the canonical
// catalog and of the vendored copy. The task hashes the vendored file and compares. That works in
// any checkout, needs no sibling repo and no credential. The sibling comparison is KEPT as an
// additional, stronger check for whoever does have the monorepo beside them.
//
// A cross-repo token was rejected as the alternative fix: it would mint a long-lived credential
// with read access to the private monorepo and store it in four SDK repos, three of them public --
// widening blast radius to solve what is only a file-availability problem.
//
// The monorepo is found at ../mpesa by default; override with MPESA_REPO=/path.
val darajaCatalogCopy = layout.projectDirectory.file("src/main/resources/dev/paylod/daraja-error-codes.json")
val darajaCatalogChecksum = layout.projectDirectory.file("daraja-catalog.sha256")

fun canonicalDarajaCatalog(): File {
    val root = System.getenv("MPESA_REPO") ?: rootDir.resolveSibling("mpesa").path
    return File(root, "supabase/functions/_shared/daraja/daraja-error-codes.json")
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { b -> "%02x".format(b) }

/** One pinned row: the canonical digest, the vendored digest, and the two paths they describe. */
data class DarajaChecksumRow(
    val canonicalSha: String,
    val vendoredSha: String,
    val canonicalPath: String,
    val vendoredPath: String,
)

/**
 * Parse `daraja-catalog.sha256`, FAILING CLOSED.
 *
 * Missing, unreadable, empty, malformed, or zero data rows all throw. That is the entire point:
 * "I could not check" must never be a silent success. Every rejection path here was previously a
 * path that returned quietly.
 */
fun readDarajaChecksums(file: File): List<DarajaChecksumRow> {
    if (!file.isFile) {
        throw GradleException(
            "the pinned Daraja catalog checksum file is MISSING: $file\n" +
                "Without it the vendored catalog cannot be verified, and an unverifiable catalog " +
                "is not an acceptable build input.\nRegenerate it: ./gradlew syncDarajaCatalog " +
                "(with the paylod monorepo available), or from the monorepo itself: " +
                "node scripts/sync-daraja-catalog.mjs",
        )
    }
    val text = try {
        file.readText()
    } catch (e: Exception) {
        throw GradleException("could not read the pinned Daraja catalog checksum file $file: $e")
    }
    val rows = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val f = line.split(Regex("\\s+"))
            if (f.size != 4) {
                throw GradleException(
                    "malformed line in $file: expected 4 whitespace-separated fields " +
                        "(canonical-sha256, vendored-sha256, canonical-path, vendored-path), " +
                        "got ${f.size}: $line",
                )
            }
            listOf(f[0], f[1]).forEach { d ->
                if (!Regex("^[0-9a-f]{64}$").matches(d)) {
                    throw GradleException("malformed SHA-256 digest in $file: '$d' (line: $line)")
                }
            }
            DarajaChecksumRow(f[0], f[1], f[2], f[3])
        }
        .toList()
    if (rows.isEmpty()) {
        throw GradleException(
            "$file contains no checksum rows. An empty pin verifies nothing, which is the failure " +
                "mode this file exists to remove.",
        )
    }
    return rows
}

/** The exact bytes the monorepo generator writes, so both producers agree byte for byte. */
fun darajaChecksumBody(rows: List<DarajaChecksumRow>): String =
    listOf(
        "# Daraja catalog provenance — PINNED CHECKSUMS. GENERATED FILE, DO NOT HAND-EDIT.",
        "#",
        "# Regenerate from the paylod monorepo:  node scripts/sync-daraja-catalog.mjs",
        "#",
        "# This repo's drift guard verifies the vendored catalog against these digests, with no access",
        "# to the canonical file. That is deliberate. The canonical catalog lives in a PRIVATE monorepo",
        "# which CI cannot check out, and the guard used to compare against a sibling directory that",
        "# does not exist in CI — so it skipped, silently, and the pipeline stayed green while verifying",
        "# nothing. A check that cannot distinguish \"I did not look\" from \"I looked and it is fine\" is",
        "# not a check.",
        "#",
        "# A cross-repo token was rejected as the fix: it would mint a long-lived credential with read",
        "# access to the private monorepo and store it in four SDK repos, three of them public. A",
        "# committed digest needs no credential, no network, and works in any checkout.",
        "#",
        "# Fields: <canonical sha256>  <vendored sha256>  <canonical path>  <vendored path>",
    ).joinToString("\n") + "\n" +
        rows.joinToString("\n") {
            "${it.canonicalSha}  ${it.vendoredSha}  ${it.canonicalPath}  ${it.vendoredPath}"
        } + "\n"

val DARAJA_CANONICAL_RELPATH = "supabase/functions/_shared/daraja/daraja-error-codes.json"

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
        val canonicalBytes = from.readBytes()
        if (to.exists() && to.readBytes().contentEquals(canonicalBytes)) {
            logger.lifecycle("[ok] up to date  ${to.relativeTo(projectDir)}")
        } else {
            from.copyTo(to, overwrite = true)
            logger.lifecycle("[sync] wrote     ${to.relativeTo(projectDir)}")
        }
        // Recorded from the CANONICAL bytes, never from the copy on disk. Hashing the copy would
        // make the record a tautology; hashing the source makes it provenance the copy must meet.
        val digest = sha256Hex(canonicalBytes)
        val sum = darajaCatalogChecksum.asFile
        val body = darajaChecksumBody(
            listOf(
                DarajaChecksumRow(
                    digest,
                    digest,
                    DARAJA_CANONICAL_RELPATH,
                    to.relativeTo(projectDir).invariantSeparatorsPath,
                ),
            ),
        )
        if (sum.isFile && sum.readText() == body) {
            logger.lifecycle("[ok] up to date  ${sum.relativeTo(projectDir)}")
        } else {
            sum.writeText(body)
            logger.lifecycle("[sync] wrote     ${sum.relativeTo(projectDir)}")
        }
    }
}

tasks.register("checkDarajaCatalog") {
    group = "verification"
    description = "Fail if the vendored Daraja error catalog has drifted from its pinned checksum."
    doLast {
        // LAYER 1 — always runs, in every checkout, with no monorepo and no credential.
        // Throws if the pin is missing, unreadable, malformed, or empty.
        val rows = readDarajaChecksums(darajaCatalogChecksum.asFile)
        for (row in rows) {
            val vendored = File(projectDir, row.vendoredPath)
            if (!vendored.isFile) {
                throw GradleException(
                    "the vendored Daraja catalog named by daraja-catalog.sha256 is MISSING: " +
                        "$vendored\nRun: ./gradlew syncDarajaCatalog",
                )
            }
            val actual = sha256Hex(vendored.readBytes())
            if (actual != row.vendoredSha) {
                throw GradleException(
                    "DRIFT: ${row.vendoredPath} does not match its pinned checksum.\n" +
                        "  pinned:   ${row.vendoredSha}\n" +
                        "  on disk:  $actual\n" +
                        "The vendored catalog is GENERATED and must never be hand-edited. If the " +
                        "canonical table really changed, regenerate the copy AND the pin:\n" +
                        "  ./gradlew syncDarajaCatalog",
                )
            }
        }
        logger.lifecycle(
            "[ok] Daraja catalog matches its pinned checksum (${rows.size} file(s) verified).",
        )

        // LAYER 2 — additional, strictly stronger, and only possible with the monorepo beside us.
        // Its ABSENCE no longer makes this task vacuous: layer 1 above already ran and passed.
        val from = canonicalDarajaCatalog()
        if (!from.isFile) {
            logger.lifecycle(
                "[info] canonical monorepo not present at $from, so the pinned checksum above is " +
                    "what was verified. This is the normal CI condition and is NOT a skip.",
            )
            return@doLast
        }
        val canonicalBytes = from.readBytes()
        val canonicalSha = sha256Hex(canonicalBytes)
        val row = rows.single { it.canonicalPath == DARAJA_CANONICAL_RELPATH }
        if (canonicalSha != row.canonicalSha) {
            throw GradleException(
                "STALE PIN: the canonical Daraja catalog at $from has moved on from what " +
                    "daraja-catalog.sha256 records.\n" +
                    "  pinned canonical:  ${row.canonicalSha}\n" +
                    "  actual canonical:  $canonicalSha\n" +
                    "Run: ./gradlew syncDarajaCatalog",
            )
        }
        if (!darajaCatalogCopy.asFile.readBytes().contentEquals(canonicalBytes)) {
            throw GradleException(
                "DRIFT: ${darajaCatalogCopy.asFile.relativeTo(projectDir)} differs from the " +
                    "canonical Daraja catalog at $from.\nRun: ./gradlew syncDarajaCatalog",
            )
        }
        logger.lifecycle("[ok] Daraja catalog also matches canonical at $from.")
    }
}

tasks.named("check") { dependsOn("checkDarajaCatalog") }
