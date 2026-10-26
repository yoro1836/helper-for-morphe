package dev.rushi.apkdownloadhelper

import android.content.Context
import android.util.Log
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import dev.rushi.apkdownloadhelper.play.NativeDeviceInfoProvider
import dev.rushi.apkdownloadhelper.play.PlayHttpClient

/**
 * Google Play downloads through Aurora's anonymous accounts. Play's purchase
 * call accepts any version code it still hosts, so older builds can be fetched
 * when their version code is known: from the request itself, from Aptoide's
 * version list (name -> code), or typed by the user.
 */
internal class AuroraParser(private val ctx: SourceParserContext) : ApkSourceParser {
    override val source: DownloadSource = DownloadSource.AURORA

    private val playHttpClient: PlayHttpClient
        get() = checkNotNull(ctx.playHttpClient) { "Play client unavailable." }

    private val appContext: Context
        get() = checkNotNull(ctx.appContext) { "App context unavailable." }

    private var playAuthData: AuthData? = null

    override suspend fun findCandidates(
        request: HelperRequest,
        option: CandidateOption
    ): List<DownloadCandidate> = when (option) {
        CandidateOption.LATEST -> findAurora(request)
        CandidateOption.REQUESTED -> findAuroraRequested(request)
        CandidateOption.MANUAL -> emptyList()
    }

    override suspend fun resolveHistory(request: HelperRequest): List<DownloadCandidate> {
        val app = playApp(request) ?: return emptyList()
        val versions = buildList {
            add(PlayVersion(app.versionName, app.versionCode))
            request.requestedVersionCodes.forEach { add(PlayVersion(request.requestedVersionName, it)) }
            request.compatibleVersionCodes.filter { it > 0L }.forEach { add(PlayVersion(null, it)) }
            addAll(knownVersions(request))
        }
            // Play can't serve a build newer than its current one.
            .filter { it.versionCode in 1..app.versionCode }
            .groupBy(PlayVersion::versionCode)
            .map { (code, entries) -> PlayVersion(entries.firstNotNullOfOrNull(PlayVersion::versionName), code) }
            .sortedByDescending(PlayVersion::versionCode)

        return versions.map { version ->
            DownloadCandidate(
                source = DownloadSource.AURORA,
                name = app.displayName.ifBlank { request.appName },
                packageName = app.packageName,
                versionName = version.versionName,
                versionCode = version.versionCode,
                url = playStoreUrl(app.packageName),
                fileKind = "web",
                option = CandidateOption.LATEST,
                directDownload = false,
                versionStatus = request.versionStatus(version.versionName, version.versionCode),
                formatMatches = true
            )
        }
    }

    override suspend fun resolveHistoryCandidate(
        request: HelperRequest,
        candidate: DownloadCandidate
    ): DownloadCandidate? {
        val versionCode = candidate.versionCode?.takeIf { it > 0L } ?: return null
        val app = playApp(request) ?: return null
        return auroraCandidate(request, app, versionCode, candidate.versionName, candidate.option)
            ?: throw IllegalStateException("Google Play no longer serves build $versionCode.")
    }

    private fun findAurora(request: HelperRequest): List<DownloadCandidate> {
        val app = playApp(request) ?: return emptyList()
        return listOfNotNull(
            auroraCandidate(request, app, app.versionCode, app.versionName, CandidateOption.LATEST)
        )
    }

    private suspend fun findAuroraRequested(request: HelperRequest): List<DownloadCandidate> {
        val app = playApp(request) ?: return emptyList()
        for (target in requestedVersions(request, app)) {
            val candidate = runCatching {
                auroraCandidate(request, app, target.versionCode, target.versionName, CandidateOption.REQUESTED)
            }
                .onFailure { Log.w(TAG, "Aurora build ${target.versionCode} unavailable for ${request.packageName}", it) }
                .getOrNull()
            if (candidate != null) return listOf(candidate)
        }
        return emptyList()
    }

    /** Version codes worth trying for the request, best match first. */
    private suspend fun requestedVersions(request: HelperRequest, app: App): List<PlayVersion> {
        val names = request.requestedVersionNames
        val requestedCodes = request.requestedVersionCodes
        if (requestedCodes.isNotEmpty()) {
            return requestedCodes.map { PlayVersion(request.requestedVersionName, it) }
        }
        if (names.isEmpty()) return emptyList()
        if (names.any { app.versionName.versionNameEquals(it) }) {
            return listOf(PlayVersion(app.versionName, app.versionCode))
        }
        return knownVersions(request)
            .filter { version -> names.any { version.versionName.versionNameEquals(it) } }
            .sortedByDescending(PlayVersion::versionCode)
    }

    /** Version name -> code pairs from Aptoide's version list (one JSON call). */
    private suspend fun knownVersions(request: HelperRequest): List<PlayVersion> =
        runCatching { ctx.aptoideApi.listAppVersionsByPackage(request.packageName).list }
            .onFailure { Log.w(TAG, "Aptoide version list failed for ${request.packageName}", it) }
            .getOrDefault(emptyList())
            .filter { it.packageName == request.packageName }
            .mapNotNull { item ->
                val code = item.file.vercode.toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
                PlayVersion(item.file.vername.takeIf(String::isNotBlank), code)
            }

    private fun playApp(request: HelperRequest): App? {
        val app = AppDetailsHelper(playAuth())
            .using(playHttpClient)
            .getAppByPackageName(request.packageName)
        return app.takeIf { it.packageName == request.packageName && it.versionCode > 0L }
    }

    private fun auroraCandidate(
        request: HelperRequest,
        app: App,
        versionCode: Long,
        versionName: String?,
        option: CandidateOption
    ): DownloadCandidate? {
        val playFiles = getPlayInstallFiles(app, versionCode)
        val files = playFiles
            .mapIndexedNotNull { index, file ->
                val url = file.url.normalizedHttpUrlOrNull()
                if (url == null) {
                    Log.w(TAG, "Aurora returned invalid download URL for ${request.packageName}: ${file.url}")
                    return@mapIndexedNotNull null
                }
                CandidateDownloadFile(
                    url = url,
                    fileName = playFileName(file, index),
                    size = file.size
                )
            }

        if (files.size != playFiles.size) return null
        if (files.isEmpty()) return null

        val resolvedName = versionName ?: app.versionName.takeIf { versionCode == app.versionCode }
        val fileKind = if (files.size > 1) "apks" else "apk"
        return DownloadCandidate(
            source = DownloadSource.AURORA,
            name = app.displayName.ifBlank { request.appName },
            packageName = app.packageName,
            versionName = resolvedName,
            versionCode = versionCode,
            url = playStoreUrl(app.packageName),
            fileKind = fileKind,
            option = option,
            directDownload = true,
            versionStatus = if (option == CandidateOption.LATEST && versionCode == app.versionCode) {
                VersionStatus.LATEST
            } else {
                request.versionStatus(resolvedName, versionCode)
            },
            formatMatches = request.acceptsFormat(fileKind),
            files = files
        )
    }

    private fun playAuth(): AuthData {
        playAuthData?.let { return it }

        val properties = NativeDeviceInfoProvider(appContext).getNativeDeviceProperties()
        val response = playHttpClient.postAuth(AURORA_AUTH_URL, gson.toJson(properties).toByteArray())
        check(response.isSuccessful) { "Aurora auth failed: HTTP ${response.code}" }

        return gson.fromJson(String(response.responseBytes), AuthData::class.java).also {
            playAuthData = it
        }
    }

    private fun getPlayInstallFiles(app: App, versionCode: Long): List<PlayFile> =
        PurchaseHelper(playAuth())
            .using(playHttpClient)
            .purchase(app.packageName, versionCode, app.offerType)
            .filter { it.type == PlayFile.Type.BASE || it.type == PlayFile.Type.SPLIT }

    private fun playFileName(file: PlayFile, index: Int): String =
        when (file.type) {
            PlayFile.Type.BASE -> "base.apk"
            PlayFile.Type.SPLIT -> "split_$index.apk"
            else -> "file_$index.apk"
        }

    private data class PlayVersion(val versionName: String?, val versionCode: Long)
}
