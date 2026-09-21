package eu.darken.porter.privileged

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import eu.darken.porter.common.CompatibilitySetup
import eu.darken.porter.privileged.util.Android17Compat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.UserManagerApis

internal class CompatibilitySetupHandler(
    private val config: ShizukuConfigManager,
    private val writer: Writer,
    private val refresh: Runnable,
) {
    fun interface Writer {
        @Throws(Exception::class)
        fun write(uid: Int, flags: Int)
    }

    @Throws(Exception::class)
    fun execute(operation: Int, json: String?): Bundle {
        val reply = Bundle()
        reply.putInt("version", CompatibilitySetup.VERSION)
        if (operation == CompatibilitySetup.INSPECT) {
            val users: Collection<Int> = UserManagerApis.getUserIdsNoThrow()
            if (users.isEmpty()) throw IllegalStateException("Cannot check Android users")
            val installed = ArrayList<Int>()
            for (user in users) {
                val pi = Android17Compat.getPackageInfo(ServerConstants.COMPAT_APPLICATION_ID, PACKAGE_FLAGS.toLong(), user)
                val applicationInfo = pi?.applicationInfo
                if (applicationInfo != null && (applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED) != 0) installed.add(user)
            }
            reply.putIntArray("users", installed.toIntArray())
            val pi = Android17Compat.getPackageInfo(ServerConstants.COMPAT_APPLICATION_ID, PACKAGE_FLAGS.toLong(), 0)
            reply.putString("certificate", if (pi == null) null else certificate(pi))
            reply.putBoolean("available", Compatibility.isAvailable())
        } else if (operation == CompatibilitySetup.PREVIEW_IMPORT) {
            val original = Android17Compat.getPackageInfo(ServerConstants.COMPAT_APPLICATION_ID, PACKAGE_FLAGS.toLong(), 0)
            if (original == null || Compatibility.isAvailable() || original.permissions == null)
                throw IllegalStateException("Original Shizuku is unavailable")
            var definesLegacy = false
            for (permission in original.permissions) {
                if (ServerConstants.LEGACY_PERMISSION == permission.name
                    && ServerConstants.COMPAT_APPLICATION_ID == permission.packageName
                    && (permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS)
                    definesLegacy = true
            }
            if (!definesLegacy) throw IllegalStateException("Installed app does not own the Shizuku permission")
            val source = File("/data/user_de/0/com.android.shell/shizuku.json")
            val backup = File(source.path + ".bak")
            FileInputStream(if (backup.exists()) backup else source).use { input ->
                reply.putString("decisions", LegacyAccessImport.encode(LegacyAccessImport.preview(readBounded(input), resolver)))
            }
        } else if (operation == CompatibilitySetup.APPLY_IMPORT) {
            if (!Compatibility.isAvailable()) throw IllegalStateException("Compatibility support is not verified")
            if (json == null || json.toByteArray(StandardCharsets.UTF_8).size > CompatibilitySetup.MAX_SNAPSHOT_BYTES)
                throw IllegalArgumentException("Invalid access snapshot")
            var applied = 0
            var skipped = 0
            for (decision in LegacyAccessImport.decode(json)) {
                if (!LegacyAccessImport.canApply(decision, resolver)) {
                    skipped++
                    continue
                }
                writer.write(decision.uid, decision.flags)
                applied++
            }
            config.persistImport()
            reply.putInt("applied", applied)
            reply.putInt("skipped", skipped)
            refresh.run()
        } else if (operation == CompatibilitySetup.REFRESH) {
            if (!Compatibility.isAvailable()) throw IllegalStateException("Compatibility support is not verified")
            refresh.run()
        } else throw IllegalArgumentException("Unknown compatibility operation")
        return reply
    }

    private val resolver: LegacyAccessImport.Resolver = object : LegacyAccessImport.Resolver {
        override fun hasPorterDecision(uid: Int): Boolean = config.find(uid) != null

        @Throws(Exception::class)
        override fun resolve(packageName: String): LegacyAccessImport.App? {
            val pi = Android17Compat.getPackageInfo(packageName, PACKAGE_FLAGS.toLong(), 0)
            val applicationInfo = pi?.applicationInfo ?: return null
            val app = LegacyAccessImport.App()
            app.uid = applicationInfo.uid
            app.packageName = packageName
            app.label = packageName
            app.certificate = certificate(pi)
            val names = PackageManagerApis.getPackagesForUidNoThrow(app.uid)
            app.exclusiveUid = names.size == 1 && names.contains(packageName)
            app.legacy = ClientRouting.requests(pi.requestedPermissions, ServerConstants.LEGACY_PERMISSION)
            app.granted = Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, packageName, 0) == PackageManager.PERMISSION_GRANTED
            return app
        }
    }

    private companion object {
        private val PACKAGE_FLAGS = PackageManager.GET_PERMISSIONS or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)

        @Throws(Exception::class)
        private fun certificate(info: PackageInfo): String {
            val signatures = Compatibility.signatures(info)
            if (signatures == null || signatures.isEmpty()) throw IllegalStateException("Missing signing certificate")
            val hashes = ArrayList<String>()
            for (signature in signatures) {
                val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                val value = StringBuilder()
                for (b in digest) value.append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xff))
                hashes.add(value.toString())
            }
            hashes.sort()
            return TextUtils.join(",", hashes)
        }

        @Throws(Exception::class)
        private fun readBounded(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                if (bytes.size() + read > CompatibilitySetup.MAX_SNAPSHOT_BYTES) throw IllegalArgumentException("Access database is too large")
                bytes.write(buffer, 0, read)
            }
            return bytes.toString("UTF-8")
        }
    }
}
