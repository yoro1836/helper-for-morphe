package dev.rushi.apkdownloadhelper

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

private const val MAX_DOWNLOAD_ATTEMPTS = 3
private const val DOWNLOAD_RETRY_BASE_MS = 2_000L
private const val HTTP_PARTIAL_CONTENT = 206
private const val COPY_BUFFER_SIZE = 64 * 1024

/** Files at least this big are fetched over several connections when the server allows ranges. */
internal const val PARALLEL_MIN_SIZE = 4L * 1024L * 1024L
internal const val PARALLEL_SEGMENTS = 4

/**
 * Streaming download engine with parallel ranges, partial-range resume and
 * automatic retries.
 *
 * The first attempt of a large file whose server accepts ranges is split into
 * [PARALLEL_SEGMENTS] ranges fetched over separate HTTP/1.1 connections (per-
 * connection throttling on CDNs is the usual bottleneck). Otherwise, and on
 * retries, it streams into `target` (usually a `.part` file), resuming from the
 * bytes already there with a Range request when the server supports it.
 * Transient failures (network I/O, HTTP 408/429/5xx) retry up to
 * [MAX_DOWNLOAD_ATTEMPTS] times with exponential backoff, reporting each retry
 * through [onRetry].
 */
internal class ApkDownloader(
    private val client: OkHttpClient,
    private val apkPureClient: OkHttpClient,
    private val onRetry: (attempt: Int, delayMs: Long) -> Unit = { _, _ -> }
) {
    // Extra range segments use HTTP/1.1 so they get their own TCP connections
    // instead of sharing one HTTP/2 connection. The first request keeps the
    // normal client so bot checks see the same client as before.
    private val rangeClient = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
    private val apkPureRangeClient = apkPureClient.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()

    private val activeCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var cancelled = false

    fun cancelCurrent() {
        cancelled = true
        activeCalls.forEach(Call::cancel)
    }

    suspend fun downloadToFile(
        download: CandidateDownloadFile,
        target: File,
        onProgress: (copied: Long, total: Long) -> Unit
    ) {
        cancelled = false
        var attempt = 0
        while (true) {
            try {
                performDownloadAttempt(download, target, allowParallel = attempt == 0, onProgress)
                return
            } catch (error: Throwable) {
                if (cancelled || error is CancellationException || error.message == "Canceled") throw error
                if (attempt >= MAX_DOWNLOAD_ATTEMPTS - 1 || !isTransientDownloadError(error)) throw error
                attempt++
                val delayMs = DOWNLOAD_RETRY_BASE_MS * (1L shl (attempt - 1))
                onRetry(attempt, delayMs)
                delay(delayMs)
            }
        }
    }

    private suspend fun performDownloadAttempt(
        download: CandidateDownloadFile,
        target: File,
        allowParallel: Boolean,
        onProgress: (copied: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val url = download.url.normalizedHttpUrlOrNull()
            ?: error("Source returned an invalid download URL.".withManualModeHint())
        val partial = if (target.exists()) target.length() else 0L
        val builder = Request.Builder().url(url)
        download.referer?.let { builder.header("Referer", it) }
        // Cookies captured by the in-app browser (Cloudflare clearance etc.).
        download.cookieHeader?.let { builder.header("Cookie", it) }
        if (partial > 0L) builder.header("Range", "bytes=$partial-")

        val downloadClient = downloadClientFor(url)
        execute(downloadClient, builder.build()).use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val resumed = response.code == HTTP_PARTIAL_CONTENT && partial > 0L
            val contentLength = response.body.contentLength()
            val total: Long = if (resumed) {
                partial + contentLength.coerceAtLeast(0L)
            } else {
                download.size ?: contentLength.coerceAtLeast(0L)
            }
            val contentType = response.body.contentType()?.toString()
            val input = if (resumed) {
                response.body.byteStream()
            } else {
                validateApkLikeStream(response.body.byteStream(), contentType)
            }

            if (allowParallel && !resumed && response.code == 200 && response.acceptsByteRanges() &&
                contentLength >= PARALLEL_MIN_SIZE
            ) {
                try {
                    // Ranges go to the post-redirect URL so a one-shot source
                    // link (e.g. APKMirror's download.php) is only hit once.
                    downloadParallel(
                        client = if (downloadClient === apkPureClient) apkPureRangeClient else rangeClient,
                        url = response.request.url.toString(),
                        referer = download.referer,
                        cookieHeader = download.cookieHeader,
                        firstSegment = input,
                        total = contentLength,
                        target = target,
                        onProgress = onProgress
                    )
                } catch (error: Throwable) {
                    target.delete()
                    if (cancelled || error is CancellationException) throw error
                    throw IOException("Parallel download failed: ${error.message}", error)
                }
                return@use
            }

            FileOutputStream(target, resumed).buffered(COPY_BUFFER_SIZE).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var copied = if (resumed) partial else 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(copied, total)
                }
            }
        }
    }

    private suspend fun downloadParallel(
        client: OkHttpClient,
        url: String,
        referer: String?,
        cookieHeader: String?,
        firstSegment: InputStream,
        total: Long,
        target: File,
        onProgress: (copied: Long, total: Long) -> Unit
    ) = coroutineScope {
        RandomAccessFile(target, "rw").use { it.setLength(total) }
        val segmentSize = (total + PARALLEL_SEGMENTS - 1) / PARALLEL_SEGMENTS
        val ranges = (0 until PARALLEL_SEGMENTS)
            .map { index -> index * segmentSize to minOf(total, (index + 1) * segmentSize) - 1 }
            .filter { (start, end) -> start <= end }
        val copied = AtomicLong(0L)
        val progressLock = Any()
        val report: (Int) -> Unit = { read ->
            val now = copied.addAndGet(read.toLong())
            synchronized(progressLock) { onProgress(now, total) }
        }

        ranges.mapIndexed { index, (start, end) ->
            async(Dispatchers.IO) {
                try {
                    if (index == 0) {
                        // The first segment reuses the probe response's stream.
                        copyRange(firstSegment, target, start, end, report)
                    } else {
                        downloadRange(client, url, referer, cookieHeader, start, end, target, report)
                    }
                } catch (error: Throwable) {
                    // Blocking reads ignore coroutine cancellation; unblock the
                    // sibling segments so the attempt fails fast.
                    activeCalls.forEach(Call::cancel)
                    throw error
                }
            }
        }.awaitAll()
    }

    private fun downloadRange(
        client: OkHttpClient,
        url: String,
        referer: String?,
        cookieHeader: String?,
        start: Long,
        end: Long,
        target: File,
        report: (Int) -> Unit
    ) {
        val builder = Request.Builder().url(url).header("Range", "bytes=$start-$end")
        referer?.let { builder.header("Referer", it) }
        cookieHeader?.let { builder.header("Cookie", it) }
        execute(client, builder.build()).use { response ->
            check(response.code == HTTP_PARTIAL_CONTENT) { "HTTP ${response.code} for range $start-$end" }
            val contentRange = response.header("Content-Range").orEmpty()
            if (!contentRange.startsWith("bytes $start-")) {
                throw IOException("Unexpected Content-Range '$contentRange' for $start-$end")
            }
            copyRange(response.body.byteStream(), target, start, end, report)
        }
    }

    private fun copyRange(input: InputStream, target: File, start: Long, end: Long, report: (Int) -> Unit) {
        RandomAccessFile(target, "rw").use { file ->
            file.seek(start)
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var remaining = end - start + 1
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("Range $start-$end ended early")
                file.write(buffer, 0, read)
                remaining -= read
                report(read)
            }
        }
    }

    private fun execute(client: OkHttpClient, request: Request): Response {
        val call = client.newCall(request)
        activeCalls += call
        if (cancelled) call.cancel()
        return try {
            call.execute()
        } finally {
            activeCalls -= call
        }
    }

    private fun Response.acceptsByteRanges(): Boolean =
        header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true

    private fun downloadClientFor(url: String): OkHttpClient =
        if (url.contains("apkpure", ignoreCase = true)) apkPureClient else client

    private fun isTransientDownloadError(error: Throwable): Boolean {
        if (error is IOException) return true
        val message = error.message.orEmpty()
        return Regex("""HTTP (408|429|5\d\d)\b""").containsMatchIn(message)
    }
}

private fun validateApkLikeStream(
    input: InputStream,
    contentType: String?
): InputStream {
    val header = ByteArray(4)
    val headerSize = input.read(header)
    val isZip = headerSize >= 2 &&
        header[0] == 'P'.code.toByte() &&
        header[1] == 'K'.code.toByte()

    check(isZip) {
        val type = contentType?.let { " ($it)" }.orEmpty()
        "Source did not return a valid APK/APKS/XAPK$type."
    }

    return SequenceInputStream(ByteArrayInputStream(header, 0, headerSize), input)
}
