package com.nuvio.app.features.plugins

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.nuvio.app.features.plugins.cryptointerop.*
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

internal fun pluginGetRandomValues(length: Int): ByteArray {
    require(length >= 0) { "Random byte length must be non-negative" }
    if (length == 0) return ByteArray(0)
    val bytes = ByteArray(length)
    @OptIn(ExperimentalForeignApi::class)
    val status = SecRandomCopyBytes(kSecRandomDefault, length.toULong(), bytes.refTo(0))
    require(status == 0) { "Failed to generate secure random bytes: status $status" }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginDigest(algorithm: String, data: ByteArray): ByteArray {
    val normalized = normalizeDigestAlgorithm(algorithm)
    val output = ByteArray(
        when (normalized) {
            "MD5" -> CC_MD5_DIGEST_LENGTH.toInt()
            "SHA1" -> CC_SHA1_DIGEST_LENGTH.toInt()
            "SHA256" -> CC_SHA256_DIGEST_LENGTH.toInt()
            "SHA384" -> CC_SHA384_DIGEST_LENGTH.toInt()
            "SHA512" -> CC_SHA512_DIGEST_LENGTH.toInt()
            else -> error("Unsupported digest algorithm: $algorithm")
        },
    )

    data.usePinned { pinnedData ->
        output.usePinned { pinnedOutput ->
            val dataPtr = if (data.isNotEmpty()) pinnedData.addressOf(0) else null
            val outputPtr = pinnedOutput.addressOf(0).reinterpret<UByteVar>()

            when (normalized) {
                "MD5" -> CC_MD5(dataPtr, data.size.toUInt(), outputPtr)
                "SHA1" -> CC_SHA1(dataPtr, data.size.toUInt(), outputPtr)
                "SHA256" -> CC_SHA256(dataPtr, data.size.toUInt(), outputPtr)
                "SHA384" -> CC_SHA384(dataPtr, data.size.toUInt(), outputPtr)
                "SHA512" -> CC_SHA512(dataPtr, data.size.toUInt(), outputPtr)
            }
        }
    }

    return output
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginPbkdf2(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keySizeBits: Int,
    algorithm: String,
): ByteArray {
    require(iterations > 0) { "PBKDF2 iterations must be positive" }
    require(keySizeBits > 0 && keySizeBits % 8 == 0) { "PBKDF2 key size must be a positive byte-aligned bit length" }

    val prf = normalizePbkdf2Prf(algorithm)
    
    val derivedKeyLen = keySizeBits / 8
    val derivedKey = ByteArray(derivedKeyLen)
    
    password.usePinned { pinnedPassword ->
        salt.usePinned { pinnedSalt ->
            derivedKey.usePinned { pinnedDerivedKey ->
                val passwordPtr = if (password.isNotEmpty()) pinnedPassword.addressOf(0).reinterpret<ByteVar>() else null
                val saltPtr = if (salt.isNotEmpty()) pinnedSalt.addressOf(0).reinterpret<UByteVar>() else null
                val derivedKeyPtr = pinnedDerivedKey.addressOf(0).reinterpret<UByteVar>()

                val status = CCKeyDerivationPBKDF(
                    algorithm = kCCPBKDF2,
                    password = passwordPtr,
                    passwordLen = password.size.toULong(),
                    salt = saltPtr,
                    saltLen = salt.size.toULong(),
                    prf = prf,
                    rounds = iterations.toUInt(),
                    derivedKey = derivedKeyPtr,
                    derivedKeyLen = derivedKeyLen.toULong()
                )
                
                require(status == kCCSuccess) { "PBKDF2 failed with status: $status" }
            }
        }
    }
    
    return derivedKey
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginAesEncrypt(
    mode: String,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray {
    requireValidAesKey(key)
    if (!mode.uppercase().contains("ECB")) {
        require(iv.isNotEmpty()) { "AES mode $mode requires an IV" }
    }

    if (mode.uppercase().contains("GCM")) {
        return aesGcmSeal(key = key, iv = iv, plaintext = data)
    }
    
    val isEcb = mode.uppercase().contains("ECB")
    val isNoPadding = mode.uppercase().contains("NOPADDING")

    val dataOutAvailable = data.size + 16 // AES block size
    val dataOut = ByteArray(dataOutAvailable)
    
    var finalData: ByteArray? = null
    
    memScoped {
        val dataOutMoved = alloc<platform.posix.size_tVar>()
        
        var options = 0U
        if (isEcb) {
            options = options or kCCOptionECBMode
        }
        if (!isNoPadding) {
            options = options or kCCOptionPKCS7Padding
        }

        key.usePinned { pinnedKey ->
            iv.usePinned { pinnedIv ->
                data.usePinned { pinnedData ->
                    dataOut.usePinned { pinnedDataOut ->
                        val status = CCCrypt(
                            op = kCCEncrypt,
                            alg = kCCAlgorithmAES,
                            options = options,
                            key = if (key.isNotEmpty()) pinnedKey.addressOf(0) else null,
                            keyLength = key.size.toULong(),
                            iv = if (!isEcb && iv.isNotEmpty()) pinnedIv.addressOf(0) else null,
                            dataIn = if (data.isNotEmpty()) pinnedData.addressOf(0) else null,
                            dataInLength = data.size.toULong(),
                            dataOut = pinnedDataOut.addressOf(0),
                            dataOutAvailable = dataOutAvailable.toULong(),
                            dataOutMoved = dataOutMoved.ptr
                        )
                        
                        if (status == kCCSuccess) {
                            finalData = dataOut.copyOf(dataOutMoved.value.toInt())
                        } else {
                            error("CCCrypt Encrypt failed with status: $status")
                        }
                    }
                }
            }
        }
    }
    
    return finalData ?: ByteArray(0)
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginAesDecrypt(
    mode: String,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray {
    requireValidAesKey(key)
    if (!mode.uppercase().contains("ECB")) {
        require(iv.isNotEmpty()) { "AES mode $mode requires an IV" }
    }

    if (mode.uppercase().contains("GCM")) {
        require(data.size >= gcmTagSize) { "Data too short for GCM decryption" }
        val ciphertextLength = data.size - gcmTagSize
        return aesGcmOpen(
            key = key,
            iv = iv,
            ciphertext = data.copyOfRange(0, ciphertextLength),
            tag = data.copyOfRange(ciphertextLength, data.size),
        )
    }
    
    val isEcb = mode.uppercase().contains("ECB")
    val isNoPadding = mode.uppercase().contains("NOPADDING")

    val dataOutAvailable = data.size + 16 // AES block size
    val dataOut = ByteArray(dataOutAvailable)
    
    var finalData: ByteArray? = null
    
    memScoped {
        val dataOutMoved = alloc<platform.posix.size_tVar>()
        
        var options = 0U
        if (isEcb) {
            options = options or kCCOptionECBMode
        }
        if (!isNoPadding) {
            options = options or kCCOptionPKCS7Padding
        }

        key.usePinned { pinnedKey ->
            iv.usePinned { pinnedIv ->
                data.usePinned { pinnedData ->
                    dataOut.usePinned { pinnedDataOut ->
                        val status = CCCrypt(
                            op = kCCDecrypt,
                            alg = kCCAlgorithmAES,
                            options = options,
                            key = if (key.isNotEmpty()) pinnedKey.addressOf(0) else null,
                            keyLength = key.size.toULong(),
                            iv = if (!isEcb && iv.isNotEmpty()) pinnedIv.addressOf(0) else null,
                            dataIn = if (data.isNotEmpty()) pinnedData.addressOf(0) else null,
                            dataInLength = data.size.toULong(),
                            dataOut = pinnedDataOut.addressOf(0),
                            dataOutAvailable = dataOutAvailable.toULong(),
                            dataOutMoved = dataOutMoved.ptr
                        )
                        
                        if (status == kCCSuccess) {
                            finalData = dataOut.copyOf(dataOutMoved.value.toInt())
                        } else {
                            error("CCCrypt failed with status: $status")
                        }
                    }
                }
            }
        }
    }
    
    return finalData ?: ByteArray(0)
}

internal fun pluginSign(algorithm: String, privateKey: ByteArray, data: ByteArray): ByteArray {
    throw UnsupportedOperationException("Asymmetric signing is currently implemented natively only on Android")
}

internal fun pluginVerify(algorithm: String, publicKey: ByteArray, signature: ByteArray, data: ByteArray): Boolean {
    throw UnsupportedOperationException("Asymmetric verification is currently implemented natively only on Android")
}

private fun UByteArray.toHex(): String = joinToString(separator = "") { byte ->
    byte.toString(16).padStart(2, '0')
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginDigestHex(algorithm: String, data: String): String {
    val normalized = normalizeDigestAlgorithm(algorithm)
    val input = data.encodeToByteArray()
    val output = UByteArray(
        when (normalized) {
            "MD5" -> CC_MD5_DIGEST_LENGTH.toInt()
            "SHA1" -> CC_SHA1_DIGEST_LENGTH.toInt()
            "SHA256" -> CC_SHA256_DIGEST_LENGTH.toInt()
            "SHA384" -> CC_SHA384_DIGEST_LENGTH.toInt()
            "SHA512" -> CC_SHA512_DIGEST_LENGTH.toInt()
            else -> error("Unsupported digest algorithm: $algorithm")
        },
    )

    input.usePinned { pinnedInput ->
        output.usePinned { pinnedOutput ->
            val dataPtr = if (input.isNotEmpty()) pinnedInput.addressOf(0) else null
            val outputPtr = pinnedOutput.addressOf(0)

            when (normalized) {
                "MD5" -> CC_MD5(dataPtr, input.size.toUInt(), outputPtr)
                "SHA1" -> CC_SHA1(dataPtr, input.size.toUInt(), outputPtr)
                "SHA256" -> CC_SHA256(dataPtr, input.size.toUInt(), outputPtr)
                "SHA384" -> CC_SHA384(dataPtr, input.size.toUInt(), outputPtr)
                "SHA512" -> CC_SHA512(dataPtr, input.size.toUInt(), outputPtr)
            }
        }
    }

    return output.toHex()
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginHmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val (alg, outputSize) = normalizeHmacAlgorithm(algorithm)
    val output = ByteArray(outputSize)

    key.usePinned { pinnedKey ->
        data.usePinned { pinnedInput ->
            output.usePinned { pinnedOutput ->
                val keyPtr = if (key.isNotEmpty()) pinnedKey.addressOf(0) else null
                val inputPtr = if (data.isNotEmpty()) pinnedInput.addressOf(0) else null

                CCHmac(
                    alg,
                    keyPtr,
                    key.size.toULong(),
                    inputPtr,
                    data.size.toULong(),
                    pinnedOutput.addressOf(0).reinterpret<UByteVar>(),
                )
            }
        }
    }

    return output
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginHmacHex(algorithm: String, key: String, data: String): String {
    return pluginHmac(algorithm, key.encodeToByteArray(), data.encodeToByteArray()).toHex()
}

private fun normalizeDigestAlgorithm(algorithm: String): String {
    return when (algorithm.normalizedAlgorithmToken()) {
        "MD5" -> "MD5"
        "SHA1" -> "SHA1"
        "SHA256" -> "SHA256"
        "SHA384" -> "SHA384"
        "SHA512" -> "SHA512"
        else -> error("Unsupported digest algorithm: $algorithm")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun normalizePbkdf2Prf(algorithm: String) =
    when (algorithm.normalizedAlgorithmToken().removePrefix("HMAC")) {
        "SHA1" -> kCCPRFHmacAlgSHA1
        "SHA256" -> kCCPRFHmacAlgSHA256
        "SHA384" -> kCCPRFHmacAlgSHA384
        "SHA512" -> kCCPRFHmacAlgSHA512
        else -> error("Unsupported PBKDF2 hash algorithm: $algorithm")
    }

@OptIn(ExperimentalForeignApi::class)
private fun normalizeHmacAlgorithm(algorithm: String) =
    when (algorithm.normalizedAlgorithmToken().removePrefix("HMAC")) {
        "MD5" -> kCCHmacAlgMD5 to CC_MD5_DIGEST_LENGTH.toInt()
        "SHA1" -> kCCHmacAlgSHA1 to CC_SHA1_DIGEST_LENGTH.toInt()
        "SHA256" -> kCCHmacAlgSHA256 to CC_SHA256_DIGEST_LENGTH.toInt()
        "SHA384" -> kCCHmacAlgSHA384 to CC_SHA384_DIGEST_LENGTH.toInt()
        "SHA512" -> kCCHmacAlgSHA512 to CC_SHA512_DIGEST_LENGTH.toInt()
        else -> error("Unsupported HMAC algorithm: $algorithm")
    }

private fun requireValidAesKey(key: ByteArray) {
    require(key.size == 16 || key.size == 24 || key.size == 32) {
        "AES key must be 16, 24, or 32 bytes"
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }

private fun String.normalizedAlgorithmToken(): String =
    uppercase()
        .replace("-", "")
        .replace("_", "")
        .replace("/", "")
        .replace(" ", "")

@OptIn(ExperimentalEncodingApi::class)
internal fun pluginBase64Encode(data: String): String =
    Base64.encode(data.encodeToByteArray())

@OptIn(ExperimentalEncodingApi::class)
internal fun pluginBase64Decode(data: String): String {
    var normalized = data.trim().replace("\n", "").replace("\r", "").replace(" ", "")
    normalized = normalized.replace("-", "+").replace("_", "/")
    val padNeeded = (4 - (normalized.length % 4)) % 4
    if (padNeeded > 0) {
        normalized += "=".repeat(padNeeded)
    }
    val decoded = Base64.decode(normalized)
    return decoded.decodeToString()
}

internal fun pluginUtf8ToHex(value: String): String =
    value.encodeToByteArray().joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }

internal fun pluginHexToByteArray(hex: String): ByteArray {
    val normalized = hex.trim().lowercase()
        .replace(" ", "")
        .removePrefix("0x")
    if (normalized.isEmpty()) return ByteArray(0)

    val evenHex = if (normalized.length % 2 == 0) normalized else "0$normalized"
    val out = ByteArray(evenHex.length / 2)
    for (index in out.indices) {
        val part = evenHex.substring(index * 2, index * 2 + 2)
        out[index] = part.toInt(16).toByte()
    }
    return out
}

internal fun pluginHexToUtf8(hex: String): String {
    return pluginHexToByteArray(hex).decodeToString()
}

// AES-GCM, built from public CommonCrypto only.
//
// CommonCrypto does expose GCM through CCCryptorCreateWithMode(kCCModeGCM, ...), but driving it
// needs CCCryptorGCMEncrypt/Decrypt/Final, which are SPI. App Store Connect rejects any binary
// referencing them with error 90338, and it does so during processing -- after the upload has
// "succeeded" and the build number is spent. So GCM is assembled here instead: the counter mode
// runs through the public CCCrypt in ECB, and GHASH is computed in Kotlin.
//
// The wire format is unchanged: ciphertext followed by a 16-byte tag, no AAD.

private const val gcmBlockSize = 16
private const val gcmTagSize = 16

// R = 0xE1000000_00000000, the GF(2^128) reduction polynomial from NIST SP 800-38D.
private const val gcmReductionPolynomial: Long = -0x1F00000000000000L

private fun aesGcmSeal(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
    val hashSubkey = aesEcbEncryptBlocks(key, ByteArray(gcmBlockSize))
    val initialCounter = gcmInitialCounter(hashSubkey, iv)
    val ciphertext = aesGcmXorKeystream(key, initialCounter, plaintext)
    return ciphertext + aesGcmTag(key, hashSubkey, initialCounter, ciphertext)
}

private fun aesGcmOpen(
    key: ByteArray,
    iv: ByteArray,
    ciphertext: ByteArray,
    tag: ByteArray,
): ByteArray {
    val hashSubkey = aesEcbEncryptBlocks(key, ByteArray(gcmBlockSize))
    val initialCounter = gcmInitialCounter(hashSubkey, iv)
    val expected = aesGcmTag(key, hashSubkey, initialCounter, ciphertext)

    // Compared without early exit: a length-dependent loop here would leak the tag one byte at a
    // time to a caller that can retry.
    var difference = 0
    for (index in 0 until gcmTagSize) {
        difference = difference or (expected[index].toInt() xor tag[index].toInt())
    }
    if (difference != 0) error("GCM tag verification failed")

    return aesGcmXorKeystream(key, initialCounter, ciphertext)
}

private fun aesGcmTag(
    key: ByteArray,
    hashSubkey: ByteArray,
    initialCounter: ByteArray,
    ciphertext: ByteArray,
): ByteArray {
    val ghash = GHash(hashSubkey)
    ghash.update(ciphertext)
    ghash.updateLengthBlock(aadLengthBits = 0L, cipherLengthBits = ciphertext.size.toLong() * 8)
    val hashed = ghash.digest()
    val mask = aesEcbEncryptBlocks(key, initialCounter)
    return ByteArray(gcmTagSize) { index ->
        (hashed[index].toInt() xor mask[index].toInt()).toByte()
    }
}

/**
 * J0. A 12-byte IV -- the common case -- is used directly with a counter of 1; any other length is
 * folded through GHASH, as the spec requires.
 */
private fun gcmInitialCounter(hashSubkey: ByteArray, iv: ByteArray): ByteArray {
    if (iv.size == 12) {
        val counter = ByteArray(gcmBlockSize)
        iv.copyInto(counter)
        counter[gcmBlockSize - 1] = 1
        return counter
    }
    val ghash = GHash(hashSubkey)
    ghash.update(iv)
    ghash.updateLengthBlock(aadLengthBits = 0L, cipherLengthBits = iv.size.toLong() * 8)
    return ghash.digest()
}

/**
 * GCM's counter mode. AES-ECB maps each 16-byte block independently, so a run of counter blocks
 * encrypted in one call yields exactly the concatenated keystream -- which keeps this to one
 * CCCrypt call per chunk rather than one per block.
 */
private fun aesGcmXorKeystream(key: ByteArray, initialCounter: ByteArray, input: ByteArray): ByteArray {
    if (input.isEmpty()) return ByteArray(0)

    val output = ByteArray(input.size)
    val counter = initialCounter.copyOf()
    val blocksPerChunk = 4096 // 64 KiB of counter blocks in flight at a time.
    var offset = 0

    while (offset < input.size) {
        val remaining = input.size - offset
        val blocks = minOf(blocksPerChunk, (remaining + gcmBlockSize - 1) / gcmBlockSize)
        val counters = ByteArray(blocks * gcmBlockSize)
        for (block in 0 until blocks) {
            incrementGcmCounter(counter)
            counter.copyInto(counters, block * gcmBlockSize)
        }

        val keystream = aesEcbEncryptBlocks(key, counters)
        val chunk = minOf(remaining, blocks * gcmBlockSize)
        for (index in 0 until chunk) {
            output[offset + index] = (input[offset + index].toInt() xor keystream[index].toInt()).toByte()
        }
        offset += chunk
    }

    return output
}

/** inc32: only the trailing 32 bits advance, wrapping within themselves. */
private fun incrementGcmCounter(counter: ByteArray) {
    for (index in gcmBlockSize - 1 downTo gcmBlockSize - 4) {
        val next = ((counter[index].toInt() and 0xFF) + 1) and 0xFF
        counter[index] = next.toByte()
        if (next != 0) return
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun aesEcbEncryptBlocks(key: ByteArray, blocks: ByteArray): ByteArray {
    require(blocks.size % gcmBlockSize == 0) {
        "AES-ECB input must be a whole number of blocks, got ${blocks.size}"
    }
    if (blocks.isEmpty()) return ByteArray(0)

    val output = ByteArray(blocks.size)
    memScoped {
        val dataOutMoved = alloc<platform.posix.size_tVar>()
        key.usePinned { pinnedKey ->
            blocks.usePinned { pinnedIn ->
                output.usePinned { pinnedOut ->
                    val status = CCCrypt(
                        op = kCCEncrypt,
                        alg = kCCAlgorithmAES,
                        options = kCCOptionECBMode,
                        key = pinnedKey.addressOf(0),
                        keyLength = key.size.toULong(),
                        iv = null,
                        dataIn = pinnedIn.addressOf(0),
                        dataInLength = blocks.size.toULong(),
                        dataOut = pinnedOut.addressOf(0),
                        dataOutAvailable = output.size.toULong(),
                        dataOutMoved = dataOutMoved.ptr,
                    )
                    if (status != kCCSuccess) {
                        error("CCCrypt ECB failed with status: $status")
                    }
                    if (dataOutMoved.value.toInt() != output.size) {
                        error("CCCrypt ECB wrote ${dataOutMoved.value} bytes, expected ${output.size}")
                    }
                }
            }
        }
    }
    return output
}

/** GHASH over GF(2^128), per NIST SP 800-38D. State and subkey are held as two big-endian longs. */
private class GHash(hashSubkey: ByteArray) {
    private val subkeyHigh: Long = hashSubkey.bigEndianLongAt(0)
    private val subkeyLow: Long = hashSubkey.bigEndianLongAt(8)
    private var stateHigh: Long = 0L
    private var stateLow: Long = 0L

    /** Absorbs data a block at a time, zero-padding a short final block. */
    fun update(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            if (data.size - offset >= gcmBlockSize) {
                stateHigh = stateHigh xor data.bigEndianLongAt(offset)
                stateLow = stateLow xor data.bigEndianLongAt(offset + 8)
            } else {
                val padded = ByteArray(gcmBlockSize)
                data.copyInto(padded, 0, offset, data.size)
                stateHigh = stateHigh xor padded.bigEndianLongAt(0)
                stateLow = stateLow xor padded.bigEndianLongAt(8)
            }
            multiplyBySubkey()
            offset += gcmBlockSize
        }
    }

    /** The closing block: the two lengths, in bits, as a pair of 64-bit big-endian values. */
    fun updateLengthBlock(aadLengthBits: Long, cipherLengthBits: Long) {
        stateHigh = stateHigh xor aadLengthBits
        stateLow = stateLow xor cipherLengthBits
        multiplyBySubkey()
    }

    fun digest(): ByteArray {
        val out = ByteArray(gcmBlockSize)
        out.putBigEndianLong(0, stateHigh)
        out.putBigEndianLong(8, stateLow)
        return out
    }

    /** Carry-less multiply of the state by the hash subkey, most significant bit first. */
    private fun multiplyBySubkey() {
        var resultHigh = 0L
        var resultLow = 0L
        var runningHigh = subkeyHigh
        var runningLow = subkeyLow
        var operandHigh = stateHigh
        var operandLow = stateLow

        for (bit in 0 until 64) {
            if (operandHigh < 0) { // top bit set
                resultHigh = resultHigh xor runningHigh
                resultLow = resultLow xor runningLow
            }
            operandHigh = operandHigh shl 1
            val carry = runningLow and 1L
            runningLow = (runningLow ushr 1) or (runningHigh shl 63)
            runningHigh = runningHigh ushr 1
            if (carry != 0L) runningHigh = runningHigh xor gcmReductionPolynomial
        }
        for (bit in 0 until 64) {
            if (operandLow < 0) {
                resultHigh = resultHigh xor runningHigh
                resultLow = resultLow xor runningLow
            }
            operandLow = operandLow shl 1
            val carry = runningLow and 1L
            runningLow = (runningLow ushr 1) or (runningHigh shl 63)
            runningHigh = runningHigh ushr 1
            if (carry != 0L) runningHigh = runningHigh xor gcmReductionPolynomial
        }

        stateHigh = resultHigh
        stateLow = resultLow
    }
}

private fun ByteArray.bigEndianLongAt(index: Int): Long {
    var value = 0L
    for (offset in 0 until 8) {
        value = (value shl 8) or (this[index + offset].toLong() and 0xFF)
    }
    return value
}

private fun ByteArray.putBigEndianLong(index: Int, value: Long) {
    for (offset in 0 until 8) {
        this[index + offset] = (value ushr (56 - 8 * offset)).toByte()
    }
}
