package com.abid.handlebarlink.carplay.protocol

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * ChaCha20-Poly1305 sealing by BouncyCastle's own engine, independent of the JCA cipher under test:
 * ciphertext followed by the 16-byte tag.
 */
internal fun bouncyCastleSeal(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    plaintext: ByteArray,
): ByteArray {
    val aead = ChaCha20Poly1305()
    aead.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
    val out = ByteArray(aead.getOutputSize(plaintext.size))
    val n = aead.processBytes(plaintext, 0, plaintext.size, out, 0)
    aead.doFinal(out, n)
    return out
}

/** Runs [block], which must not really suspend (its read functions answer at once), and returns its result. */
internal fun <T> runWithoutSuspending(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "The block suspended" }.getOrThrow()
}
