package dev.paylod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

private const val CANONICAL_RELPATH = "supabase/functions/_shared/daraja/daraja-error-codes.json"

/**
 * The vendored Daraja catalog must equal the canonical one, and the catalog's own key must be
 * unambiguous.
 *
 * WHY: `src/main/resources/dev/paylod/daraja-error-codes.json` is a COPY of
 * `supabase/functions/_shared/daraja/daraja-error-codes.json` in the paylod monorepo. Four SDKs
 * carry such a copy, and three of them had drifted into three DIFFERENT customer-facing sentences
 * for the same M-Pesa result code. Nothing failed when that happened, which is the whole problem:
 * the copies were only ever checked by a human reading four files side by side.
 *
 * The `./gradlew checkDarajaCatalog` task covers the build path; this covers the test path, so a
 * plain `./gradlew test` catches drift too.
 *
 * WHY THIS NO LONGER LOOKS FOR A SIBLING DIRECTORY FIRST. It used to `assumeTrue(canonical.isFile)`
 * and skip when the monorepo was absent. The monorepo is PRIVATE and is never checked out in CI, so
 * that assumption failed on every CI run and the guard skipped every time, while the pipeline
 * reported green. A check that cannot distinguish "I did not look" from "I looked and it is fine"
 * is not a check.
 *
 * The verification is now anchored on `daraja-catalog.sha256`, a committed pin recording the
 * SHA-256 of the canonical table and of the vendored copy. Hashing a local file needs no sibling
 * repo and no credential, so the guard runs everywhere. The canonical comparison is KEPT as an
 * additional, stronger layer for anyone who does have the monorepo beside them.
 *
 * A cross-repo token was rejected as the alternative: a long-lived credential with read access to
 * the private monorepo, stored in four SDK repos, three of them public, to solve what is only a
 * file-availability problem.
 */
class DarajaCatalogDriftTest {

    private data class ChecksumRow(
        val canonicalSha: String,
        val vendoredSha: String,
        val canonicalPath: String,
        val vendoredPath: String,
    )

    private fun projectDir(): File = File(System.getProperty("user.dir"))

    private fun canonicalCatalog(): File {
        val root = System.getenv("MPESA_REPO")
            ?: projectDir().parentFile.resolve("mpesa").path
        return File(root, CANONICAL_RELPATH)
    }

    private fun checksumFile(): File = File(projectDir(), "daraja-catalog.sha256")

    private fun vendoredCatalog(): File =
        File(projectDir(), "src/main/resources/dev/paylod/daraja-error-codes.json")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b) }

    /**
     * Parse the pin, FAILING CLOSED. Missing, unreadable, empty, malformed, or zero rows all throw.
     * Removing "I could not check" as a silent-success outcome is the entire point of this file.
     */
    private fun readChecksums(file: File): List<ChecksumRow> {
        if (!file.isFile) {
            throw AssertionError(
                "the pinned Daraja catalog checksum file is MISSING: $file. Without it the " +
                    "vendored catalog cannot be verified, and an unverifiable catalog must fail, " +
                    "not pass. Regenerate: ./gradlew syncDarajaCatalog",
            )
        }
        val rows = file.readText()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val f = line.split(Regex("\\s+"))
                if (f.size != 4) {
                    throw AssertionError(
                        "malformed line in $file: expected 4 whitespace-separated fields, got " +
                            "${f.size}: $line",
                    )
                }
                for (d in listOf(f[0], f[1])) {
                    if (!Regex("^[0-9a-f]{64}$").matches(d)) {
                        throw AssertionError("malformed SHA-256 digest in $file: '$d' (line: $line)")
                    }
                }
                ChecksumRow(f[0], f[1], f[2], f[3])
            }
            .toList()
        if (rows.isEmpty()) {
            throw AssertionError("$file contains no checksum rows. An empty pin verifies nothing.")
        }
        return rows
    }

    /**
     * LAYER 1. Runs in every checkout, including CI, with no monorepo and no credential.
     */
    @Test
    @Tag("nv-catalog-drift")
    fun `the vendored Daraja catalog matches its pinned checksum`() {
        val rows = readChecksums(checksumFile())
        for (row in rows) {
            val vendored = File(projectDir(), row.vendoredPath)
            assertTrue(
                vendored.isFile,
                "the vendored catalog named by daraja-catalog.sha256 is missing: $vendored",
            )
            assertEquals(
                row.vendoredSha,
                sha256(vendored.readBytes()),
                "DRIFT: ${row.vendoredPath} does not match its pinned SHA-256. The vendored " +
                    "catalog is GENERATED and must never be hand-edited. A merchant on this SDK " +
                    "would read different words than a merchant on another SDK for the same " +
                    "M-Pesa result code. Run: ./gradlew syncDarajaCatalog",
            )
        }
    }

    /**
     * LAYER 2. Additional and strictly stronger, but only possible when the monorepo is checked out
     * beside this repo. Its absence does NOT make the guard vacuous: the pinned check above already
     * ran unconditionally. So this one asserts the pin is not stale, rather than being the only
     * thing standing between a drifted copy and a green build.
     */
    @Test
    @Tag("nv-catalog-drift")
    fun `when the monorepo is present the pin and the copy both match canonical`() {
        val canonical = canonicalCatalog()
        if (!canonical.isFile) {
            // Deliberately a PASS, not a skip, and it is honest about what it means: the pinned
            // check in the test above is what verified the catalog on this run.
            println(
                "[info] canonical monorepo not present at $canonical, so the pinned checksum is " +
                    "what verified the catalog. This is the normal CI condition and is NOT a skip.",
            )
            return
        }
        val canonicalBytes = canonical.readBytes()
        val row = readChecksums(checksumFile()).single { it.canonicalPath == CANONICAL_RELPATH }
        assertEquals(
            row.canonicalSha,
            sha256(canonicalBytes),
            "STALE PIN: the canonical catalog at $canonical has moved on from what " +
                "daraja-catalog.sha256 records. Run: ./gradlew syncDarajaCatalog",
        )
        assertEquals(
            String(canonicalBytes),
            vendoredCatalog().readText(),
            "DRIFT: the vendored Daraja catalog differs from canonical at $canonical. " +
                "Run: ./gradlew syncDarajaCatalog",
        )
    }

    /**
     * NON-VACUITY FOR THE GUARD ITSELF. The two tests above are only worth anything if they can
     * actually go red, and the specific failure this whole change exists to remove is the guard
     * quietly succeeding when it had nothing to check. So: corrupt the pin, corrupt the copy, and
     * require the verification to REJECT each one. Everything happens in a temp directory, so a
     * failure here cannot leave residue in the working tree.
     */
    @Test
    @Tag("nv-catalog-drift-failclosed")
    fun `the guard fails closed on a missing, malformed, or unsatisfied pin`() {
        val real = checksumFile()
        val tmp = createTempDirectory("daraja-pin").toFile()
        try {
            // Missing pin must throw, not pass.
            assertThrows(AssertionError::class.java) {
                readChecksums(File(tmp, "daraja-catalog.sha256"))
            }
            // A pin with only comments has zero rows and verifies nothing.
            val commentsOnly = File(tmp, "comments.sha256")
            commentsOnly.writeText("# nothing here\n#\n")
            assertThrows(AssertionError::class.java) { readChecksums(commentsOnly) }
            // Wrong field count.
            val short = File(tmp, "short.sha256")
            short.writeText("${"a".repeat(64)}  ${"a".repeat(64)}  only-three-fields\n")
            assertThrows(AssertionError::class.java) { readChecksums(short) }
            // A digest that is not 64 hex characters.
            val badHex = File(tmp, "badhex.sha256")
            badHex.writeText("notadigest  ${"a".repeat(64)}  canonical/path  vendored/path\n")
            assertThrows(AssertionError::class.java) { readChecksums(badHex) }
            // Truncated file: real header, no rows.
            val truncated = File(tmp, "truncated.sha256")
            truncated.writeText(real.readText().lineSequence().filter { it.startsWith("#") }
                .joinToString("\n"))
            assertThrows(AssertionError::class.java) { readChecksums(truncated) }

            // And a well-formed pin that the bytes do NOT satisfy must be detected as drift.
            val rows = readChecksums(real)
            val vendoredBytes = vendoredCatalog().readBytes()
            val mutated = String(vendoredBytes).replace("\"code\"", "\"CODE\"").toByteArray()
            assertNotEquals(
                rows.first().vendoredSha,
                sha256(mutated),
                "a mutated catalog must not hash to the pinned digest",
            )
        } finally {
            tmp.deleteRecursively()
        }
        assertTrue(real.isFile, "the real pin must still be in place after this test")
    }

    /**
     * The catalog admits the SAME `code` under different families, and the two rows can disagree on
     * everything that matters:
     *
     *   2001         stk_result     retryable=true   "That M-Pesa PIN was incorrect..."
     *   2001         b2c_c2b_result retryable=false  "We hit a setup error on our side..."
     *   500.001.1001 stk_result     category=pending "Check your phone and enter your M-Pesa PIN..."
     *   500.001.1001 api_error      category=mpesa_system
     *
     * So `code` alone is NOT a key, and a lookup that treats it as one silently answers a different
     * question than the caller asked — including "is this retryable?", where the two 2001 rows give
     * opposite answers. The chosen policy is that (code, family) is THE key everywhere; duplicates
     * of the PAIR are forbidden. Deduplicating to bare `code` was rejected: the colliding rows are
     * genuinely different facts, and collapsing them would have to discard a true one.
     */
    @Test
    @Tag("nv-catalog-key")
    fun `code and family together are a unique key`() {
        val entries = DarajaCatalog.allEntries
        val pairs = entries.map { it.code to it.family }
        val duplicatePairs = pairs.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertTrue(
            duplicatePairs.isEmpty(),
            "(code, family) is the catalog key but these pairs appear more than once: $duplicatePairs",
        )
        assertEquals(entries.size, pairs.toSet().size, "(code, family) pairs are not unique")

        // Non-vacuity: the pair key is only load-bearing BECAUSE bare `code` collides. If these
        // collisions ever disappear, this test has stopped guarding anything and should be revisited
        // deliberately rather than kept as decoration.
        val duplicateCodes = entries.groupingBy { it.code }.eachCount().filter { it.value > 1 }.keys
        assertAll(
            { assertTrue(entries.size >= 30, "catalog did not load: ${entries.size} entries") },
            {
                assertTrue(
                    duplicateCodes.containsAll(setOf("0", "2001", "500.001.1001")),
                    "expected bare `code` to collide for 0, 2001 and 500.001.1001, but found: " +
                        "$duplicateCodes. If the catalog was deduplicated, the (code, family) key " +
                        "policy needs revisiting.",
                )
            },
        )

        // The collision is not cosmetic: the two 2001 rows disagree on `retryable`, which is the
        // boolean a merchant branches on when deciding whether to charge again.
        val stk2001 = entries.single { it.code == "2001" && it.family == DarajaFamily.STK_RESULT }
        val b2c2001 = entries.single { it.code == "2001" && it.family == DarajaFamily.B2C_C2B_RESULT }
        assertTrue(
            stk2001.retryable != b2c2001.retryable,
            "the 2001 rows no longer disagree on retryable; this test's premise has changed",
        )
    }
}
