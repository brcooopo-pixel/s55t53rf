package com.privatechat.app.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Locale

/** Ephemeral, authenticated X25519 session. The class never persists keys or messages. */
class SecureSession private constructor(
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val sendLabel: ByteArray,
    private val receiveLabel: ByteArray,
    private val transcript: ByteArray
) {
    private var sendSequence = 0L
    private var receiveSequence = 0L

    @Synchronized fun encrypt(plain: ByteArray): ByteArray {
        check(sendSequence < Long.MAX_VALUE) { "Session message limit reached" }
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(sendKey), 128, nonce(sendSequence++), sendLabel + transcript))
        val out = ByteArray(cipher.getOutputSize(plain.size))
        val n = cipher.processBytes(plain, 0, plain.size, out, 0)
        val end = cipher.doFinal(out, n)
        return out.copyOf(n + end)
    }

    @Synchronized fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size in 16..16384) { "Invalid encrypted message length" }
        check(receiveSequence < Long.MAX_VALUE) { "Session message limit reached" }
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(receiveKey), 128, nonce(receiveSequence++), receiveLabel + transcript))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        val n = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        val end = cipher.doFinal(out, n)
        return out.copyOf(n + end)
    }

    private fun nonce(sequence: Long): ByteArray = ByteArray(12).also { b ->
        for (i in 0..7) b[11 - i] = (sequence ushr (8 * i)).toByte()
    }

    companion object {
        private val random = SecureRandom()
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        fun newRoomCode(): String = buildString(12) {
            repeat(12) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

        fun normalizeCode(code: String): String {
            val normalized = code.uppercase(Locale.ROOT).filterNot { it == '-' || it == ' ' }
            require(normalized.length == 12 && normalized.all { ALPHABET.contains(it) }) {
                "Enter the 12-character Room Code"
            }
            return normalized
        }

        /** Blocking; call only from a background thread. Both peers must use the same code. */
        fun handshake(input: DataInputStream, output: DataOutputStream, roomCode: String, isHost: Boolean): SecureSession {
            val code = normalizeCode(roomCode).toByteArray(Charsets.US_ASCII)
            // 60-bit random codes resist practical offline guessing; scrypt raises guess cost.
            val passwordKey = SCrypt.generate(code, "PrivateChat-v1-room".toByteArray(), 1 shl 14, 8, 1, 32)
            val privateKey = X25519PrivateKeyParameters(random)
            val publicKey = privateKey.generatePublicKey().encoded
            val salt = ByteArray(16).also(random::nextBytes)
            val role = if (isHost) byteArrayOf(1) else byteArrayOf(2)
            val unsigned = role + publicKey + salt
            val ownPacket = unsigned + hmac(passwordKey, unsigned)

            if (isHost) {
                writePacket(output, ownPacket)
                val peer = parsePeer(readPacket(input), 2, passwordKey)
                return finish(privateKey, peer.publicKey, passwordKey, publicKey, salt, peer.salt, peer.mac, true)
            } else {
                writePacket(output, ownPacket)
                val peer = parsePeer(readPacket(input), 1, passwordKey)
                return finish(privateKey, peer.publicKey, passwordKey, peer.publicKey, peer.salt, publicKey, salt, false)
            }
        }

        // Public key, salt and authenticator from a validated peer packet.
        private data class Peer(val publicKey: ByteArray, val salt: ByteArray, val mac: ByteArray)

        private fun parsePeer(packet: ByteArray, role: Int, key: ByteArray): Peer {
            require(packet.size == 81 && packet[0].toInt() == role) { "Invalid peer handshake" }
            val unsigned = packet.copyOfRange(0, 49)
            val provided = packet.copyOfRange(49, 81)
            require(constantTimeEquals(provided, hmac(key, unsigned))) {
                "Room Code incorrect or peer authentication failed"
            }
            return Peer(packet.copyOfRange(1, 33), packet.copyOfRange(33, 49), provided)
        }

        private fun finish(
            privateKey: X25519PrivateKeyParameters, peerPublic: ByteArray, passwordKey: ByteArray,
            hostPublic: ByteArray, hostSalt: ByteArray, clientPublic: ByteArray, clientSalt: ByteArray, isHost: Boolean
        ): SecureSession {
            val agreement = X25519Agreement()
            agreement.init(privateKey)
            val shared = ByteArray(32)
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), shared, 0)
            require(shared.any { it.toInt() != 0 }) { "Invalid peer public key" }
            val transcript = hostPublic + hostSalt + clientPublic + clientSalt
            val material = hkdf(shared + passwordKey, transcript, "PrivateChat-v1-traffic".toByteArray(), 64)
            shared.fill(0)
            passwordKey.fill(0)
            val h2c = material.copyOfRange(0, 32)
            val c2h = material.copyOfRange(32, 64)
            material.fill(0)
            return if (isHost) {
                SecureSession(h2c, c2h, "host-to-client".toByteArray(), "client-to-host".toByteArray(), transcript)
            } else {
                SecureSession(c2h, h2c, "client-to-host".toByteArray(), "host-to-client".toByteArray(), transcript)
            }
        }

        private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray {
            val generator = HKDFBytesGenerator(SHA256Digest())
            generator.init(HKDFParameters(ikm, salt, info))
            return ByteArray(size).also { generator.generateBytes(it, 0, size) }
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = HMac(SHA256Digest())
            mac.init(KeyParameter(key))
            mac.update(data, 0, data.size)
            return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
        }

        private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var difference = 0
            for (i in a.indices) difference = difference or (a[i].toInt() xor b[i].toInt())
            return difference == 0
        }

        private fun writePacket(out: DataOutputStream, packet: ByteArray) {
            out.writeInt(packet.size)
            out.write(packet)
            out.flush()
        }

        private fun readPacket(input: DataInputStream): ByteArray {
            val size = input.readInt()
            require(size == 81) { "Invalid peer handshake" }
            return ByteArray(size).also { input.readFully(it) }
        }
    }
}