package ch.ripple.core

import kotlin.jvm.JvmInline
import kotlin.math.absoluteValue

/**
 * An exact CHF amount stored in Rappen (1/100 CHF). Integer arithmetic keeps every
 * simulation bit-for-bit reproducible on all platforms, unlike Double.
 */
@JvmInline
value class Chf(val rappen: Long) : Comparable<Chf> {
    operator fun plus(other: Chf) = Chf(Math.addExact(rappen, other.rappen))
    operator fun minus(other: Chf) = Chf(Math.subtractExact(rappen, other.rappen))
    operator fun unaryMinus() = Chf(-rappen)
    override fun compareTo(other: Chf) = rappen.compareTo(other.rappen)

    val isNegative get() = rappen < 0

    /** Swiss formatting, e.g. `CHF 152'000.00` or `CHF -18'600.00`. */
    fun format(): String {
        val abs = rappen.absoluteValue
        val francs = (abs / 100).toString().reversed().chunked(3).joinToString("'").reversed()
        val cents = (abs % 100).toString().padStart(2, '0')
        return "CHF ${if (rappen < 0) "-" else ""}$francs.$cents"
    }

    override fun toString() = format()

    companion object {
        val ZERO = Chf(0)
        fun francs(amount: Long) = Chf(Math.multiplyExact(amount, 100L))
    }
}

/** Overflow-checked Long helpers usable from common code. */
private object Math {
    fun addExact(a: Long, b: Long): Long {
        val r = a + b
        require(((a xor r) and (b xor r)) >= 0) { "CHF amount overflow" }
        return r
    }
    fun subtractExact(a: Long, b: Long): Long {
        val r = a - b
        require(((a xor b) and (a xor r)) >= 0) { "CHF amount overflow" }
        return r
    }
    fun multiplyExact(a: Long, b: Long): Long {
        val r = a * b
        require(a == 0L || (r / a == b && !(a == -1L && b == Long.MIN_VALUE))) { "CHF amount overflow" }
        return r
    }
}
