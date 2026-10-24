package pw.vasilevskiy.loeuc.extension

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * SHA-256 digests of the certificates LoEUC is signed with.
 *
 * Two release certificates are in circulation: the Play app-signing key, which every install
 * from Google Play carries, and the upload key, which builds distributed outside Play carry.
 * The list only grows. Removing an entry, or a LoEUC key change without a new entry here,
 * makes every published extension stop trusting the host.
 */
object LoEUCHostCertificates {
    /** The Play app-signing key, held by Google: every install from Google Play carries it. */
    const val PLAY = "49f62829eb587ed6d9307010145e9265212cb2cec02059fe26d8e733b8c916dd"

    /** CN=LoEUC Upload, OU=LoEUC, O=LoEUC, L=Moscow, ST=Moscow, C=RU: builds distributed outside Play. */
    const val UPLOAD = "2029308c693efdbf2c3fa8cac34ad8f5312d0fb29ed45f94a543bc10fe581074"

    val RELEASE: Set<String> = setOf(PLAY, UPLOAD)
}

/**
 * Decides whether a binder call comes from LoEUC.
 *
 * The bind permission alone is not enough. A signature permission is granted to whoever is
 * signed like the app that *declared* it, and an app installed before LoEUC can declare it
 * first and grant it to itself. So every incoming call is checked against the certificates
 * LoEUC is known to be signed with. The check runs per call, not in `onBind`: `onBind` is
 * invoked by the system, and the calling uid there is not the client's.
 */
class HostVerifier internal constructor(
    private val packagesForUid: (Int) -> List<String>,
    private val hasCertificate: (packageName: String, sha256: ByteArray) -> Boolean,
    private val trustedCertificates: Set<String>,
    /** Accept any build of LoEUC by package name alone - see [LoEUCExtensionService.acceptDebugHost]. */
    private val acceptAnyHostBuild: Boolean,
) {
    private val verdicts = HashMap<Int, Boolean>()

    fun isHost(uid: Int): Boolean = synchronized(verdicts) {
        verdicts.getOrPut(uid) { decide(uid) }
    }

    /** Forgets earlier verdicts, for when packages were installed or replaced. */
    fun reset() = synchronized(verdicts) { verdicts.clear() }

    private fun decide(uid: Int): Boolean {
        val hostPackages = packagesForUid(uid).filter { it == ExtensionApi.HOST_PACKAGE }
        if (hostPackages.isEmpty()) return false
        if (acceptAnyHostBuild) return true
        return hostPackages.any { name ->
            trustedCertificates.any { digest -> hasCertificate(name, digest.hexToBytes()) }
        }
    }

    companion object {
        /**
         * The verifier for a running extension. [acceptDebugHost] takes effect only when the
         * extension itself is a debuggable build, so a release that forgot to turn it off
         * still checks certificates.
         */
        fun create(context: Context, acceptDebugHost: Boolean): HostVerifier {
            val packageManager = context.packageManager
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            return HostVerifier(
                packagesForUid = { uid -> packageManager.getPackagesForUid(uid)?.toList().orEmpty() },
                hasCertificate = { name, digest ->
                    packageManager.hasSigningCertificate(name, digest, PackageManager.CERT_INPUT_SHA256)
                },
                trustedCertificates = LoEUCHostCertificates.RELEASE,
                acceptAnyHostBuild = acceptDebugHost && debuggable,
            )
        }
    }
}

internal fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
