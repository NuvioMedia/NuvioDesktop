package com.nuvio.app.features.updater

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ReleaseChecksumTest {
    // Shape of the SHA256SUMS.txt the desktop release workflow publishes.
    private val publishedChecksums = """
        2dcab2bf5cb84274b6b677ff92348951696d526470b80b59f6d9077eb298724a  Nuvio-macOS-arm64-0.1.33-alpha.dmg
        a60574364e2b1a253b62a0a93a7e9c6ba38971c3f2e36c10cc913cb8987cee75  Nuvio-Windows-x64-0.1.33-alpha.msi
        4953acb7c4bb740e97f8c9dbfb951ed4fce61794744813a2721c8c0efbdf9f26 *Nuvio-Linux-x86_64-0.1.33-alpha.AppImage.zsync
    """.trimIndent()

    @Test
    fun checksum_url_is_derived_next_to_the_downloaded_asset() {
        assertEquals(
            "https://github.com/NuvioMedia/NuvioDesktop/releases/download/0.1.33-alpha/SHA256SUMS.txt",
            checksumsUrlFor(
                "https://github.com/NuvioMedia/NuvioDesktop/releases/download/0.1.33-alpha/Nuvio-Windows-x64-0.1.33-alpha.msi",
            ),
        )
        assertEquals(
            "https://example.com/releases/1.0.0/SHA256SUMS.txt",
            checksumsUrlFor("https://example.com/releases/1.0.0/Nuvio.deb?sig=abc"),
        )
    }

    @Test
    fun non_https_assets_have_no_checksum_url() {
        assertNull(checksumsUrlFor("http://example.com/release/Nuvio.deb"))
        assertNull(checksumsUrlFor("file:///tmp/Nuvio.deb"))
        assertNull(checksumsUrlFor("https://example.com/"))
    }

    @Test
    fun published_checksums_are_parsed_into_file_names_and_hashes() {
        val checksums = parseReleaseChecksums(publishedChecksums)

        assertEquals(3, checksums.size)
        assertEquals(
            "a60574364e2b1a253b62a0a93a7e9c6ba38971c3f2e36c10cc913cb8987cee75",
            checksums["Nuvio-Windows-x64-0.1.33-alpha.msi"],
        )
        assertEquals(
            "4953acb7c4bb740e97f8c9dbfb951ed4fce61794744813a2721c8c0efbdf9f26",
            checksums["Nuvio-Linux-x86_64-0.1.33-alpha.AppImage.zsync"],
            "the '*' binary marker must not end up in the file name",
        )
    }

    @Test
    fun malformed_checksum_lines_are_ignored() {
        val checksums = parseReleaseChecksums(
            """
            not a checksum line
            2dcab2bf5cb84274b6b677ff92348951696d526470b80b59f6d9077eb298724a
            zzzab2bf5cb84274b6b677ff92348951696d526470b80b59f6d9077eb298724a  Short.dmg
            2dcab2bf5cb84274b6b677ff92348951696d526470b80b59f6d9077eb298724a  Good.dmg
            """.trimIndent(),
        )

        assertEquals(setOf("Good.dmg"), checksums.keys)
    }

    @Test
    fun matching_hash_verifies_and_a_different_hash_does_not() {
        val checksums = parseReleaseChecksums(publishedChecksums)
        val expected = checksums.getValue("Nuvio-Windows-x64-0.1.33-alpha.msi")

        assertEquals(
            UpdateChecksumCheck.Verified,
            checkUpdateChecksum(checksums, "Nuvio-Windows-x64-0.1.33-alpha.msi", expected),
        )
        assertEquals(
            UpdateChecksumCheck.Verified,
            checkUpdateChecksum(checksums, "Nuvio-Windows-x64-0.1.33-alpha.msi", expected.uppercase()),
            "hashes are case insensitive",
        )
        assertTrue(
            checkUpdateChecksum(checksums, "Nuvio-Windows-x64-0.1.33-alpha.msi", "0".repeat(64)) is
                UpdateChecksumCheck.Mismatch,
        )
    }

    @Test
    fun an_asset_missing_from_the_published_checksums_is_reported() {
        assertEquals(
            UpdateChecksumCheck.NotListed,
            checkUpdateChecksum(parseReleaseChecksums(publishedChecksums), "Unlisted.deb", "0".repeat(64)),
        )
    }

    @Test
    fun file_digest_matches_the_known_sha256_of_the_bytes() {
        val file = Files.createTempFile("nuvio-checksum", ".bin").toFile()
        try {
            file.writeText("abc")

            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                sha256Hex(file),
            )
            assertEquals(
                MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()).joinToString("") {
                    byte -> byte.toInt().and(0xFF).toString(16).padStart(2, '0')
                },
                sha256Hex(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun a_verified_download_passes_and_a_tampered_one_fails() {
        runBlocking {
            val file = Files.createTempFile("nuvio-update", ".msi").toFile()
            try {
                file.writeText("installer bytes")
                val assetName = "Nuvio-Windows-x64-0.1.33-alpha.msi"
                val checksums = "${sha256Hex(file)}  $assetName\n"
                val checksumsUrl = "https://example.com/0.1.33-alpha/SHA256SUMS.txt"

                verifyUpdateChecksum(file, assetName, checksumsUrl) { checksums }

                file.writeText("installer bytes, tampered")
                assertFailsWith<IllegalStateException>("a tampered download must not be installed") {
                    verifyUpdateChecksum(file, assetName, checksumsUrl) { checksums }
                }
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun a_missing_checksum_file_does_not_block_the_install() {
        runBlocking {
            val file = Files.createTempFile("nuvio-update", ".msi").toFile()
            try {
                file.writeText("installer bytes")
                val checksumsUrl = "https://example.com/0.1.33-alpha/SHA256SUMS.txt"

                verifyUpdateChecksum(file, "Nuvio.msi", checksumsUrl) { null }
                verifyUpdateChecksum(file, "Nuvio.msi", checksumsUrl) { "" }
                verifyUpdateChecksum(file, "Nuvio.msi", null) { error("must not be called") }
            } finally {
                file.delete()
            }
        }
    }
}
