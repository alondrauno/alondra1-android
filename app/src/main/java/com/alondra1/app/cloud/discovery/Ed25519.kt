package com.alondra1.app.cloud.discovery

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature verification (RFC 8032), verify only. Android's own Ed25519 needs API 33 (minSdk is 29), and the
 * discovery manifest is the only signature the app checks, so this small implementation is used everywhere. Same
 * acceptance rule as Go's crypto/ed25519 and the Windows app (cofactorless, canonical S and point encodings), so all
 * clients agree on the shared fixture cases.
 */
object Ed25519 {
    private val TWO: BigInteger = BigInteger.valueOf(2) // BigInteger.TWO needs API 33
    private val P: BigInteger = TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = mod(BigInteger.valueOf(-121665).multiply(inverse(BigInteger.valueOf(121666))))
    private val SQRT_M1: BigInteger = TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
    private val B: Point = decode(hex("5866666666666666666666666666666666666666666666666666666666666666")) ?: error("base point")

    private data class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decode(publicKey) ?: return false
        val s = leToBig(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val sha = MessageDigest.getInstance("SHA-512")
        sha.update(signature, 0, 32); sha.update(publicKey); sha.update(message)
        val k = leToBig(sha.digest()).mod(L)
        val minusA = a.copy(x = mod(a.x.negate()), t = mod(a.t.negate()))
        val r = add(multiply(B, s), multiply(minusA, k))
        return encode(r).contentEquals(signature.copyOfRange(0, 32))
    }

    private fun mod(v: BigInteger, m: BigInteger = P): BigInteger = v.mod(m)
    private fun inverse(v: BigInteger): BigInteger = mod(v).modPow(P.subtract(TWO), P)

    private fun add(p: Point, q: Point): Point {
        val a = mod(p.y.subtract(p.x).multiply(q.y.subtract(q.x)))
        val b = mod(p.y.add(p.x).multiply(q.y.add(q.x)))
        val c = mod(TWO.multiply(p.t).multiply(q.t).multiply(D))
        val d = mod(TWO.multiply(p.z).multiply(q.z))
        val e = b.subtract(a); val f = d.subtract(c); val g = d.add(c); val h = b.add(a)
        return Point(mod(e.multiply(f)), mod(g.multiply(h)), mod(f.multiply(g)), mod(e.multiply(h)))
    }

    private fun multiply(p: Point, scalar: BigInteger): Point {
        var result = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        var addend = p
        var n = scalar
        while (n.signum() > 0) {
            if (n.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            n = n.shiftRight(1)
        }
        return result
    }

    private fun encode(p: Point): ByteArray {
        val zInv = inverse(p.z)
        val x = mod(p.x.multiply(zInv))
        val y = mod(p.y.multiply(zInv))
        val out = bigToLe(y)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun decode(encoded: ByteArray): Point? {
        val bytes = encoded.copyOf()
        val sign = (bytes[31].toInt() and 0x80) != 0
        bytes[31] = (bytes[31].toInt() and 0x7F).toByte()
        val y = leToBig(bytes)
        if (y >= P) return null
        val u = mod(y.multiply(y).subtract(BigInteger.ONE))
        val v = mod(D.multiply(y).multiply(y).add(BigInteger.ONE))
        val v3 = mod(v.multiply(v).multiply(v))
        var x = mod(u.multiply(v3).multiply(mod(u.multiply(v3).multiply(v3).multiply(v)).modPow(P.subtract(BigInteger.valueOf(5)).divide(BigInteger.valueOf(8)), P)))
        val vx2 = mod(v.multiply(x).multiply(x))
        if (vx2 != u) {
            if (vx2 != mod(u.negate())) return null
            x = mod(x.multiply(SQRT_M1))
        }
        if (x.signum() == 0 && sign) return null
        if (x.testBit(0) != sign) x = mod(x.negate())
        return Point(x, y, BigInteger.ONE, mod(x.multiply(y)))
    }

    private fun leToBig(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun bigToLe(v: BigInteger): ByteArray {
        val be = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        val out = ByteArray(32)
        for (i in be.indices) out[i] = be[be.size - 1 - i]
        return out
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
