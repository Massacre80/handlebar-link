# Spec C — CarPlay/AirPlay pairing, auth-setup and channel crypto (extracted from DiPlay source)

Source root: `/Volumes/My files/pocof1/carlink-src/vendor/diplay`
Abbreviation used below: `AP/` = `shared/src/main/java/com/shilapi/xcertplay/airplay/`

Only source code was read (no docs/research, no notes). Every constant is cited `file:line`.
Roles: **accessory = Android receiver (server)**, **controller = iPhone (client)**.

Crypto primitives (all from `AP/AirPlayCrypto.kt`):

| Primitive | Implementation | Cite |
|---|---|---|
| SHA-512 | BouncyCastle `SHA512Digest`, `sha512(vararg parts)` = hash of the plain concatenation | AirPlayCrypto.kt:126, 238-243 |
| HKDF | RFC 5869 HKDF with SHA-512 (`HKDFBytesGenerator(SHA512Digest())`), **default L = 32 bytes** | AirPlayCrypto.kt:113-124 |
| AEAD | ChaCha20-Poly1305 (RFC 8439), 12-byte nonce, 16-byte tag appended to ciphertext (`MAC_BITS = 128`) | AirPlayCrypto.kt:26-27, 128-146, 190-217 |
| X25519 | BouncyCastle `X25519PrivateKeyParameters`, raw 32-byte keys, shared = raw 32-byte output (no hashing) | AirPlayCrypto.kt:79-90 |
| Ed25519 | BouncyCastle `Ed25519Signer` (pure Ed25519, no prehash), raw 32-byte keys, 64-byte signatures | AirPlayCrypto.kt:92-111 |

Nonce constructors (AirPlayCrypto.kt:219-236):

```kotlin
/** 12-byte nonce: four zero bytes followed by an eight-byte little-endian counter. */
fun nonce64(counter: Long): ByteArray {
    val nonce = ByteArray(NONCE_SIZE)
    var value = counter
    for (index in 4 until NONCE_SIZE) { nonce[index] = value.toByte(); value = value ushr 8 }
    return nonce
}
/** 12-byte nonce from an eight-byte ASCII label placed after four zero bytes. */
fun nonceLabel(label: String): ByteArray {
    val nonce = ByteArray(NONCE_SIZE)
    val ascii = label.asciiBytes()
    ascii.copyInto(nonce, 4, 0, minOf(ascii.size, LABEL_SIZE))   // LABEL_SIZE = 8
    return nonce
}
```

So: `nonce64(c) = 00 00 00 00 || LE64(c)`; `nonceLabel("PV-Msg02") = 00 00 00 00 || "PV-Msg02"` (label is **left-padded with 4 zero bytes**, i.e. occupies bytes 4..11).

AEAD AAD: when none is passed it is empty (`aad: ByteArray = ByteArray(0)`, AirPlayCrypto.kt:132,142); the platform path only calls `updateAAD` if non-empty (AirPlayCrypto.kt:164).

---

## 1. TLV8

File `AP/Tlv8Codec.kt`.

Item = `[type:u8][length:u8][value: length bytes]`.

Constants: `SEPARATOR_TYPE = 0xff` (:7), `MAX_FRAGMENT = 255` (:8), decode caps `MAX_REASSEMBLED_BYTES = 64*1024` (:12), `MAX_TOTAL_FRAGMENTS = 1024` (:13).

### Encode (:15-32)
```kotlin
for (item in items) {
    if (previousType == item.type) chunks.add(byteArrayOf(SEPARATOR_TYPE.toByte(), 0))
    var offset = 0
    do {
        val length = minOf(MAX_FRAGMENT, item.value.size - offset)
        chunks.add(byteArrayOf(item.type.toByte(), length.toByte()))
        chunks.add(item.value.copyOfRange(offset, offset + length))
        offset += length
    } while (offset < item.value.size)
    previousType = item.type
}
```
- Values > 255 bytes are split into consecutive fragments of the same type: 255, 255, ..., remainder. A value whose length is an exact multiple of 255 ends with a 255-byte fragment (no trailing 0-length fragment).
- Empty value encodes as `[type][00]` (do-while runs once).
- Two consecutive items of the same type get a `FF 00` separator between them.
- Items are emitted in the list order given by the caller (orders given per message below).

### Decode (:34-75)
- Reads `[type][len]`, stops silently if truncated (:48).
- **Continuation rule:** if `type == lastType && lastLength == 255` the value is appended to the previous one (:51-57); otherwise the item *replaces* any earlier value of that type ("last value wins", :58-67).
- Returns `Map<Int, ByteArray>`; order is not preserved; separators (type 0xff) just become a map entry.
- Hitting either cap stops decoding and returns the partial map (no exception).

### Type IDs used (PairSetup.kt:116-124, PairVerify.kt:112-117)

| Type | Name | Used |
|---|---|---|
| 0x00 | Method | **declared but never read or written** (PairSetup.kt:116) |
| 0x01 | Identifier | yes |
| 0x02 | Salt | yes |
| 0x03 | PublicKey | yes |
| 0x04 | Proof | yes |
| 0x05 | EncryptedData | yes |
| 0x06 | State | yes |
| 0x07 | Error | yes (value 0x02 = Authentication, PairSetup.kt:127, PairVerify.kt:118) |
| 0x0a | Signature | yes |
| 0x13 Flags, 0x09 Permissions, etc. | — | **not used anywhere** |

Error response (both PairSetup.kt:108-113 and PairVerify.kt:104-109): TLV `[06 01 <state>][07 01 02]`, where `<state>` is the *expected reply state* (2/4/6) or, for an unknown/missing state, the received state value (or 0). It is returned inside a normal 200 response (see §2.0).

---

## 2. /pair-setup

### 2.0 Transport / dispatch (AirPlaySession.kt:586-593, 992; RtspMessage.kt:93-107)
- Any request whose lower-cased path ends with `/pair-setup` → `pairSetup.handle(request.body)`; `/pair-verify` → `pairVerify.handle(...)`. Method is not checked (in practice `POST`).
- Response: status **200 OK** always (even for TLV errors), header `Content-Type: application/pairing+tlv8` (AirPlaySession.kt:992), `CSeq` echoed, `Content-Length`; protocol string echoes the request's (`RTSP/1.0` or `HTTP/1.1`).
- Request Content-Type is not checked.
- One `PairSetup` / `PairVerify` instance per control connection (AirPlaySession.kt:84-85).

### 2.1 Variant
**Full (non-transient) SRP pair-setup, M1..M6**, with a **fixed PIN `"3939"`** (PairSetup.kt:126). The Method TLV and any Flags TLV in M1 are ignored; there is no transient branch (no `kTLVType_Flags` handling, no `state==1 && flags==transient` path, no early session keys after M4). Dispatch is purely on the State TLV (PairSetup.kt:19-32):
```kotlin
when (state) { 1 -> m2(); 3 -> m4(tlv); 5 -> m6(tlv); else -> err(state ?: 0) }
```
Any exception → `err(state)` (i.e. echoes the *received* state, not received+1 — note).

### 2.2 Messages

| Step | Direction | TLVs (in emission order) | Cite |
|---|---|---|---|
| M1 | iPhone → us | State=1 (+ Method, ignored) | PairSetup.kt:24 |
| M2 | us → iPhone | `06:State=02`, `03:PublicKey=B (384 bytes, left-zero-padded)`, `02:Salt (16 bytes)` | PairSetup.kt:34-44 |
| M3 | iPhone → us | State=3, `03:PublicKey=A`, `04:Proof=M1 (64 bytes)` | PairSetup.kt:46-50 |
| M4 | us → iPhone | `06:State=04`, `04:Proof=M2 (64 bytes)`; on failure `[State=4][Error=2]` | PairSetup.kt:51-60 |
| M5 | iPhone → us | State=5, `05:EncryptedData` | PairSetup.kt:62-65 |
| M6 | us → iPhone | `06:State=06`, `05:EncryptedData`; on failure `[State=6][Error=2]` | PairSetup.kt:100-105 |

Note the M2 order is State, PublicKey, Salt (B before salt). B is 384 bytes so it is fragmented 255+129 under type 0x03.

Every M1 creates a fresh SRP session (new salt, new b) (PairSetup.kt:35-36).

### 2.3 SRP-6a parameters (`AP/Srp6a.kt`)

| Parameter | Value | Cite |
|---|---|---|
| Group | RFC 5054 3072-bit (N_HEX; verified here: 768 hex chars = 3072 bits, passes Fermat test, (N-1)/2 also passes → safe prime) | Srp6a.kt:48-61 |
| `N_BYTES` (pad width) | 384 | Srp6a.kt:63 |
| g | 5 | Srp6a.kt:17 |
| Hash | SHA-512 | Srp6a.kt:18 etc. |
| Username I | `"Pair-Setup"` (UTF-8) | PairSetup.kt:125; Srp6a.kt:22 |
| Password P | `"3939"` (UTF-8) | PairSetup.kt:126; Srp6a.kt:23 |
| Salt length | 16 random bytes (SecureRandom) | Srp6a.kt:13, 21 |
| Private b | 32 random bytes interpreted as unsigned big-endian | Srp6a.kt:14, 26 |

Note: the N_HEX source lines have irregular lengths (64,64,64,64,64,64,65,64,65,64,64,62 chars) because the RFC hex was re-wrapped, but the concatenation is the correct 768-char RFC 5054 3072-bit prime.

Byte helpers (Srp6a.kt:31-43):
```kotlin
internal fun toBytes(value: BigInteger): ByteArray {      // minimal unsigned big-endian (strip sign byte)
    if (value.signum() == 0) return byteArrayOf(0)
    val encoded = value.toByteArray()
    return if (encoded.size > 1 && encoded[0] == 0.toByte()) encoded.copyOfRange(1, encoded.size) else encoded
}
internal fun toBigInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes)
internal fun pad(value: BigInteger): ByteArray {          // left-zero-pad to 384 bytes
    val bytes = toBytes(value)
    if (bytes.size >= N_BYTES) return bytes
    return ByteArray(N_BYTES).also { bytes.copyInto(it, N_BYTES - bytes.size) }
}
```
(`toBytes` only strips the Java sign byte; it does not strip further leading zeros, but BigInteger.toByteArray never has more than one, so it is the minimal encoding.)

Formulas (all `H = SHA-512`, `|` = concatenation):

| Quantity | Formula | Code | Cite |
|---|---|---|---|
| k | `H(N | PAD(g))` — N is already 384 bytes; g padded to 384 bytes | `toBigInt(sha512(toBytes(n), pad(g)))` | Srp6a.kt:18 |
| x | `H(s | H(I | ":" | P))` | `toBigInt(sha512(salt, sha512(identifier, COLON, pass)))` | Srp6a.kt:24, 64 |
| v | `g^x mod N` | `g.modPow(x, n)` | Srp6a.kt:25 |
| B | `(k·v + g^b) mod N` | `(multiplier * verifier + g.modPow(privateExponent, n)).mod(n)` | Srp6a.kt:27 |
| B on wire | `PAD(B)` (384 bytes) | `val publicKey get() = Srp6a.pad(publicKeyB)` | Srp6a.kt:80 |
| A check | reject if `A mod N == 0` | `if (a.mod(n).signum() == 0) return Srp6aResult(false)` | Srp6a.kt:85 |
| u | `H(PAD(A) | PAD(B))` | `toBigInt(sha512(pad(a), pad(publicKeyB)))` | Srp6a.kt:87 |
| S | `(A · v^u mod N)^b mod N` | `(a * verifier.modPow(u, n)).mod(n).modPow(privateExponent, n)` | Srp6a.kt:88 |
| K | `H(S)` with **S NOT padded** (minimal big-endian) → 64 bytes | `sha512(Srp6a.toBytes(s))` | Srp6a.kt:89 |
| H(N) | SHA-512 of the 384-byte N | `sha512(toBytes(n))` | Srp6a.kt:91 |
| H(g) | SHA-512 of the **single byte 0x05** (g NOT padded) | `sha512(toBytes(generator()))` | Srp6a.kt:92 |
| M1 (expected client proof) | `H( (H(N) xor H(g)) | H(I) | s | PAD(A) | PAD(B) | K )` | see below | Srp6a.kt:93-104 |
| M2 (server proof) | `H( PAD(A) | M1 | K )` where M1 = the client's received proof bytes | `sha512(pad(a), clientProof, sessionKey)` | Srp6a.kt:107 |

```kotlin
val hashXor = ByteArray(hashN.size)
for (index in hashN.indices) hashXor[index] = (hashN[index].toInt() xor hashG[index].toInt()).toByte()
val expected = AirPlayCrypto.sha512(hashXor, AirPlayCrypto.sha512(identifier), salt,
                                    Srp6a.pad(a), Srp6a.pad(publicKeyB), sessionKey)
if (!expected.contentEquals(clientProof)) return Srp6aResult(false)
```

Padding summary: A and B are padded to 384 bytes in u, M1 and M2; g is padded in k but NOT in H(g); S is NOT padded before K = H(S); x is computed with the 16-byte salt as-is.

SRP oddities:
- No `u == 0` check.
- M1 comparison is `contentEquals` (not constant-time).
- `A` is accepted at any length; `pad(A)` reproduces the iPhone's exact 384-byte A only if the iPhone sent 384 bytes (it does in practice). **Correction (review 2026-11-19):** Apple's own accessory code strips the leading zero bytes of A and B in M1
  (HomeKitADK `PAL/Crypto/OpenSSL/HAPOpenSSL.c` `HAP_srp_proof_m1`, `Count_Leading_Zeroes`) while u and M2 use the
  padded 384 bytes. DiPlay's always-padded M1 therefore differs whenever A or B starts with 0x00 (~1/128). Our
  `Srp6aServer` accepts either form.
- The PIN is hardcoded — the iPhone is never shown a PIN (CarPlay accessories use a fixed setup code).

### 2.4 M5/M6 encrypted exchange (PairSetup.kt:62-106)

Session key K = 64-byte SRP K from §2.3.

```kotlin
val encryptionKey = hkdfSha512(key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")       // 32 bytes
val sub = Tlv8Codec.decode(chachaOpen(encryptionKey, nonceLabel("PS-Msg05"), encrypted))       // no AAD
```
M5 inner TLV must contain `01:Identifier` (iPhone pairing id, UTF-8 string), `03:PublicKey` (iPhone Ed25519 LTPK, 32 B), `0a:Signature` (64 B) (:69-72).

Verification (:74-81):
```kotlin
val controllerSignKey = hkdfSha512(K, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")  // 32 B
val controllerSignData = concatBytes(controllerSignKey, controllerId, controllerLtpk)
ed25519Verify(controllerLtpk, controllerSignData, controllerSignature)
pairings.save(controllerId.toString(Charsets.UTF_8), controllerLtpk)
```
Signed by iPhone: `iOSDeviceX(32) | iOSDevicePairingID | iOSDeviceLTPK(32)`.

Our M6 (:83-105):
```kotlin
val accessoryId = identity.pairingId.toByteArray(Charsets.UTF_8)            // UUID string, 36 ASCII bytes
val accessorySignKey = hkdfSha512(K, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")  // 32 B
val accessorySignData = concatBytes(accessorySignKey, accessoryId, identity.publicKey)
val accessorySignature = ed25519Sign(identity.privateKey, accessorySignData)
val subResponse = TLV8[ 01:accessoryId, 03:identity.publicKey (32), 0a:accessorySignature (64) ]
val sealed = chachaSeal(encryptionKey, nonceLabel("PS-Msg06"), subResponse)   // no AAD; ciphertext||tag
reply TLV8[ 06:State=06, 05:sealed ]
```
`complete = true` after M6 (:99) — not otherwise used for gating in the code read.

Strings (PairSetup.kt:128-135): `"Pair-Setup-Encrypt-Salt"`, `"Pair-Setup-Encrypt-Info"`, `"Pair-Setup-Controller-Sign-Salt"`, `"Pair-Setup-Controller-Sign-Info"`, `"Pair-Setup-Accessory-Sign-Salt"`, `"Pair-Setup-Accessory-Sign-Info"`, `"PS-Msg05"`, `"PS-Msg06"`. All encoded US-ASCII (`asciiBytes()`, Bytes.kt:13).

## 3. Keys derived after pair-setup

| Key | IKM | Salt | Info | Len | Use |
|---|---|---|---|---|---|
| Setup encrypt key | SRP K (64 B) | `Pair-Setup-Encrypt-Salt` | `Pair-Setup-Encrypt-Info` | 32 | ChaCha20-Poly1305 for M5 (nonce `PS-Msg05`) and M6 (nonce `PS-Msg06`) |
| iOSDeviceX | SRP K | `Pair-Setup-Controller-Sign-Salt` | `Pair-Setup-Controller-Sign-Info` | 32 | prefix of the data the iPhone signs |
| AccessoryX | SRP K | `Pair-Setup-Accessory-Sign-Salt` | `Pair-Setup-Accessory-Sign-Info` | 32 | prefix of the data we sign |

Nothing from pair-setup is used after M6 except the persisted (controllerId → LTPK) pairing (PairingStore.kt:7-11; persisted via `AirPlayPersistence.savePairing`, common/.../AirPlayPersistence.kt:1569-1577 as hex under `"pairing.<id>"` plus id set `"pairing_ids"`). No control-channel keys come from pair-setup (not transient).

## 4. /pair-verify (`AP/PairVerify.kt`)

Dispatch on State (:28-40): `1 -> m2(tlv)`, `3 -> m4(tlv)`, else `err(state)`; exception → `err(state)`.

### M1 (iPhone → us)
`06:State=1`, `03:PublicKey` = iPhone ephemeral X25519 public key (32 B). Missing PK → `err(2)` (:43). Method/other TLVs ignored. **No pairing lookup and no rejection of unknown iPhones happens at M1/M2.**

### M2 (us → iPhone) (:42-72)
```kotlin
val pair = x25519Generate()                                   // our ephemeral
val shared = x25519Shared(pair.privateKey, clientEphemeralPublicKey)   // 32 B raw, no all-zero check
val encryptionKey = hkdfSha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")  // 32 B
val accessoryId = identity.pairingId.toByteArray(UTF_8)
val signature = ed25519Sign(identity.privateKey,
                            concatBytes(pair.publicKey, accessoryId, clientEphemeralPublicKey))
val sub = TLV8[ 01:accessoryId, 0a:signature ]
val sealed = chachaSeal(encryptionKey, nonceLabel("PV-Msg02"), sub)     // no AAD
reply TLV8[ 06:State=02, 03:pair.publicKey (32), 05:sealed ]
```
We sign: `AccessoryEphPK(32) | AccessoryPairingID(UTF-8 UUID) | iPhoneEphPK(32)` with our long-term Ed25519 key.

### M3 (iPhone → us) (:74-94)
`06:State=3`, `05:EncryptedData`. Decrypt with same `encryptionKey`, nonce `nonceLabel("PV-Msg03")`, no AAD. Inner TLV: `01:Identifier` (iPhone pairing id), `0a:Signature`.
```kotlin
val controllerLtpk = pairings.get(controllerId.toString(Charsets.UTF_8)) ?: return err(4)
val signatureData = concatBytes(clientEphemeralPublicKey, controllerId, ownEphemeralPublicKey)
if (!ed25519Verify(controllerLtpk, signatureData, controllerSignature)) return err(4)
```
iPhone signs: `iPhoneEphPK(32) | iPhonePairingID | AccessoryEphPK(32)`.

### M4 (us → iPhone) (:96-101)
```kotlin
val readKey  = hkdfSha512(shared, "Control-Salt", "Control-Write-Encryption-Key")   // decrypts iPhone→us
val writeKey = hkdfSha512(shared, "Control-Salt", "Control-Read-Encryption-Key")    // encrypts us→iPhone
keys = ControlKeys(readKey, writeKey); verified = true
reply TLV8[ 06:State=04 ]
```
The M4 reply itself is sent **in plaintext**; the cipher is installed immediately after writing it (AirPlaySession.kt:533-538):
```kotlin
output.write(cipher?.encrypt(wire) ?: wire)
if (cipher == null && pairVerify.controlKeys != null) {
    val keys = pairVerify.controlKeys!!
    cipher = ControlCipher(keys.readKey, keys.writeKey)
}
```
The raw X25519 `shared` secret (32 B) is kept (`PairVerify.shared`, :26; `AirPlaySession.sharedSecret`, AirPlaySession.kt:127) and is the IKM for **all** later keys (control, events, streams).

Strings (PairVerify.kt:119-125): `"Pair-Verify-Encrypt-Salt"`, `"Pair-Verify-Encrypt-Info"`, `"Control-Salt"`, `"Control-Read-Encryption-Key"`, `"Control-Write-Encryption-Key"`, `"PV-Msg02"`, `"PV-Msg03"`.

## 5. Control channel encryption (`AP/ControlCipher.kt`)

Keys: from §4 (read = HKDF(shared, "Control-Salt", "Control-Write-Encryption-Key"), write = HKDF(shared, "Control-Salt", "Control-Read-Encryption-Key")), 32 B each.

Constants: `HEADER_SIZE = 2` (:51), `TAG_SIZE = 16` (:52), `MAX_PAYLOAD = 0x4000` = **16384 bytes** (:53) — *not* 1024.

Frame: `[len: u16 LE = plaintext length][ciphertext: len bytes][tag: 16 bytes]`.
- AAD = the 2 length bytes.
- Nonce = `nonce64(counter)` = `00 00 00 00 || LE64(counter)`; separate counters per direction, both start at 0, +1 per frame (:13-14, 25-26, 38-41).

Encrypt (:32-45):
```kotlin
do {
    val chunk = plaintext.copyOfRange(offset, minOf(offset + MAX_PAYLOAD, plaintext.size))
    val header = byteArrayOf(chunk.size.toByte(), (chunk.size ushr 8).toByte())
    val ciphertextAndTag = chachaSeal(writeKey, nonce64(writeCounter), chunk, header)
    output.add(header); output.add(ciphertextAndTag)
    writeCounter++; offset += MAX_PAYLOAD
} while (offset < plaintext.size)
```
(Empty plaintext produces one zero-length frame.)

Decrypt (:16-30): loops while ≥2 bytes; `length = LE16`; waits for `2+length+16` bytes; opens with `aad = header`; leftover bytes returned as `rest`. No upper bound check on `length` (up to 65535 accepted).

The whole RTSP message stream (requests/responses incl. headers) is encrypted after pair-verify; framing boundaries are independent of RTSP message boundaries (AirPlaySession.kt:467-482: decrypt into plaintext, then `RtspMessage.parseMessages`).

### Event channel (same framing, different keys) (AirPlaySession.kt:897-916)
```kotlin
val writeKey = hkdfSha512(shared, "Events-Salt", "Events-Write-Encryption-Key", 32)
val readKey  = hkdfSha512(shared, "Events-Salt", "Events-Read-Encryption-Key", 32)
eventCipher = ControlCipher(readKey, writeKey)
```
**Oddity / important:** the naming is the *opposite* of the control channel: on the event socket we *read* with `Events-Read-Encryption-Key` and *write* with `Events-Write-Encryption-Key` (whereas on control we read with `Control-Write-...`). This is the working configuration (the event connection is opened by the iPhone to our `eventPort` returned in the session SETUP, AirPlaySession.kt:697-700). Tests confirm the direction mapping is symmetric: CarPlayControllerTouchQueueTest.kt:59,72 decode our output with `ControlCipher(read=writeKeyOfSender, write=...)`.

## 6. /auth-setup — MFi-SAP (`AP/MfiSapAuthSetup.kt`)

Dispatch (AirPlaySession.kt:594-598): path ends with `/auth-setup`; body → `MfiSapAuthSetup.handle(body, mfi)`; null (malformed or no MFi) → **status 400**; else 200 with `Content-Type: application/octet-stream` (AirPlaySession.kt:994). The class comment says it "runs over the already-encrypted control channel" (MfiSapAuthSetup.kt:16), i.e. after pair-verify.

### Request (MfiSapAuthSetup.kt:21-27)
Exactly **33 bytes**: `[0x01 (VERSION)][32-byte iPhone X25519 public key]`. Any other length or first byte → null → 400.

### Processing (:29-50)
```kotlin
val pair = x25519Generate()                                     // our ephemeral
val shared = x25519Shared(pair.privateKey, peerPublicKey)
if (shared.all { it.toInt() == 0 }) return null                 // reject low-order point
val aesKey = sha1("AES-KEY".asciiBytes(), shared).copyOf(16)    // SHA-1("AES-KEY" | shared)[0..16]
val aesIv  = sha1("AES-IV".asciiBytes(),  shared).copyOf(16)    // SHA-1("AES-IV"  | shared)[0..16]
// MFI coprocessor:
val certificate = mfi.readCertificate()
val digest = if (mfi.protocolMajor() == 2) sha1(pair.publicKey, peerPublicKey)     // 20 B
             else                           sha256(pair.publicKey, peerPublicKey)   // 32 B
val encryptedSignature = aesCtr128(aesKey, aesIv, mfi.signChallenge(digest))
// BAA authenticator:
val encryptedSignature = aesCtr128(aesKey, aesIv, mfi.signChallenge(pair.publicKey + peerPublicKey)) // raw 64 B, not hashed
```
- What is signed: **our ephemeral pubkey first, then the iPhone's** (`ourPK(32) | iPhonePK(32)`), hashed with SHA-1 for coprocessor protocol major 2.x, SHA-256 otherwise (3.x). The coprocessor signs the digest as its "challenge".
- Cipher: `AES/CTR/NoPadding`, 128-bit key, IV = 16-byte initial counter block (:171-175). Only the signature is encrypted; certificate is clear.

### Response, MFI type (:69-81)
```
offset 0      : ourX25519PublicKey (32)
offset 32     : u32 BE certLen
offset 36     : certificate (certLen bytes, raw from coprocessor reg 0x30/0x31)
offset 36+cL  : u32 BE sigLen
offset 40+cL  : AES-CTR(signature) (sigLen bytes)
```
`putU32` is big-endian (:177-182).

### Response, BAA type (:83-106)
`ourPK(32) | u32BE(leafLen) | leaf | u32BE(encSigLen) | encSig | u32BE(blobLen) | blob`, where `blob` is OPACK `{"baIC": <intermediate bytes>}` (:108-159):
`E1` (dict, 1 entry) `44 'b' 'a' 'I' 'C'` (4-char string) then data header: `0x70+len` if len ≤ 0x20; `91 len8` if ≤ 0xFF; `92 lenLE16`; else `93 lenLE32`; then the bytes.

### Coprocessor interface (mfi/MfiAuthenticationClient.kt)
`protocolMajor()` = I2C register 0x02 (:55-57, 227); certificate length reg 0x30 (u16 BE), data from 0x31 in 128-byte windows with incrementing register (:60-82, 234-236); challenge: length reg 0x20 (u16 BE), data reg 0x21, start by writing 0x01 to reg 0x10, poll reg 0x10 for 0x10, then signature length reg 0x11, data reg 0x12 (:90-120, 229-238). Challenge 1..128 bytes (:239-240). `LocalMfiAuthenticationClient.protocolMajor()` returns constant 3 (LocalMfiAuthenticationClient.kt:23) → SHA-256 path.

## 7. Identity

- `AirPlayIdentity(privateKey, publicKey, pairingId)` (AirPlayIdentity.kt:6-19). Generation: `AirPlayCrypto.ed25519Generate()` (raw 32-byte Ed25519 seed + 32-byte public key) and `pairingId = UUID.randomUUID().toString()` → lowercase hyphenated 36-char string, e.g. `xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx` (:14-17). On the wire it is the UTF-8 bytes of that string (PairSetup.kt:83, PairVerify.kt:53).
- Persistence: SharedPreferences `"xcertplay_airplay"`, keys `"identity_private"`, `"identity_public"` (lowercase hex), `"pairing_id"` (common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt:28-31, 1543-1558). Generated once, reused forever.
- `publicKeyHex` = lowercase hex of the 32-byte Ed25519 public key (AirPlayIdentity.kt:11; Bytes.kt:15-24).
- Exposure:
  - Bonjour `_airplay._tcp` TXT: `"pi" = pairingId`, `"pk" = publicKeyHex` (64 hex chars), also `deviceid`, `features`, `flags="0x4"`, `model`, `srcvers`, `protovers="1.1"` (shared/.../network/CarPlayBonjour.kt:83-96).
  - iAP2 CarPlay start-session (wireless 0x4301 / wired) carries `publicKey = identity.publicKeyHex` (orchestration/CarPlayController.kt:1764, 2782; transport/Iap2WirelessControlClient.kt:270-284).
- Paired controllers: `PairingStore` map `controllerId(String) → LTPK(32 B)` (PairingStore.kt:4-16), loaded/saved by AirPlayPersistence.kt:1560-1577.

## 8. Stream keys

All derived from the pair-verify X25519 `shared` secret (32 B) with HKDF-SHA512, L=32.

| Stream | Salt | Info | Use | Cite |
|---|---|---|---|---|
| Screen 110/111 (main/alt video), audio 100/101/102 | `"DataStream-Salt" + decimal(streamConnectionID)` | `"DataStream-Output-Encryption-Key"` | decrypt iPhone→us | CarPlayMediaEngine.kt:620-622, 665-678, 705 |
| Microphone uplink (on audio stream type 100) | same salt (that stream's streamConnectionID) | `"DataStream-Input-Encryption-Key"` | encrypt us→iPhone | CarPlayMediaEngine.kt:635, 706 |
| iAP2 DataStream type 130, `clientTypeUUID = E9459FD0-BCAD-4C45-820F-1E72447EF2F2` | `"DataStream-Salt" + decimal(seed)` | `"DataStream-Output-Encryption-Key"` | read key (receive) | CarPlayMediaEngine.kt:379-395, 707 |
| Video-in-car settings channel (type 130, VideoInCar.SETTINGS_CHANNEL_UUID) | `"DataStream-Salt" + decimal(seed)` | read=`Output`, write=`Input` | duplex | CarPlayMediaEngine.kt:455-462 |
| Buffered audio type 103 | — no HKDF — key = SETUP plist `"shk"` (must be 32 B) | | | CarPlayMediaEngine.kt:283-287 |

Salt string: plain concatenation, **no separator**, decimal of the plist integer treated as *unsigned* (`unsignedPlistDecimal`, CarPlayMediaEngine.kt:715-722: Long→`toUnsignedString`, etc.), ASCII. E.g. connection id 12345 → salt `"DataStream-Salt12345"`. Note: the code uses `streamConnectionID` for screen/audio but `seed` for type-130 data streams.

Framing per stream (for reference):
- **Screen (TCP):** 128-byte header; `bodySize = u32 LE header[0..4]` (ScreenStream.kt:84, 243); opcode `header[4]` (`OP_VIDEO_FRAME=0` encrypted, `OP_VIDEO_CONFIG=1` clear avcC/hvcC) (ScreenStream.kt:142-146). Frame body = ciphertext||tag; `chachaOpen(key, nonce64(frameCounter), body, aad = full 128-byte header)` (ScreenStream.kt:170); frameCounter starts at 0 and increments only on opcode-0 frames (ScreenStream.kt:107-109). Header bytes 8..15 = LE NTP 32.32 sender time (ScreenStream.kt:160-167).
- **Audio RTP (UDP) 100/101/102:** `[RTP header 12][ciphertext][tag 16][nonce tail 8]`; AAD = RTP bytes 4..11 (timestamp+SSRC); nonce = `00000000 || tail8` (AudioStream.kt:131-139, 212-215).
- **Microphone (uplink):** same layout; RTP `0x80`, PT, seq BE, ts BE; nonce = `00000000 || LE64(counter)`; tail = LE64(counter) (MicrophonePacketizer.kt:95-126).
- **Buffered audio 103 (TCP):** 2-byte length prefix per frame, then same RTP+ct+tag+nonce8 layout; requires byte0 == 0x80 (BufferedAudioStream.kt:346-370).
- **iAP2 tunnel / video settings:** identical to ControlCipher framing: `[u16 LE len][ct][tag]`, AAD = len bytes, nonce64 counter (IapTunnel.kt:170-187, 217-218; VideoSettingsChannel.kt:65-75, 92-100).

## 9. Golden vectors

### 9.1 Literals from tests (source has **no** SRP / pair-setup / pair-verify / MFi-SAP tests)

`shared/src/test/java/com/shilapi/xcertplay/airplay/AirPlayCryptoChachaTest.kt:22-31` — RFC 8439 §2.8.2 AEAD vector:
- key `808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f`
- nonce `070000004041424344454647`
- aad `50515253c0c1c2c3c4c5c6c7`
- plaintext ASCII `"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."`
- expected tag (last 16 B) `1ae10b594f09e26a7e902ecbd0600691`

`AirPlayCryptoChachaTest.kt:72` uses `nonceLabel("PV-Msg02")`; `:37,54,86,103,125` use `nonce64(n)`.

`Tlv8CodecTest.kt`:
- `[01 03 09 08 07]` → {1: 090807} (:21-23)
- `[01 01 01][02 02 0202][03 03 030303]` → 3 entries (:25-33)
- `[04 00]` → {4: empty} (:36-37)
- `[06 01 01][06 01 02]` → {6: 02} (last wins) (:42-45)
- `[06 FF 11×255][06 FF 22×255][06 03 33 44 55]` → {6: 11×255 ‖ 22×255 ‖ 334455} (513 bytes) (:48-57)
- 600-byte value `(i and 0xff)` round-trips through encode/decode (:60-67) → encoded as `06 FF ..., 06 FF ..., 06 5A ...` (255+255+90).
- caps: 1 MiB of `06 FF 00×255` → value ≤ 65536 (:69-81); 300 fragments → < 76500 (:83-96); 1100×(full-fragment + `01 00`) then `63 03 070707` → type 99 absent (:98-115).

Other test keys (stream/framing tests, not crypto vectors): IapTunnelPeerTest.kt:12 key `ByteArray(32){it+1}`; VideoSettingsChannelPeerTest.kt:11-12 read `ByteArray(32){1}`, write `ByteArray(32){2}`, sync frame AAD `[32,0]` + 32-byte header `00 00 00 20 "sync"...` → reply header `"rply"` at bytes 4..7 (:36-54); ScreenStreamPeerTest.kt:24 key `ByteArray(32){it}`, header body-size LE at bytes 0..1, opcode at byte 4, AAD = 128-byte header (:35-45); BufferedAudioStreamTest.kt:428-438 RTP `80 60 seqBE tsBE`, nonceTail `(seq+i)`, AAD = header[4..12]; CarPlayControllerTouchQueueTest.kt:59,72 `ControlCipher(ByteArray(32){1}, ByteArray(32){2})` decoded by `ControlCipher(ByteArray(32){2}, ByteArray(32){1})`.

### 9.2 Derived values (computed by me with Python stdlib from the formulas above — NOT present in the source; use only to cross-check a reimplementation)

Nonces:
- `nonceLabel("PS-Msg05")` = `0000000050532d4d73673035`
- `nonceLabel("PS-Msg06")` = `0000000050532d4d73673036`
- `nonceLabel("PV-Msg02")` = `0000000050562d4d73673032`
- `nonceLabel("PV-Msg03")` = `0000000050562d4d73673033`
- `nonce64(0)` = `000000000000000000000000`; `nonce64(1)` = `000000000100000000000000`; `nonce64(256)` = `000000000001000000000000`

SRP constants:
- k = SHA512(N ‖ PAD(5)) = `a9c2e2559bf0ebb53f0cbbf62282906bede7f2182f00678211fbd5bde5b285033a4993503b87397f9be5ec02080fedbc0835587ad039060879b8621e8c3659e0`
- H(N) xor H(g) [H(g) = SHA512(0x05)] = `b3d63ef6aafb2e9796dae006fe60f20e5326fe1e1c52a5021eb21747ca33dfea8ff203bd45a2544395d73d19899c174a683c9e783296dd1693ebc71cf5a53da3`
- H(I) = SHA512("Pair-Setup") = `cd3fa890ac891aa9a9d23346f1e4f698b01240ad810cf029d5d57fc79f1b1fc4e2c40862dfb3828ed2c58aa80f678a45a7f4d78b9c4ac33fdc5546f2a10430b3`

HKDF-SHA512, L=32, IKM = `000102…1f` (32 bytes):
- Pair-Verify-Encrypt-Salt / -Info → `faf9f3558a8ed1e45219bd94fb6d27e5b43a1bc861157fc2a0d291d8e3df410a`
- Control-Salt / Control-Write-Encryption-Key (our READ key) → `c3ca130c7033dbe5e7ff7f91d117ead869bac476994c7a48ca170c111136ed96`
- Control-Salt / Control-Read-Encryption-Key (our WRITE key) → `c09403ef8aa6c5045cbd8cf9bf3e665b2caed623af2be0e87c8f80f519914d3d`
- Events-Salt / Events-Write-Encryption-Key (our WRITE key) → `24ee7bb1f775cc08cc23e25bee48afa439250299bc76549e80c0b54d9cc60b61`
- Events-Salt / Events-Read-Encryption-Key (our READ key) → `77c905af437e45acea18c03d6d5b7791fa9b9da65f3d4d63de8ce7ab3ea3fa5a`
- DataStream-Salt12345 / DataStream-Output-Encryption-Key → `7a0a0ebaa095ab14b9366e179f7e3e2eda27ea6f2bf930198acdca4c79d62d9d`
- DataStream-Salt12345 / DataStream-Input-Encryption-Key → `5cc5df99856e50b600793b4da0918b3120d92bac96ff36aec4437e67e5175bbf`

HKDF-SHA512, L=32, IKM = `000102…3f` (64 bytes, stands in for SRP K):
- Pair-Setup-Encrypt-Salt / -Info → `b6335536162d6629e4c0bade85f1b712c85a364bab0dedb25014cfb814489273`
- Pair-Setup-Controller-Sign-Salt / -Info → `0466a0bf8ce2baaa4dcf7797775db7f1c3040e75f035338e97de03eb2033fcc4`
- Pair-Setup-Accessory-Sign-Salt / -Info → `e05a60ccf621522918f88f6709298ff62695ac9ddd9f80f589e484e4a74528d9`

MFi-SAP with shared = `000102…1f`:
- aesKey = SHA1("AES-KEY"‖shared)[0..16] = `8aff92634f0a103a75aa5dc3d24438c0`
- aesIv = SHA1("AES-IV"‖shared)[0..16] = `95f4f8637bdaaf2d1042c9281b541d24`

## 10. Bugs / oddities / ambiguities

1. **No transient pair-setup support**; Method (0x00) and Flags TLVs are ignored. Works because CarPlay uses full pair-setup with fixed PIN 3939.
2. **Pair-setup error state**: on an exception the reply echoes the *received* state (e.g. 3) rather than the next state (4) (PairSetup.kt:29-31); explicit failures use the next state (`err(4)`, `err(6)`).
3. **Pair-verify has no explicit all-zero X25519 check** (PairVerify.kt:48), whereas auth-setup does (MfiSapAuthSetup.kt:31).
   Correction (review 2026-11-19): BouncyCastle's `X25519PrivateKeyParameters.generateSecret` throws on an all-zero
   result, so DiPlay refuses it in both places (auth-setup answers 500 through its catch-all before its own check runs).
4. **Unknown controller** at pair-verify M3 → `[State=4][Error=2]` (PairVerify.kt:92) — the iPhone then re-runs pair-setup.
5. **SRP**: no `u==0` check; non-constant-time proof compare; K uses unpadded S (matches Apple/csrp behaviour); A/B padded to 384 in u/M1/M2 (Apple strips them in M1, see §2.3); H(g) unpadded.
6. **Event-channel key naming is reversed vs. control** (see §5). Control: read=`Control-Write-Encryption-Key`, write=`Control-Read-Encryption-Key`. Events: read=`Events-Read-Encryption-Key`, write=`Events-Write-Encryption-Key`. Both are as in the working code; copy them exactly.
7. **ControlCipher MAX_PAYLOAD is 0x4000 (16 KiB)**, not 1024; the decrypt side accepts any u16 length.
8. **M4 reply of pair-verify is sent in clear**; cipher activates only for subsequent bytes. Any plaintext bytes already buffered in `accumulated` when the cipher switches on remain treated as plaintext (edge case; AirPlaySession.kt:481-484, 533-538). Ours drops the connection instead, before it counts as verified.
9. Stream-key salt suffix: `streamConnectionID` for screen/audio/mic, but `seed` for type-130 data streams (iAP2, video settings). Both as unsigned decimal.
10. `PairSetup.complete` is set but not used as a gate in the code read; pair-verify only needs the persisted LTPK.
11. `TYPE_METHOD` constant is dead code (PairSetup.kt:116).
12. MFi-SAP digest choice depends on coprocessor register 0x02: `== 2` → SHA-1 (20 B), anything else → SHA-256 (32 B). BAA path signs the raw 64 bytes unhashed.
