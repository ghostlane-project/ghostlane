package org.olcbox.app.crypt

/** Platform crypto primitives. Client is decrypt-only (never encrypts). */
expect object PlatformCrypto {
    fun randomBytes(size: Int): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
    /** AES-256-CBC + PKCS7 decrypt. Returns null on bad padding/length. */
    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray?
}
