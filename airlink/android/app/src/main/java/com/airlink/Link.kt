package com.airlink

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** PIN-authenticated, AES-256-GCM encrypted channel to the PC receiver. */
class Link(host: String, port: Int, pin: String) {
    private val sock = Socket()
    private val out: OutputStream
    private val key: SecretKeySpec
    private var ctr = 0L

    init {
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(host, port), 5000)
        sock.soTimeout = 8000
        val inp = DataInputStream(sock.getInputStream())
        out = sock.getOutputStream()
        val s = ByteArray(16).also { inp.readFully(it) }
        val c = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val pb = pin.toByteArray()
        out.write(c + mac(pb, "client".toByteArray() + s + c)); out.flush()
        val srv = ByteArray(32).also { inp.readFully(it) } // EOF here = wrong PIN
        if (!MessageDigest.isEqual(srv, mac(pb, "server".toByteArray() + s + c)))
            throw IOException("PC failed verification")
        key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(pb + s + c), "AES")
        sock.soTimeout = 0
    }

    private fun mac(k: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }.doFinal(data)

    /** type: 0 = device name, 1 = JPEG frame, 2 = keepalive */
    @Synchronized
    fun send(type: Int, payload: ByteArray) {
        val nonce = ByteArray(12)
        ByteBuffer.wrap(nonce).putLong(4, ctr++)
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val ct = ci.doFinal(byteArrayOf(type.toByte()) + payload)
        out.write(ByteBuffer.allocate(4).putInt(ct.size).array())
        out.write(ct)
        out.flush()
    }

    fun close() { runCatching { sock.close() } }
}
