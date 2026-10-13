package eu.darken.porter.manager.compatibility

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.drawable.toBitmap
import eu.darken.porter.common.CompatibilitySetup
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import eu.darken.porter.manager.BuildConfig
import eu.darken.porter.manager.authorization.AuthorizationManager
import org.json.JSONArray
import org.json.JSONObject
import eu.darken.porter.sdk.Porter
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal class CompatibilityRepository @VisibleForTesting internal constructor(
    private val context: Context,
    private val request: (operation: Int, argument: String?) -> Bundle = { operation, argument -> CompatibilityService.request(operation, argument) },
    private val command: suspend (arguments: Array<String>, apk: File?) -> String = { arguments, apk -> CompatibilityService.command(arguments, apk) },
    private val stopShizuku: suspend () -> Unit = { OriginalShizukuService(legacyPs = Build.VERSION.SDK_INT < 26).stop() },
    private val porterRunning: () -> Boolean = { runCatching { Porter.connection.value?.binder?.pingBinder() == true }.getOrDefault(false) },
    private val permissionOwner: (PackageManager) -> String? = OtherShizukuDetector::permissionOwner,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    enum class Status { MISSING, INSTALLED, UPDATE, CONFLICT, INVALID }
    enum class Phase { CHECKING, PREPARING, STOPPING, REMOVING, UNINSTALLING, INSTALLING, IMPORTING, ACTIVATING }
    data class Decision(val packageName: String, val label: String, val uid: Int, val flags: Int, val json: JSONObject)
    data class State(
        val status: Status = Status.MISSING,
        val running: Boolean = false,
        val supported: Boolean = false,
        val phase: Phase? = null,
        val error: String? = null,
        val completed: Boolean = false,
        val applied: Int = 0,
        val skipped: Int = 0,
        val preview: List<Decision>? = null,
        val previewUnavailable: Boolean = false,
        val hasSnapshot: Boolean = false,
        val otherUsers: Boolean = false,
        val installedLabel: String = "Shizuku",
        val installedIcon: Bitmap? = null,
        val installedVersionName: String? = null,
        val installedVersionCode: Long? = null,
        val isCompanion: Boolean = false,
        val inspectionError: String? = null,
        val manualUninstallRequired: Boolean = false,
        val stopRequired: Boolean = false,
        /** The package Replace removes while [status] is CONFLICT. */
        val conflictPackage: String? = null,
        val replaceable: Boolean = false,
    )
    /**
     * [target] is the package to inspect and replace: the foreign owner of [PERMISSION] when [foreign],
     * otherwise [PACKAGE]. [targetInfo] is null when that package is absent or hidden from Porter.
     */
    internal data class Resolution(
        val status: Status,
        val target: String,
        val foreign: Boolean,
        val companion: PackageInfo?,
        val targetInfo: PackageInfo?,
        val isCompanion: Boolean,
    )
    private data class Pin(val packageName: String, val certificate: String)
    private val busy = AtomicBoolean()
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val snapshot = CompatibilityImportStore(context.noBackupFilesDir)
    private val resultFile = AtomicFile(File(context.noBackupFilesDir, "compatibility-result.json"))
    private var pinned: Pin? = null
    private var inspected: Resolution? = null
    private var cachedIconKey: String? = null
    private var cachedIcon: Bitmap? = null
    private val flags = PACKAGE_FLAGS
    // Android 9 and 10 read an archive's signers only for GET_SIGNATURES and otherwise return it without signingInfo.
    @Suppress("DEPRECATION")
    private val archiveFlags = if (Build.VERSION.SDK_INT < 30) flags or PackageManager.GET_SIGNATURES else flags
    private val pm get() = context.packageManager
    val primaryUser get() = Process.myUid() / 100000 == 0

    init {
        runCatching {
            val result = JSONObject(resultFile.openRead().bufferedReader().use { it.readText() })
            mutable.update { it.copy(completed = result.optBoolean("completed"), error = result.optString("error").ifBlank { null }, applied = result.optInt("applied"), skipped = result.optInt("skipped")) }
        }
    }

    fun refresh() {
        scope.launch {
            if (busy.get() || !mutex.tryLock()) return@launch
            try { if (!busy.get()) inspect() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(inspectionError = e.message ?: e.javaClass.simpleName) }
            } finally { mutex.unlock() }
        }
    }

    private fun inspect() {
        val owner = permissionOwner(pm)
        val resolution = resolve(pm, owner, certificate(pm.getPackageInfo(context.packageName, flags)))
        val status = resolution.status
        val targetInfo = resolution.targetInfo
        val running = porterRunning()
        var supported = false
        var otherUsers = false
        var companionElsewhere = false
        var inspectionError: String? = null
        if (running && primaryUser && BuildConfig.IS_FOSS) {
            try {
                val info = request(CompatibilitySetup.INSPECT, resolution.target)
                otherUsers = info.getIntArray("users")?.any { it != 0 } ?: error("Cannot check Android users")
                companionElsewhere = info.getIntArray("companionUsers")?.isNotEmpty() ?: error("Cannot check Android users")
                supported = true
            } catch (e: Exception) { inspectionError = e.message ?: e.javaClass.simpleName }
        }
        val application = targetInfo?.applicationInfo
        // A system app survives pm uninstall, and an app under PACKAGE would make the companion install
        // fail after the foreign owner is already gone.
        val replaceable = status == Status.CONFLICT && running && supported && primaryUser && !otherUsers
            && application != null && (application.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            && (!resolution.foreign || (resolution.companion == null && !companionElsewhere))
        inspected = resolution
        val wasCompleted = mutable.value.completed
        mutable.update { it.copy(status = status, running = running, supported = supported, otherUsers = otherUsers,
            hasSnapshot = snapshot.exists,
            installedLabel = application?.loadLabel(pm)?.toString() ?: if (resolution.foreign) resolution.target else "Shizuku",
            installedIcon = icon(targetInfo),
            installedVersionName = targetInfo?.versionName, installedVersionCode = targetInfo?.let(::version), isCompanion = resolution.isCompanion,
            conflictPackage = resolution.target.takeIf { status == Status.CONFLICT }, replaceable = replaceable,
            inspectionError = inspectionError, completed = it.completed && status == Status.INSTALLED) }
        if (wasCompleted && !mutable.value.completed) persistResult()
    }

    fun prepareReplacement() = operation(Phase.PREPARING) {
        inspect()
        check(!snapshot.exists) { "Discard the saved import before reviewing a new replacement" }
        check(mutable.value.replaceable)
        val current = inspected ?: error("Installed app changed; check again")
        val target = Pin(current.target, certificate(current.targetInfo ?: error("Installed app changed; check again")))
        pinned = target
        val decisions = runCatching { request(CompatibilitySetup.PREVIEW_IMPORT, target.packageName).getString("decisions") ?: error("Missing import preview") }
        mutable.update { it.copy(preview = decisions.getOrNull()?.let(::decode).orEmpty(), previewUnavailable = decisions.isFailure) }
    }

    private fun checkReplacement(expected: Pin) {
        val current = inspected?.takeIf { it.target == expected.packageName }?.targetInfo
        check(mutable.value.replaceable && current != null && runCatching { certificate(current) }.getOrNull() == expected.certificate) {
            "Installed apps changed; review replacement again"
        }
    }

    fun dismissResult() {
        scope.launch {
            mutex.withLock {
                if (busy.get()) return@withLock
                mutable.update { it.copy(completed = false, applied = 0, skipped = 0) }
                runCatching { persistResult() }
            }
        }
    }

    fun dismissPreview() { mutable.update { it.copy(preview = null) } }

    fun replace() = operation(Phase.PREPARING) {
        check(!snapshot.exists) { "Discard the saved import before reviewing a new replacement" }
        check(mutable.value.preview != null) { "Review replacement first" }
        val expected = pinned ?: error("Review replacement first")
        val previewUnavailable = mutable.value.previewUnavailable
        inspect()
        checkReplacement(expected)
        mutable.update { it.copy(phase = Phase.STOPPING) }
        stopShizuku()
        mutable.update { it.copy(phase = Phase.PREPARING) }
        // Read after stopping the service so its final access choices are carried over.
        val fresh = runCatching {
            decode(request(CompatibilitySetup.PREVIEW_IMPORT, expected.packageName).getString("decisions") ?: error("Missing import snapshot"))
        }.getOrElse {
            if (!previewUnavailable) {
                mutable.update { it.copy(previewUnavailable = true) }
                error("App access choices could not be read. Review the warning before continuing.")
            }
            emptyList()
        }
        inspect()
        checkReplacement(expected)
        if (fresh.isNotEmpty()) snapshot.save(JSONArray().apply { fresh.forEach { put(it.json) } })
        else snapshot.discard()
        mutable.update { it.copy(phase = Phase.REMOVING, preview = null, hasSnapshot = fresh.isNotEmpty()) }
        check(command(arrayOf("pm", "uninstall", expected.packageName), null).startsWith("Success")) { "Shizuku was not removed" }
        check(packageInfo(pm, expected.packageName) == null) { "Shizuku was not removed" }
        installAndActivate()
    }

    fun install() = operation(Phase.INSTALLING) {
        inspect()
        check(mutable.value.supported && !mutable.value.otherUsers && mutable.value.status != Status.CONFLICT) { "Review the installed Shizuku app first" }
        installAndActivate()
    }

    fun afterAndroidInstaller() = operation(Phase.CHECKING) {
        inspect()
        if (mutable.value.status == Status.INSTALLED && mutable.value.supported) {
            mutable.update { it.copy(phase = Phase.ACTIVATING) }
            request(CompatibilitySetup.REFRESH, null)
            mutable.update { it.copy(completed = !snapshot.exists) }
        }
    }

    fun reinstall() = operation(Phase.INSTALLING) {
        inspect()
        val current = installed() ?: error("Compatibility app is no longer installed")
        check(mutable.value.isCompanion && mutable.value.supported && !mutable.value.otherUsers) { "Compatibility app cannot be reinstalled here" }
        check(version(current) <= BuildConfig.COMPAT_VERSION_CODE) { "The installed compatibility app is newer than the included version" }
        installAndActivate(force = true)
    }

    fun verifiedUninstallPackage(): String {
        check(BuildConfig.IS_FOSS && primaryUser) { "Manage compatibility from the primary Android user in the FOSS build" }
        check(!busy.get()) { "Wait for the current setup step to finish" }
        verifyCompanion()
        return PACKAGE
    }

    private fun verifyCompanion() {
        check(BuildConfig.IS_FOSS && primaryUser) { "Manage compatibility from the primary Android user in the FOSS build" }
        val current = installed() ?: error("Compatibility app is no longer installed")
        check(certificate(current) == certificate(pm.getPackageInfo(context.packageName, flags))) { "The installed app is not Porter Compatibility" }
    }

    fun uninstall() = operation(Phase.UNINSTALLING) {
        verifyCompanion()
        check(command(arrayOf("pm", "uninstall", "--user", "0", PACKAGE), null).startsWith("Success")) { "Compatibility app was not removed" }
        inspect()
        check(installed() == null) { "Compatibility app was not removed" }
    }

    fun afterAndroidUninstaller() = operation(Phase.CHECKING) {
        inspect()
        if (mutable.value.status == Status.MISSING) {
            mutable.update { it.copy(manualUninstallRequired = false) }
        }
    }

    fun consumeManualUninstall() { mutable.update { it.copy(manualUninstallRequired = false) } }

    private suspend fun installAndActivate(force: Boolean = false) {
        if (force || mutable.value.status != Status.INSTALLED || installed() == null) {
            mutable.update { it.copy(phase = Phase.INSTALLING) }
            val file = bundledApk()
            command(arrayOf("pm", "install", "-r", "--user", "0", "-S", file.length().toString()), file)
            file.delete()
        }
        inspect()
        check(mutable.value.status == Status.INSTALLED) { "Installed compatibility app could not be verified" }
        var applied = 0
        var skipped = 0
        if (snapshot.exists) {
            mutable.update { it.copy(phase = Phase.IMPORTING) }
            val result = request(CompatibilitySetup.APPLY_IMPORT, snapshot.read())
            applied = result.getInt("applied")
            skipped = result.getInt("skipped")
            snapshot.discard()
        }
        mutable.update { it.copy(phase = Phase.ACTIVATING) }
        request(CompatibilitySetup.REFRESH, null)
        mutable.update { it.copy(completed = true, applied = applied, skipped = skipped, hasSnapshot = false) }
    }

    fun discardImport() = operation(Phase.CHECKING) { snapshot.discard(); inspect() }

    fun bundledApk(): File {
        check(BuildConfig.IS_FOSS) { "Bundled installation is unavailable in this build" }
        val file = File(context.cacheDir, "compat/porter-compat.apk")
        file.parentFile!!.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            context.assets.open("compat/porter-compat.apk").use { it.copyTo(output) }
            atomic.finishWrite(output)
        } catch (e: Exception) { atomic.failWrite(output); throw e }
        val archive = pm.getPackageArchiveInfo(file.path, archiveFlags) ?: error("Invalid bundled APK")
        check(archive.packageName == PACKAGE && version(archive) == BuildConfig.COMPAT_VERSION_CODE.toLong() && ownsPermission(archive)
            && certificate(archive) == certificate(pm.getPackageInfo(context.packageName, flags))) { "Bundled APK identity does not match Porter" }
        return file
    }

    fun clearStagedApk() { File(context.cacheDir, "compat/porter-compat.apk").delete() }
    // refresh() polls every couple of seconds, and State compares its Bitmap by identity, so a
    // freshly decoded icon per pass would emit a new state and recompose on every tick.
    private fun icon(info: PackageInfo?): Bitmap? {
        val application = info?.applicationInfo
        if (info == null || application == null) {
            cachedIconKey = null
            cachedIcon = null
            return null
        }
        val key = "${info.packageName}:${version(info)}:${application.sourceDir}:${info.lastUpdateTime}:${info.firstInstallTime}"
        if (key != cachedIconKey) {
            cachedIcon = runCatching { application.loadIcon(pm).toBitmap(96, 96) }.getOrNull()
            cachedIconKey = key
        }
        return cachedIcon
    }
    private fun installed(): PackageInfo? = packageInfo(pm, PACKAGE)
    private fun decode(json: String): List<Decision> {
        check(json.toByteArray().size <= CompatibilitySetup.MAX_SNAPSHOT_BYTES)
        val array = JSONArray(json)
        val apps = runCatching { AuthorizationManager.discover().apps.associateBy { it.applicationInfo.packageName } }.getOrDefault(emptyMap())
        return (0 until array.length()).map {
            val item = array.getJSONObject(it)
            val name = item.getString("packageName")
            val label = runCatching { apps[name]?.applicationInfo?.loadLabel(pm)?.toString() ?: name }.getOrDefault(name)
            Decision(name, label, item.getInt("uid"), item.getInt("flags"), item)
        }
    }

    private fun persistResult() {
        val result = mutable.value
        writeAtomic(resultFile, JSONObject().put("completed", result.completed).put("error", result.error ?: "")
            .put("applied", result.applied).put("skipped", result.skipped).toString())
    }

    private fun operation(phase: Phase, block: suspend () -> Unit) {
        synchronized(busy) {
            if (!busy.compareAndSet(false, true)) return
            mutable.update { it.copy(phase = phase, error = null, completed = false, applied = 0, skipped = 0, stopRequired = false) }
        }
        scope.launch {
            mutex.withLock {
                try { block() }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val failedPhase = mutable.value.phase
                    runCatching { inspect() }
                    mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName,
                        manualUninstallRequired = failedPhase == Phase.UNINSTALLING && it.isCompanion,
                        stopRequired = failedPhase == Phase.STOPPING) }
                } finally {
                    runCatching { persistResult() }
                    synchronized(busy) {
                        busy.set(false)
                        mutable.update { it.copy(phase = null) }
                    }
                }
            }
        }
    }

    companion object {
        const val PACKAGE = "moe.shizuku.privileged.api"
        const val PERMISSION = "moe.shizuku.manager.permission.API_V23"
        val PACKAGE_FLAGS = PackageManager.GET_PERMISSIONS or if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Volatile private var instance: CompatibilityRepository? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: CompatibilityRepository(context.applicationContext).also { instance = it } }

        /** [owner] is the package defining [PERMISSION], null when no app defines it. */
        internal fun resolve(pm: PackageManager, owner: String?, ownCertificate: String): Resolution {
            val target = owner ?: PACKAGE
            val foreign = target != PACKAGE
            val companion = packageInfo(pm, PACKAGE)
            val isCompanion = companion != null && certificate(companion) == ownCertificate
            val status = when {
                foreign -> Status.CONFLICT
                companion == null -> Status.MISSING
                !isCompanion -> Status.CONFLICT
                !ownsPermission(companion) -> Status.INVALID
                BuildConfig.IS_FOSS && version(companion) < BuildConfig.COMPAT_VERSION_CODE -> Status.UPDATE
                else -> Status.INSTALLED
            }
            return Resolution(status, target, foreign, companion, if (foreign) packageInfo(pm, target) else companion, isCompanion)
        }

        private fun packageInfo(pm: PackageManager, packageName: String): PackageInfo? =
            try { pm.getPackageInfo(packageName, PACKAGE_FLAGS) } catch (_: PackageManager.NameNotFoundException) { null }
        private fun ownsPermission(info: PackageInfo) = info.permissions?.any {
            it.name == PERMISSION && (it.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE) == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
        } == true
        fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        fun certificate(info: PackageInfo): String {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") info.signatures
            check(!signatures.isNullOrEmpty()) { "Signing certificate unavailable" }
            return signatures.map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) } }.sorted().joinToString(",")
        }
        private fun writeAtomic(file: AtomicFile, text: String) {
            val out = file.startWrite()
            try { out.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
            catch (e: Exception) { file.failWrite(out); throw e }
        }
    }
}
