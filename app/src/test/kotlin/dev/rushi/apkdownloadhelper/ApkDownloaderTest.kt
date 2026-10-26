package dev.rushi.apkdownloadhelper

import java.io.File
import java.util.Collections
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkDownloaderTest {

    private val payload = ByteArray((PARALLEL_MIN_SIZE + 12_345).toInt()).also {
        Random(7).nextBytes(it)
        it[0] = 'P'.code.toByte()
        it[1] = 'K'.code.toByte()
    }

    private fun server(acceptRanges: Boolean, ranges: MutableList<String?>) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                ranges += range
                val response = MockResponse()
                if (acceptRanges) response.setHeader("Accept-Ranges", "bytes")
                if (range == null || !acceptRanges) {
                    return response.setBody(Buffer().write(payload))
                }
                val (start, end) = range.removePrefix("bytes=").split("-").map(String::toInt)
                return response
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .setBody(Buffer().write(payload, start, end - start + 1))
            }
        }
        start()
    }

    private fun download(server: MockWebServer): File {
        val target = File.createTempFile("apk-downloader", ".part").also { it.delete() }
        val downloader = ApkDownloader(OkHttpClient(), OkHttpClient())
        runBlocking {
            downloader.downloadToFile(
                CandidateDownloadFile(url = server.url("/file.apk").toString(), fileName = "file.apk"),
                target
            ) { _, _ -> }
        }
        return target
    }

    @Test
    fun largeFileWithRangesDownloadsInParallelSegments() {
        val ranges = Collections.synchronizedList(mutableListOf<String?>())
        val server = server(acceptRanges = true, ranges = ranges)
        val target = download(server)
        server.shutdown()

        assertArrayEquals(payload, target.readBytes())
        assertEquals(PARALLEL_SEGMENTS, ranges.size)
        assertEquals(PARALLEL_SEGMENTS - 1, ranges.count { it != null })
        target.delete()
    }

    @Test
    fun serverWithoutRangesDownloadsInOneRequest() {
        val ranges = Collections.synchronizedList(mutableListOf<String?>())
        val server = server(acceptRanges = false, ranges = ranges)
        val target = download(server)
        server.shutdown()

        assertArrayEquals(payload, target.readBytes())
        assertTrue(ranges.single() == null)
        target.delete()
    }
}
