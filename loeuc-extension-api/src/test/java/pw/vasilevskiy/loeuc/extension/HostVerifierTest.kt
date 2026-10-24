package pw.vasilevskiy.loeuc.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostVerifierTest {

    private val hostUid = 10_100
    private val impostorUid = 10_200
    private val packages = mapOf(
        hostUid to listOf(ExtensionApi.HOST_PACKAGE),
        impostorUid to listOf("com.example.impostor"),
    )

    private fun verifier(signedWith: String?, acceptAnyHostBuild: Boolean = false, lookups: MutableList<String>? = null) =
        HostVerifier(
            packagesForUid = { uid -> packages[uid].orEmpty() },
            hasCertificate = { name, digest ->
                lookups?.add(name)
                signedWith != null && digest.contentEquals(signedWith.hexToBytes())
            },
            trustedCertificates = LoEUCHostCertificates.RELEASE,
            acceptAnyHostBuild = acceptAnyHostBuild,
        )

    @Test
    fun trustsLoEUCSignedWithAReleaseCertificate() {
        assertTrue(verifier(LoEUCHostCertificates.UPLOAD).isHost(hostUid))
    }

    @Test
    fun trustsLoEUCInstalledFromPlay() {
        assertTrue(verifier(LoEUCHostCertificates.PLAY).isHost(hostUid))
    }

    @Test
    fun refusesTheHostPackageNameSignedWithAnotherKey() {
        // The package name is not an identity: anyone can install an APK under it.
        assertFalse(verifier("00".repeat(32)).isHost(hostUid))
    }

    @Test
    fun refusesOtherPackagesWhateverTheyAreSignedWith() {
        assertFalse(verifier(LoEUCHostCertificates.UPLOAD).isHost(impostorUid))
        assertFalse(verifier(LoEUCHostCertificates.UPLOAD, acceptAnyHostBuild = true).isHost(impostorUid))
    }

    @Test
    fun debugHostSkipsOnlyTheCertificate() {
        assertTrue(verifier("00".repeat(32), acceptAnyHostBuild = true).isHost(hostUid))
    }

    @Test
    fun verdictIsCachedPerUidUntilReset() {
        val lookups = mutableListOf<String>()
        val verifier = verifier(LoEUCHostCertificates.UPLOAD, lookups = lookups)
        assertTrue(verifier.isHost(hostUid))
        val probesPerVerdict = lookups.size
        repeat(3) { verifier.isHost(hostUid) }
        assertEquals(probesPerVerdict, lookups.size)
        verifier.reset()
        verifier.isHost(hostUid)
        assertEquals(2 * probesPerVerdict, lookups.size)
    }

    @Test
    fun releaseCertificatesAreSha256Hex() {
        LoEUCHostCertificates.RELEASE.forEach { digest ->
            assertTrue(digest, Regex("[0-9a-f]{64}").matches(digest))
        }
    }
}
