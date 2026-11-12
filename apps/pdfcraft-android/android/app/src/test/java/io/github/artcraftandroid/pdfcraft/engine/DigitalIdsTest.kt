package io.github.artcraftandroid.pdfcraft.engine

import io.github.artcraftandroid.pdfcraft.core.DigitalIdInfo
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Digital IDs and trusted signers kept in the app's files (#125). */
class DigitalIdsTest {
    private fun info(sha: String, name: String = "Mona") =
        DigitalIdInfo(name, "Issuer", null, validFrom = 1_000, validTo = 2_000, selfSigned = true, sha256 = sha, problem = null)

    @Test fun validityIsJudgedInSeconds() {
        val id = DigitalId(File("x"), "n", "i", null, validFrom = 1_000, validTo = 2_000, selfSigned = false, sha256 = "ab")
        assertTrue(id.notYetValid(nowMs = 999_000))
        assertFalse(id.notYetValid(nowMs = 1_000_000))
        assertFalse(id.expired(nowMs = 2_000_999))
        assertTrue(id.expired(nowMs = 2_001_000))
    }

    @Test fun anAddedIdIsKeptUnderItsFingerprintAndSurvivesARestart() {
        val dir = Files.createTempDirectory("ids").toFile()
        val source = File(dir, "mine.p12").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val ids = DigitalIds(dir)
        val id = ids.add(source, info("AB:CD 12"))
        assertEquals("abcd12.p12", id.file.name)
        assertTrue(id.file.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(listOf("Mona"), DigitalIds(dir).all.map { it.name })
    }

    @Test fun theSameCertificateReplacesTheOldEntryAndRemoveDeletesTheFile() {
        val dir = Files.createTempDirectory("ids").toFile()
        val ids = DigitalIds(dir)
        ids.add(File(dir, "a.p12").apply { writeText("a") }, info("ff", "Old"))
        val id = ids.add(File(dir, "b.p12").apply { writeText("b") }, info("FF", "New"))
        assertEquals(listOf("New"), ids.all.map { it.name })
        ids.remove(id)
        assertFalse(id.file.exists())
        assertTrue(DigitalIds(dir).all.isEmpty())
    }

    @Test fun anIdWhoseFileIsGoneIsLeftOut() {
        val dir = Files.createTempDirectory("ids").toFile()
        val id = DigitalIds(dir).add(File(dir, "a.p12").apply { writeText("a") }, info("01"))
        id.file.delete()
        assertTrue(DigitalIds(dir).all.isEmpty())
    }

    @Test fun trustedCertificatesAreKeyedByFingerprint() {
        val dir = Files.createTempDirectory("trust").toFile()
        val t = TrustedCertificates(dir)
        t.add(byteArrayOf(9), "AA bb")
        assertTrue(t.contains("aabb"))
        assertEquals(listOf("aabb.cer"), t.files().map { it.name })
        t.remove("AA:BB")
        assertFalse(t.contains("aabb"))
    }
}
