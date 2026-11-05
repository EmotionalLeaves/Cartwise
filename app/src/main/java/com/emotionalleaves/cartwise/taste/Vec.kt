package com.emotionalleaves.cartwise.taste

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object Vec {
    fun toBytes(v: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) b.putFloat(x)
        return b.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.getFloat() }
    }

    fun cos(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var d = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            d += x * y; na += x * x; nb += y * y
        }
        return if (na == 0.0 || nb == 0.0) 0.0 else d / (sqrt(na) * sqrt(nb))
    }

    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x.toDouble() * x
        n = sqrt(n)
        if (n == 0.0) return v
        return FloatArray(v.size) { (v[it] / n).toFloat() }
    }
}
