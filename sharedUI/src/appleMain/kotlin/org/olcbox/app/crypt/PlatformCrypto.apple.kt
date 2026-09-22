package org.olcbox.app.crypt

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCBlockSizeAES128
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * Apple (iosArm64 / iosSimulatorArm64 / macosArm64) crypto via CommonCrypto.
 * Compiled only in release.yml (build-macos / build-ios) — not in pr-checks —
 * so behavior is verified on a device, wire format is proven by the JVM path.
 */
@OptIn(ExperimentalForeignApi::class)
actual object PlatformCrypto {
    actual fun randomBytes(size: Int): ByteArray {
        require(size >= 0)
        if (size == 0) return ByteArray(0)
        return ByteArray(size).also { bytes ->
            bytes.usePinned { pinned ->
                check(
                    SecRandomCopyBytes(
                        kSecRandomDefault,
                        size.convert(),
                        pinned.addressOf(0)
                    ) == 0
                ) { "SecRandomCopyBytes failed" }
            }
        }
    }

    actual fun sha256(data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        out.usePinned { op ->
            val md = op.addressOf(0).reinterpret<UByteVar>()
            if (data.isEmpty()) {
                CC_SHA256(null, 0u, md)
            } else {
                data.usePinned { dp -> CC_SHA256(dp.addressOf(0), data.size.convert(), md) }
            }
        }
        return out
    }

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        key.usePinned { kp ->
            out.usePinned { op ->
                val keyPtr = if (key.isEmpty()) null else kp.addressOf(0)
                val macOut = op.addressOf(0)
                if (data.isEmpty()) {
                    CCHmac(kCCHmacAlgSHA256, keyPtr, key.size.convert(), null, 0u, macOut)
                } else {
                    data.usePinned { dp ->
                        CCHmac(
                            kCCHmacAlgSHA256,
                            keyPtr, key.size.convert(),
                            dp.addressOf(0), data.size.convert(),
                            macOut
                        )
                    }
                }
            }
        }
        return out
    }

    actual fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray? = memScoped {
        if (ciphertext.isEmpty()) return@memScoped null
        val outBuf = ByteArray(ciphertext.size + kCCBlockSizeAES128.toInt())
        val moved = alloc<size_tVar>()
        var status = -1
        key.usePinned { kp ->
            iv.usePinned { ivp ->
                ciphertext.usePinned { cp ->
                    outBuf.usePinned { op ->
                        status = CCCrypt(
                            kCCDecrypt, kCCAlgorithmAES, kCCOptionPKCS7Padding,
                            kp.addressOf(0), key.size.convert(),
                            ivp.addressOf(0),
                            cp.addressOf(0), ciphertext.size.convert(),
                            op.addressOf(0), outBuf.size.convert(),
                            moved.ptr
                        )
                    }
                }
            }
        }
        if (status != kCCSuccess) return@memScoped null
        outBuf.copyOf(moved.value.toInt())
    }
}
