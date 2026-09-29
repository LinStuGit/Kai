package com.kami.app

import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal GM/T crypto, pure Kotlin (no BouncyCastle — Android ships an old
 * stripped org.bouncycastle on the boot classpath that clashes with bundled
 * newer artifacts).
 *
 * - [sm3]: GM/T 0004 SM3 digest.
 * - SM2 encrypt per GM/T 0003.4 in C1C3C2 layout with the leading "04" point
 *   prefix — byte-identical to `sm-crypto doEncrypt(mode=1)` and to what the
 *   PC madmodel_router sends ("04" + gmssl CryptSM2.encrypt().hex()).
 */
internal object SmCrypto {

    // ============================== SM3 ==============================

    private val IV = intArrayOf(
        0x7380166f,
        0x4914b2b9,
        0x172442d7,
        0xda8a0600.toInt(),
        0xa96f30bc.toInt(),
        0x163138aa,
        0xe38dee4d.toInt(),
        0xb0fb0e4e.toInt(),
    )

    private fun rotl(x: Int, n: Int) = (x shl n) or (x ushr (32 - n))

    private fun p0(x: Int) = x xor rotl(x, 9) xor rotl(x, 17)

    private fun p1(x: Int) = x xor rotl(x, 15) xor rotl(x, 23)

    private fun be32(m: ByteArray, o: Int) = ((m[o].toInt() and 0xff) shl 24) or ((m[o + 1].toInt() and 0xff) shl 16) or
        ((m[o + 2].toInt() and 0xff) shl 8) or (m[o + 3].toInt() and 0xff)

    fun sm3(msg: ByteArray): ByteArray {
        val bitLen = msg.size.toLong() * 8
        val padded = ((msg.size + 8) / 64 + 1) * 64
        val m = ByteArray(padded)
        System.arraycopy(msg, 0, m, 0, msg.size)
        m[msg.size] = 0x80.toByte()
        for (i in 0 until 8) m[padded - 1 - i] = (bitLen ushr (8 * i)).toByte()

        val v = IV.copyOf()
        val w = IntArray(68)
        val wt = IntArray(64)
        for (off in 0 until padded step 64) {
            for (i in 0 until 16) w[i] = be32(m, off + i * 4)
            for (i in 16 until 68) {
                val x = w[i - 16] xor w[i - 9] xor rotl(w[i - 3], 15)
                w[i] = p1(x) xor rotl(w[i - 13], 7) xor w[i - 6]
            }
            for (i in 0 until 64) wt[i] = w[i] xor w[i + 4]
            var a = v[0]
            var b = v[1]
            var c = v[2]
            var d = v[3]
            var e = v[4]
            var f = v[5]
            var g = v[6]
            var h = v[7]
            for (j in 0 until 64) {
                val t = if (j < 16) 0x79cc4519 else 0x9d8a7a87.toInt()
                val ss1 = rotl(rotl(a, 12) + e + rotl(t, j % 32), 7)
                val ss2 = ss1 xor rotl(a, 12)
                val ff = if (j < 16) a xor b xor c else (a and b) or (a and c) or (b and c)
                val gg = if (j < 16) e xor f xor g else (e and f) or (e.inv() and g)
                val tt1 = ff + d + ss2 + wt[j]
                val tt2 = gg + h + ss1 + w[j]
                d = c
                c = rotl(b, 9)
                b = a
                a = tt1
                h = g
                g = rotl(f, 19)
                f = e
                e = p0(tt2)
            }
            v[0] = v[0] xor a
            v[1] = v[1] xor b
            v[2] = v[2] xor c
            v[3] = v[3] xor d
            v[4] = v[4] xor e
            v[5] = v[5] xor f
            v[6] = v[6] xor g
            v[7] = v[7] xor h
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (v[i] ushr 24).toByte()
            out[i * 4 + 1] = (v[i] ushr 16).toByte()
            out[i * 4 + 2] = (v[i] ushr 8).toByte()
            out[i * 4 + 3] = v[i].toByte()
        }
        return out
    }

    // ============================== SM2 ==============================

    private val P = BigInteger("fffffffeffffffffffffffffffffffffffffffff00000000ffffffffffffffff", 16)
    private val A = P.subtract(BigInteger("3"))
    private val B = BigInteger("28e9fa9e9d9f5e344d5a9e4bcf6509a7f39789f515ab8f92ddbcbd414d940e93", 16)
    private val N = BigInteger("fffffffeffffffffffffffffffffffffffffffff7203df6b21c6052b53bbf40939d54123", 16)
    private val GX = BigInteger("32c4ae2c1f1981195f9904466a39c9948fe30bbff2660be1715a4589334c74c7", 16)
    private val GY = BigInteger("bc3736a2f4f6779c59bdcee36b692153d0a9877cc62a474002df32e52139f0a0", 16)

    /** Affine point; null = infinity. */
    private class Pt(val x: BigInteger?, val y: BigInteger?)

    private fun add(p1: Pt, p2: Pt): Pt {
        if (p1.x == null) return p2
        if (p2.x == null) return p1
        val x1 = p1.x!!
        val y1 = p1.y!!
        val x2 = p2.x!!
        val y2 = p2.y!!
        if (x1 == x2) {
            if (y1.add(y2).mod(P) == BigInteger.ZERO) return Pt(null, null)
            return twice(p1)
        }
        val lam = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(P)).mod(P)
        val x3 = lam.multiply(lam).subtract(x1).subtract(x2).mod(P)
        return Pt(x3, lam.multiply(x1.subtract(x3)).subtract(y1).mod(P))
    }

    private fun twice(p: Pt): Pt {
        if (p.x == null || p.y == null || p.y.signum() == 0) return Pt(null, null)
        val lam = p.x!!.multiply(p.x!!).multiply(BigInteger("3"))
            .add(A).multiply(p.y!!.shiftLeft(1).modInverse(P)).mod(P)
        val x3 = lam.multiply(lam).subtract(p.x!!.shiftLeft(1)).mod(P)
        return Pt(x3, lam.multiply(p.x!!.subtract(x3)).subtract(p.y!!).mod(P))
    }

    private fun mul(k: BigInteger, p: Pt): Pt {
        var r = Pt(null, null)
        var q = p
        var kk = k
        while (kk.signum() > 0) {
            if (kk.testBit(0)) r = add(r, q)
            q = twice(q)
            kk = kk.shiftRight(1)
        }
        return r
    }

    private val HEX = "0123456789abcdef"

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            sb.append(HEX[(x.toInt() ushr 4) and 0xf]).append(HEX[x.toInt() and 0xf])
        }
        return sb.toString()
    }

    private fun unhex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /** GM/T KDF over SM3. */
    private fun kdf(z: ByteArray, klen: Int): ByteArray {
        val out = ByteArray(klen)
        var ct = 1
        var done = 0
        while (done < klen) {
            val h = sm3(
                z + byteArrayOf(
                    (ct ushr 24).toByte(),
                    (ct ushr 16).toByte(),
                    (ct ushr 8).toByte(),
                    ct.toByte(),
                ),
            )
            val n = minOf(32, klen - done)
            System.arraycopy(h, 0, out, done, n)
            done += n
            ct++
        }
        return out
    }

    private fun pubPoint(pubHex: String): Pt {
        val s = pubHex.trim().lowercase().removePrefix("04")
        require(s.length >= 128) { "SM2 公钥格式异常" }
        val p = Pt(BigInteger(s.substring(0, 64), 16), BigInteger(s.substring(64, 128), 16))
        // on-curve sanity: y² = x³ + ax + b (mod p)
        val lhs = p.y!!.multiply(p.y!!).mod(P)
        val rhs = p.x!!.multiply(p.x!!).mod(P).multiply(p.x!!).add(A.multiply(p.x!!)).add(B).mod(P)
        require(lhs == rhs) { "SM2 公钥不在曲线上" }
        return p
    }

    /**
     * Encrypt to the router's wire format: hex("04" || C1(64B) || C3(32B) || C2).
     * [k] injectable for deterministic tests; random when null.
     */
    fun encryptHex(pubHex: String, msg: ByteArray, k: BigInteger? = null): String {
        val pb = pubPoint(pubHex)
        val kk = k ?: run {
            val rnd = SecureRandom()
            var t: BigInteger
            do {
                t = BigInteger(256, rnd).mod(N.subtract(BigInteger.ONE)).add(BigInteger.ONE)
            } while (t.signum() == 0)
            t
        }
        val c1 = mul(kk, Pt(GX, GY))
        val s = mul(kk, pb)
        check(s.x != null) { "SM2 加密退化点" }
        val x2 = to32(s.x!!)
        val y2 = to32(s.y!!)
        val t = kdf(x2 + y2, msg.size)
        val c2 = ByteArray(msg.size) { i -> (msg[i].toInt() xor t[i].toInt()).toByte() }
        val c3 = sm3(x2 + msg + y2)
        val head = byteArrayOf(0x04) + to32(c1.x!!) + to32(c1.y!!)
        return hex(head + c3 + c2)
    }

    /** Decrypt a "04||C1||C3||C2" hex payload — used to KAT-verify the primitives. */
    fun decryptHex(skHex: String, ctHex: String): ByteArray {
        val ct = unhex(ctHex.trim().lowercase().removePrefix("04"))
        require(ct.size > 65 + 32) { "SM2 密文过短" }
        val c1 = mul(
            BigInteger(skHex.trim(), 16),
            Pt(
                BigInteger(1, ct.copyOfRange(0, 32)),
                BigInteger(1, ct.copyOfRange(32, 64)),
            ),
        )
        check(c1.x != null)
        val x2 = to32(c1.x!!)
        val y2 = to32(c1.y!!)
        val c3 = ct.copyOfRange(64, 96)
        val c2 = ct.copyOfRange(96, ct.size)
        val t = kdf(x2 + y2, c2.size)
        val msg = ByteArray(c2.size) { i -> (c2[i].toInt() xor t[i].toInt()).toByte() }
        check(sm3(x2 + msg + y2).contentEquals(c3)) { "SM2 C3 校验失败" }
        return msg
    }

    private fun to32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        when {
            raw.size == 33 -> System.arraycopy(raw, 1, out, 0, 32)
            raw.size <= 32 -> System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
            else -> throw IllegalStateException("coordinate overflow")
        }
        return out
    }

    // ============================== TOTP ==============================

    private val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32Decode(s0: String): ByteArray {
        val s = s0.uppercase().replace("=", "").replace(" ", "")
        var bits = 0
        var acc = 0
        val out = ArrayList<Byte>(s.length * 5 / 8)
        for (ch in s) {
            val d = B32.indexOf(ch)
            require(d >= 0) { "Base32 字符异常: $ch" }
            acc = (acc shl 5) or d
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((acc shr bits) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }

    /** RFC 6238, SHA-1, 30s step, 6 digits (same as madmodel_router _totp). */
    fun totp(secretB32: String, atEpochSec: Long = System.currentTimeMillis() / 1000): String {
        val key = base32Decode(secretB32)
        val counter = atEpochSec / 30
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            msg[i] = (c and 0xff).toByte()
            c = c shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val h = mac.doFinal(msg)
        val o = h[19].toInt() and 0x0f
        val code = (
            (h[o].toInt() and 0x7f) shl 24 or ((h[o + 1].toInt() and 0xff) shl 16) or
                ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
            ) % 1_000_000
        return "%06d".format(code)
    }
}
