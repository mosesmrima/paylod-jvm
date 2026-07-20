package dev.paylod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.io.File

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
 */
class DarajaCatalogDriftTest {

    private fun canonicalCatalog(): File {
        val root = System.getenv("MPESA_REPO")
            ?: File(System.getProperty("user.dir")).parentFile.resolve("mpesa").path
        return File(root, "supabase/functions/_shared/daraja/daraja-error-codes.json")
    }

    private fun vendoredCatalog(): File =
        File(System.getProperty("user.dir"), "src/main/resources/dev/paylod/daraja-error-codes.json")

    /**
     * Skipping when the monorepo is absent is fine — a consumer checkout genuinely cannot verify a
     * file it does not have. Passing when it IS present and DIFFERS is not fine, and is the case
     * this exists to make impossible.
     */
    @Test
    @Tag("nv-catalog-drift")
    fun `the vendored Daraja catalog is byte-identical to the canonical one`() {
        val canonical = canonicalCatalog()
        assumeTrue(
            canonical.isFile,
            "SKIPPED: the paylod monorepo is not checked out beside this repo, so the vendored " +
                "Daraja catalog cannot be verified against canonical. Expected it at $canonical " +
                "(set MPESA_REPO=/path/to/mpesa). This is a skip, NOT a pass.",
        )
        val vendored = vendoredCatalog()
        assertTrue(vendored.isFile, "the vendored catalog is missing entirely: $vendored")
        assertEquals(
            canonical.readText(),
            vendored.readText(),
            "DRIFT: the vendored Daraja catalog differs from canonical at $canonical. " +
                "A merchant on this SDK would read different words than a merchant on another. " +
                "Run: ./gradlew syncDarajaCatalog",
        )
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
