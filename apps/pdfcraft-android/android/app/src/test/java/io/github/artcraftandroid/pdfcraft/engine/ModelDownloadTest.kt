package io.github.artcraftandroid.pdfcraft.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #289 section 4: an optional model is fetched, resumed and checked before it takes the model's name. */
class ModelDownloadTest {
    private val bytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val pack = ModelPack("flatten", "scan-flatten.onnx", ModelDownload.URL_PREFIX + "1/scan-flatten.onnx", bytes.size.toLong(), sha)
    private val asset = "https://objects.githubusercontent.com/asset"

    private fun dir(): File = Files.createTempDirectory("packs").toFile()

    /** A server that honours Range, serving [data] from the offset asked for. */
    private fun server(data: ByteArray = bytes, requests: MutableList<Long> = mutableListOf()): (String, Long) -> PackResponse = { _, offset ->
        requests += offset
        if (offset > 0) {
            PackResponse(206, "bytes $offset-${data.size - 1}/${data.size}", data.size - offset, null, asset, { ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt()) })
        } else {
            PackResponse(200, null, data.size.toLong(), null, asset, { ByteArrayInputStream(data) })
        }
    }

    @Test
    fun aDownloadThatMatchesTakesTheModelsName() {
        val d = dir()
        val seen = mutableListOf<Int>()
        val r = ModelDownload.download(d, pack, server(), progress = { seen += it }, sleep = {})
        assertEquals(PackOutcome.Done, r.outcome)
        assertArrayEquals(bytes, File(d, pack.file).readBytes())
        assertFalse(ModelDownload.partFile(d, pack).exists())
        assertEquals(100, seen.last())
    }

    @Test
    fun anInterruptedDownloadResumesFromWhatWasSaved() {
        val d = dir()
        ModelDownload.partFile(d, pack).writeBytes(bytes.copyOf(120_000))
        val requests = mutableListOf<Long>()
        val r = ModelDownload.download(d, pack, server(requests = requests), sleep = {})
        assertEquals(PackOutcome.Done, r.outcome)
        assertEquals(listOf(120_000L), requests)
        assertEquals(120_000L, r.resumed)
        assertArrayEquals(bytes, File(d, pack.file).readBytes())
    }

    @Test
    fun aServerThatIgnoresTheRangeStartsAgainInsteadOfAppending() {
        val d = dir()
        ModelDownload.partFile(d, pack).writeBytes(bytes.copyOf(50_000))
        val ignoresRange: (String, Long) -> PackResponse = { _, _ -> PackResponse(200, null, bytes.size.toLong(), null, asset, { ByteArrayInputStream(bytes) }) }
        assertEquals(PackOutcome.Done, ModelDownload.download(d, pack, ignoresRange, sleep = {}).outcome)
        assertArrayEquals(bytes, File(d, pack.file).readBytes())
    }

    @Test
    fun aTimeoutIsRetriedAndKeepsWhatArrived() {
        val d = dir()
        var calls = 0
        val flaky: (String, Long) -> PackResponse = { url, offset ->
            calls++
            if (calls == 1) {
                // half the file, then the connection stalls
                PackResponse(200, null, bytes.size.toLong(), null, asset, {
                    object : InputStream() {
                        var i = 0
                        override fun read(): Int = if (i < 150_000) bytes[i++].toInt() and 255 else throw SocketTimeoutException()
                    }
                })
            } else {
                server()(url, offset)
            }
        }
        val r = ModelDownload.download(d, pack, flaky, sleep = {})
        assertEquals(PackOutcome.Done, r.outcome)
        assertEquals(2, calls)
        assertArrayEquals(bytes, File(d, pack.file).readBytes())
    }

    @Test
    fun aFileThatDoesNotMatchItsHashIsThrownAway() {
        val d = dir()
        val wrong = bytes.copyOf().also { it[7] = (it[7] + 1).toByte() }
        val r = ModelDownload.download(d, pack, server(wrong), sleep = {})
        assertEquals(PackOutcome.Failed, r.outcome)
        assertEquals("mismatch", r.reason)
        assertFalse(File(d, pack.file).exists())
        assertFalse(ModelDownload.partFile(d, pack).exists())
    }

    @Test
    fun cancellingKeepsTheSavedBytesForNextTime() {
        val d = dir()
        var reads = 0
        val r = ModelDownload.download(d, pack, server(), cancelled = { reads++ > 2 }, sleep = {})
        assertEquals(PackOutcome.Cancelled, r.outcome)
        assertFalse(File(d, pack.file).exists())
        assertTrue(ModelDownload.savedBytes(d, pack) > 0)
    }

    @Test
    fun onlyThisRepositorysModelReleasesAndGitHubHostsAreTrusted() {
        assertTrue(ModelDownload.trusted(pack))
        assertFalse(ModelDownload.trusted(pack.copy(url = "https://example.com/scan-flatten.onnx")))
        assertFalse(ModelDownload.trusted(pack.copy(url = "http://github.com/MohamedRef/pdfcraft-android/releases/download/scan-models-1/scan-flatten.onnx")))
        assertFalse(ModelDownload.trusted(pack.copy(url = ModelDownload.URL_PREFIX + "1/other.onnx")))
        assertFalse(ModelDownload.trusted(pack.copy(file = "../x.onnx", url = ModelDownload.URL_PREFIX + "1/../x.onnx")))
        assertFalse(ModelDownload.trusted(pack.copy(bytes = ModelDownload.MAX_BYTES + 1)))
        assertTrue(ModelDownload.trustedHost(asset))
        assertFalse(ModelDownload.trustedHost("https://evil.example/asset"))

        // a redirect elsewhere is refused before anything is written
        val d = dir()
        val redirected: (String, Long) -> PackResponse = { _, _ -> PackResponse(200, null, bytes.size.toLong(), null, "https://evil.example/x", { ByteArrayInputStream(bytes) }) }
        val r = ModelDownload.download(d, pack, redirected, sleep = {})
        assertEquals("untrusted", r.reason)
        assertFalse(ModelDownload.partFile(d, pack).exists())
    }

    @Test
    fun removeLeavesNothingOfTheModel() {
        val d = dir()
        File(d, pack.file).writeBytes(bytes)
        ModelDownload.partFile(d, pack).writeBytes(ByteArray(3))
        assertTrue(ModelDownload.remove(d, pack))
        assertEquals(0L, ModelDownload.savedBytes(d, pack))
    }

    @Test
    fun theLogLineHoldsShortWordsAndNumbersOnly() {
        val line = diagLine(java.time.LocalDateTime.of(2026, 11, 12, 12, 0), DiagArea.Scan, "model_download", PackResult(PackOutcome.Done, "ok", 300, 120, 1, 40).diag("flatten").asList())
        assertTrue(line, line.endsWith("[scan] model_download id=flatten outcome=done reason=ok bytes=300 resumed=120 retries=1 ms=40"))
    }
}
