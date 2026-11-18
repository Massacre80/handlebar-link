package com.abid.handlebarlink.carplay.engine.android.credentials

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.prop
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.android.quietLogger
import com.abid.handlebarlink.carplay.protocol.pairing.InMemoryPairingStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom

class CredentialsTest {
    @get:Rule val folder = TemporaryFolder()

    /** The throwaway test identity tracked in :carplay:protocol (see its README); never the owner's files. */
    private val testMfi = File("../protocol/src/test/resources/mfi")

    private fun key(index: Int) = ByteArray(32) { index.toByte() }

    private fun port(
        mfi: MfiSource,
        testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ) = AndroidCredentialsPort(
        mfi = mfi,
        identities = IdentityStore({ folder.root.resolve("identity") }, SecureRandom(), quietLogger()),
        pairings = { InMemoryPairingStore() },
        io = StandardTestDispatcher(testScheduler),
        log = quietLogger(),
    )

    @Test
    fun `a build without the MFi files reports IdentityUnavailable`() =
        runTest {
            assertFailure { port({ null }, testScheduler).load() }
                .isInstanceOf<CarPlayException>()
                .prop(CarPlayException::error)
                .isInstanceOf<CarPlayError.IdentityUnavailable>()
        }

    @Test
    fun `only one of the two files is also IdentityUnavailable`() =
        runTest {
            val keyOnly =
                MfiSource { name ->
                    if (name ==
                        AssetMfiSource.KEY_FILE
                    ) {
                        testMfi.resolve("test-identity.pk8").readBytes()
                    } else {
                        null
                    }
                }
            assertFailure { port(keyOnly, testScheduler).load() }
                .isInstanceOf<CarPlayException>()
                .prop(CarPlayException::error)
                .isInstanceOf<CarPlayError.IdentityUnavailable>()
        }

    @Test
    fun `a key that does not match the certificate reports IdentityUnavailable`() =
        runTest {
            val mismatched =
                MfiSource { name ->
                    when (name) {
                        AssetMfiSource.KEY_FILE -> testMfi.resolve("other-identity.pk8").readBytes()
                        else -> testMfi.resolve("test-certificate.p7b").readBytes()
                    }
                }
            assertFailure { port(mismatched, testScheduler).load() }
                .isInstanceOf<CarPlayException>()
                .prop(CarPlayException::error)
                .isInstanceOf<CarPlayError.IdentityUnavailable>()
        }

    @Test
    fun `the test identity loads and its certificate is sent byte for byte`() =
        runTest {
            val files =
                MfiSource { name ->
                    when (name) {
                        AssetMfiSource.KEY_FILE -> testMfi.resolve("test-identity.pk8").readBytes()
                        AssetMfiSource.CERTIFICATE_FILE -> testMfi.resolve("test-certificate.p7b").readBytes()
                        else -> null
                    }
                }
            val port = port(files, testScheduler)
            val first = port.load()
            assertThat(
                first.mfi.certificate.toList(),
            ).isEqualTo(testMfi.resolve("test-certificate.p7b").readBytes().toList())
            assertThat(port.load().pairings).isSameInstanceAs(first.pairings)
        }

    @Test
    fun `the AirPlay identity is created once and read back after a restart`() {
        val file = folder.root.resolve("carplay/airplay-identity")
        val first = IdentityStore({ file }, SecureRandom(), quietLogger()).load()
        val again = IdentityStore({ file }, SecureRandom(), quietLogger()).load()
        assertThat(again.pairingId).isEqualTo(first.pairingId)
        assertThat(again.keys.publicKey.toList()).isEqualTo(first.keys.publicKey.toList())
    }

    @Test
    fun `an unreadable identity file is replaced by a new identity`() {
        val file = folder.root.resolve("airplay-identity").apply { writeText("garbage") }
        val store = IdentityStore({ file }, SecureRandom(), quietLogger())
        assertThat(store.peek()).isNull()
        val identity = store.load()
        assertThat(
            IdentityStore({ file }, SecureRandom(), quietLogger()).load().pairingId,
        ).isEqualTo(identity.pairingId)
    }

    private fun pairings(capacity: Int = 3) =
        FilePairingStore(folder.root.resolve("pairings"), capacity, {
            it.run()
        }, quietLogger())

    @Test
    fun `pairings survive a restart`() {
        pairings().save("iphone-1", key(1))
        assertThat(pairings().get("iphone-1")?.toList()).isEqualTo(key(1).toList())
        assertThat(pairings().get("iphone-2")).isNull()
    }

    @Test
    fun `one pairing more than the cap evicts the oldest, also on disk`() {
        val store = pairings(capacity = 3)
        (1..4).forEach { store.save("iphone-$it", key(it)) }
        assertThat(store.ids()).containsExactly("iphone-2", "iphone-3", "iphone-4")
        assertThat(pairings(capacity = 3).ids()).containsExactly("iphone-2", "iphone-3", "iphone-4")
    }

    @Test
    fun `saving an iPhone again makes it the newest, so it is evicted last`() {
        val store = pairings(capacity = 3)
        (1..3).forEach { store.save("iphone-$it", key(it)) }
        store.save("iphone-1", key(9))
        store.save("iphone-4", key(4))
        assertThat(store.ids()).containsExactly("iphone-3", "iphone-1", "iphone-4")
        assertThat(store.get("iphone-1")?.toList()).isEqualTo(key(9).toList())
    }

    @Test
    fun `a file holding more than the cap is trimmed when loaded`() {
        pairings(capacity = 5).apply { (1..5).forEach { save("iphone-$it", key(it)) } }
        assertThat(pairings(capacity = 2).ids()).containsExactly("iphone-4", "iphone-5")
    }

    @Test
    fun `broken lines in the pairing file are skipped and a returned key is a copy`() {
        folder.root.resolve("pairings").writeText("zz 00\n" + "69 " + "00".repeat(31) + "\n")
        val store = pairings()
        assertThat(store.ids()).isEqualTo(emptyList())
        store.save("iphone-1", key(1))
        store.get("iphone-1")!![0] = 42
        assertThat(store.get("iphone-1")!![0]).isNotEqualTo(42.toByte())
    }
}
