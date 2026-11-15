package com.discord.utilities.images

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes the `placeholder` Discord sends with media: a base64 ThumbHash (evanw/thumbhash) that
 * expands to a blurry ~32px preview shown until the real image arrives.
 */
object ThumbHash {

    class Image(val width: Int, val height: Int, val argb: IntArray)

    fun toDrawable(resources: Resources, placeholder: String?): BitmapDrawable? {
        val image = placeholder?.let { runCatching { decode(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() } ?: return null
        val bitmap = Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
        return BitmapDrawable(resources, bitmap)
    }

    fun decode(hash: ByteArray): Image {
        fun u(i: Int) = hash[i].toInt() and 0xFF
        val header24 = u(0) or (u(1) shl 8) or (u(2) shl 16)
        val header16 = u(3) or (u(4) shl 8)
        val lDc = (header24 and 63) / 63.0
        val pDc = ((header24 shr 6) and 63) / 31.5 - 1
        val qDc = ((header24 shr 12) and 63) / 31.5 - 1
        val lScale = ((header24 shr 18) and 31) / 31.0
        val hasAlpha = header24 shr 23 != 0
        val pScale = ((header16 shr 3) and 63) / 63.0
        val qScale = ((header16 shr 9) and 63) / 63.0
        val isLandscape = header16 shr 15 != 0
        val lx = max(3, if (isLandscape) (if (hasAlpha) 5 else 7) else header16 and 7)
        val ly = max(3, if (isLandscape) header16 and 7 else if (hasAlpha) 5 else 7)
        val aDc = if (hasAlpha) (u(5) and 15) / 15.0 else 1.0
        val aScale = (u(5) shr 4) / 15.0

        // Saturation is boosted 1.25x to make up for quantisation, as the encoder expects.
        val acStart = if (hasAlpha) 6 else 5
        var acIndex = 0
        fun channel(nx: Int, ny: Int, scale: Double): DoubleArray {
            val ac = ArrayList<Double>()
            for (cy in 0 until ny) {
                var cx = if (cy > 0) 0 else 1
                while (cx * ny < nx * (ny - cy)) {
                    ac += (((u(acStart + (acIndex shr 1)) shr ((acIndex and 1) shl 2)) and 15) / 7.5 - 1) * scale
                    acIndex++
                    cx++
                }
            }
            return ac.toDoubleArray()
        }
        val lAc = channel(lx, ly, lScale)
        val pAc = channel(3, 3, pScale * 1.25)
        val qAc = channel(3, 3, qScale * 1.25)
        val aAc = if (hasAlpha) channel(5, 5, aScale) else null

        val ratio = lx.toDouble() / ly
        val w = (if (ratio > 1) 32.0 else 32 * ratio).roundToInt()
        val h = (if (ratio > 1) 32 / ratio else 32.0).roundToInt()
        val fx = DoubleArray(max(lx, 5))
        val fy = DoubleArray(max(ly, 5))
        val argb = IntArray(w * h)
        fun sum(ac: DoubleArray, nx: Int, ny: Int): Double {
            var total = 0.0
            var j = 0
            for (cy in 0 until ny) {
                var cx = if (cy > 0) 0 else 1
                while (cx * ny < nx * (ny - cy)) total += ac[j++] * fx[cx++] * fy[cy] * 2
            }
            return total
        }
        for (y in 0 until h) for (x in 0 until w) {
            for (cx in fx.indices) fx[cx] = cos(PI / w * (x + 0.5) * cx)
            for (cy in fy.indices) fy[cy] = cos(PI / h * (y + 0.5) * cy)
            val l = lDc + sum(lAc, lx, ly)
            val p = pDc + sum(pAc, 3, 3)
            val q = qDc + sum(qAc, 3, 3)
            val a = aDc + (aAc?.let { sum(it, 5, 5) } ?: 0.0)
            val b = l - 2.0 / 3 * p
            val r = (3 * l - b + q) / 2
            val g = r - q
            fun c(v: Double) = max(0.0, 255 * min(1.0, v)).toInt()
            argb[y * w + x] = (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
        }
        return Image(w, h, argb)
    }
}
