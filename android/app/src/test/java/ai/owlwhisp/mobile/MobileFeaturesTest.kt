package ai.owlwhisp.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class MobileFeaturesTest {
    @Test fun versionsUseNumericOrderingAndRejectPreviewsAndMalformedTags() {
        assertTrue(ReleaseVersion.parse("0.10.0")!! > ReleaseVersion.parse("v0.9.9")!!)
        assertEquals(ReleaseVersion(1, 2, 3), ReleaseVersion.parse("v1.2.3"))
        for (value in listOf("", "1.2", "01.2.3", "1.2.3-beta", "1.2.3/evil", "9999999.0.0", "1.2.3\n")) {
            assertNull(value, ReleaseVersion.parse(value))
        }
    }
    @Test fun routingPreservesManualChoiceAndRejectsUnsupportedAssignments() {
        val models = listOf(LanguageRouting.Model("manual", listOf("en", "ru")),
            LanguageRouting.Model("english", listOf("en")), LanguageRouting.Model("russian", listOf("ru")),
            LanguageRouting.Model("arm-only", listOf("en"), false))
        val assignments = mapOf("en" to "english", "ru" to "russian")
        assertEquals("manual", LanguageRouting.resolve(false, "ru", assignments, "manual", models))
        assertEquals("russian", LanguageRouting.resolve(true, "ru", assignments, "manual", models))
        assertEquals("english", LanguageRouting.resolve(true, "en", assignments, "manual", models))
        assertEquals("manual", LanguageRouting.resolve(true, "fr", assignments, "manual", models))
        assertFalse(LanguageRouting.assign("ru", "english", models))
        assertFalse(LanguageRouting.assign("en", "arm-only", models))
        assertEquals("manual", LanguageRouting.resolve(true, "en", mapOf("en" to "arm-only"), "manual", models))
    }
    @Test fun updateURLsStayOnGitHubHttpsServers() {
        assertTrue(UpdateNetwork.allowed("https://github.com/Fil-Shtopor/OwlWhisp-Mobile/releases"))
        assertTrue(UpdateNetwork.allowed("https://release-assets.githubusercontent.com/file?token=public-download"))
        for (url in listOf("http://github.com/file", "https://github.com.evil.test/file",
            "https://github.com@evil.test/file", "https://user@github.com/file", "https://github.com:444/file",
            "file:///tmp/update.apk")) assertFalse(url, UpdateNetwork.allowed(url))
    }
    @Test fun downloadsRejectCorruptionTruncationOversizeAndCancellation() {
        val content = "verified update payload".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        val asset = UpdateAsset("https://github.com/file", content.size.toLong(), digest)
        val file = File.createTempFile("owlwhisp-update", ".apk")
        try {
            VerifiedUpdateDownload.copy(ByteArrayInputStream(content), file, asset)
            assertArrayEquals(content, file.readBytes())
            assertThrows(IllegalArgumentException::class.java) {
                VerifiedUpdateDownload.copy(ByteArrayInputStream(content.copyOf(content.size - 1)), file, asset)
            }
            assertThrows(IllegalArgumentException::class.java) {
                VerifiedUpdateDownload.copy(ByteArrayInputStream(content + byteArrayOf(0)), file, asset)
            }
            assertThrows(IllegalArgumentException::class.java) {
                VerifiedUpdateDownload.copy(ByteArrayInputStream(content), file, asset.copy(sha256 = "0".repeat(64)))
            }
            assertThrows(IllegalStateException::class.java) {
                VerifiedUpdateDownload.copy(ByteArrayInputStream(content), file, asset, { true })
            }
        } finally { file.delete() }
    }
}
