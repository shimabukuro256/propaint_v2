package com.propaint.app.engine

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * ゴールデンイメージ (期待画像) との比較テストヘルパー。
 * java.awt / javax.imageio に依存しない純粋な PNG 入出力。
 *
 * - 初回実行時: ゴールデンイメージが存在しなければ生成して保存。テストは PASS。
 * - 以降の実行: 描画結果とゴールデンイメージをピクセル単位で比較。
 * - 差分が閾値を超えたらテスト FAIL + diff 画像を出力。
 */
object GoldenImageHelper {

    private val goldenDir = File("testFixtures/golden")
    private val diffDir = File("testFixtures/diff")

    // ── PNG 書き込み (最小実装) ──────────────────────────────────────

    /**
     * ARGB IntArray を PNG バイト列にエンコード。
     * フィルタなし (filter byte = 0) + zlib 圧縮。
     */
    fun encodePng(pixels: IntArray, width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)

        // PNG signature
        dos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        // IHDR
        writeChunk(dos, "IHDR") { chunk ->
            chunk.writeInt(width)
            chunk.writeInt(height)
            chunk.writeByte(8)  // bit depth
            chunk.writeByte(6)  // color type: RGBA
            chunk.writeByte(0)  // compression
            chunk.writeByte(0)  // filter
            chunk.writeByte(0)  // interlace
        }

        // IDAT
        val raw = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        val deflaterStream = DeflaterOutputStream(raw, deflater)
        for (y in 0 until height) {
            deflaterStream.write(0) // filter byte: None
            for (x in 0 until width) {
                val c = pixels[y * width + x]
                deflaterStream.write((c shr 16) and 0xFF) // R
                deflaterStream.write((c shr 8) and 0xFF)  // G
                deflaterStream.write(c and 0xFF)           // B
                deflaterStream.write((c shr 24) and 0xFF)  // A
            }
        }
        deflaterStream.finish()
        deflater.end()
        writeChunk(dos, "IDAT", raw.toByteArray())

        // IEND
        writeChunk(dos, "IEND", byteArrayOf())

        dos.flush()
        return out.toByteArray()
    }

    /**
     * PNG バイト列をデコードして ARGB IntArray を返す。
     * @return Triple(pixels, width, height)
     */
    fun decodePng(data: ByteArray): Triple<IntArray, Int, Int> {
        var pos = 8 // skip signature
        var width = 0; var height = 0; var bitDepth = 0; var colorType = 0
        val idatChunks = mutableListOf<ByteArray>()

        while (pos < data.size) {
            val length = readInt(data, pos); pos += 4
            val type = String(data, pos, 4, Charsets.US_ASCII); pos += 4
            val chunkData = data.copyOfRange(pos, pos + length); pos += length
            pos += 4 // skip CRC

            when (type) {
                "IHDR" -> {
                    width = readInt(chunkData, 0)
                    height = readInt(chunkData, 4)
                    bitDepth = chunkData[8].toInt() and 0xFF
                    colorType = chunkData[9].toInt() and 0xFF
                }
                "IDAT" -> idatChunks.add(chunkData)
                "IEND" -> break
            }
        }

        require(bitDepth == 8 && colorType == 6) {
            "Only 8-bit RGBA PNG supported, got bitDepth=$bitDepth colorType=$colorType"
        }

        // Decompress
        val compressed = ByteArrayOutputStream()
        for (chunk in idatChunks) compressed.write(chunk)
        val inflater = Inflater()
        inflater.setInput(compressed.toByteArray())
        val decompressed = ByteArray((width * 4 + 1) * height)
        inflater.inflate(decompressed)
        inflater.end()

        // Unfilter (only None filter supported for our output)
        val pixels = IntArray(width * height)
        val stride = width * 4 + 1
        for (y in 0 until height) {
            val rowStart = y * stride + 1 // skip filter byte
            for (x in 0 until width) {
                val off = rowStart + x * 4
                val r = decompressed[off].toInt() and 0xFF
                val g = decompressed[off + 1].toInt() and 0xFF
                val b = decompressed[off + 2].toInt() and 0xFF
                val a = decompressed[off + 3].toInt() and 0xFF
                pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Triple(pixels, width, height)
    }

    private fun writeChunk(dos: DataOutputStream, type: String, data: ByteArray) {
        dos.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        dos.write(typeBytes)
        dos.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        dos.writeInt(crc.value.toInt())
    }

    private fun writeChunk(dos: DataOutputStream, type: String, writer: (DataOutputStream) -> Unit) {
        val buf = ByteArrayOutputStream()
        writer(DataOutputStream(buf))
        writeChunk(dos, type, buf.toByteArray())
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
        ((data[offset + 1].toInt() and 0xFF) shl 16) or
        ((data[offset + 2].toInt() and 0xFF) shl 8) or
        (data[offset + 3].toInt() and 0xFF)

    // ── Surface → pixel array 変換 ──────────────────────────────────

    /**
     * TiledSurface → straight ARGB IntArray。
     */
    fun surfaceToPixels(surface: TiledSurface): IntArray {
        val premul = surface.toPixelArray()
        return IntArray(premul.size) { PixelOps.unpremultiply(premul[it]) }
    }

    /**
     * TiledSurface を白背景上に合成した straight ARGB IntArray。
     */
    fun surfaceToPixelsOnWhite(surface: TiledSurface): IntArray {
        val premul = surface.toPixelArray()
        val white = PixelOps.pack(255, 255, 255, 255)
        return IntArray(premul.size) { PixelOps.blendSrcOver(white, premul[it]) }
    }

    // ── 比較 ────────────────────────────────────────────────────────

    data class CompareResult(
        val totalPixels: Int,
        val diffPixels: Int,
        val maxChannelError: Int,
        val avgError: Double,
    ) {
        val diffRatio: Double get() = diffPixels.toDouble() / totalPixels
        fun passed(maxDiffRatio: Double = 0.001, maxError: Int = 3): Boolean =
            diffRatio <= maxDiffRatio && maxChannelError <= maxError
    }

    fun comparePixels(
        actual: IntArray, expected: IntArray,
        threshold: Int = 2,
    ): CompareResult {
        require(actual.size == expected.size) { "Size mismatch: ${actual.size} vs ${expected.size}" }
        var diffPixels = 0; var maxError = 0; var totalError = 0L

        for (i in actual.indices) {
            val a = actual[i]; val e = expected[i]
            val dr = abs(((a shr 16) and 0xFF) - ((e shr 16) and 0xFF))
            val dg = abs(((a shr 8) and 0xFF) - ((e shr 8) and 0xFF))
            val db = abs((a and 0xFF) - (e and 0xFF))
            val da = abs(((a shr 24) and 0xFF) - ((e shr 24) and 0xFF))
            val chMax = maxOf(dr, dg, db, da)
            if (chMax > threshold) {
                diffPixels++
                maxError = maxOf(maxError, chMax)
            }
            totalError += chMax
        }
        return CompareResult(actual.size, diffPixels, maxError, totalError.toDouble() / actual.size)
    }

    // ── ゴールデンイメージ比較 ──────────────────────────────────────

    /**
     * ゴールデンイメージと比較。初回は保存のみ。
     * @return null=初回生成(テストPASS), CompareResult=比較結果
     */
    fun assertMatchesGolden(
        name: String,
        pixels: IntArray,
        width: Int,
        height: Int,
        maxDiffRatio: Double = 0.001,
        maxChannelError: Int = 3,
        threshold: Int = 2,
    ): CompareResult? {
        goldenDir.mkdirs()
        val goldenFile = File(goldenDir, "$name.png")

        if (!goldenFile.exists()) {
            goldenFile.writeBytes(encodePng(pixels, width, height))
            println("[GoldenImage] Generated: ${goldenFile.absolutePath}")
            println("[GoldenImage] Review and commit this file to lock in the expected output.")
            return null
        }

        val (expected, ew, eh) = decodePng(goldenFile.readBytes())
        require(ew == width && eh == height) {
            "Golden image size mismatch: ${ew}x${eh} vs ${width}x${height}"
        }

        val result = comparePixels(pixels, expected, threshold)

        if (!result.passed(maxDiffRatio, maxChannelError)) {
            diffDir.mkdirs()
            File(diffDir, "${name}_actual.png").writeBytes(encodePng(pixels, width, height))
            // diff image: 差分を赤で、一致をグレーで表示
            val diffPixels = IntArray(pixels.size) { i ->
                val a = pixels[i]; val e = expected[i]
                val dr = abs(((a shr 16) and 0xFF) - ((e shr 16) and 0xFF))
                val dg = abs(((a shr 8) and 0xFF) - ((e shr 8) and 0xFF))
                val db = abs((a and 0xFF) - (e and 0xFF))
                val da = abs(((a shr 24) and 0xFF) - ((e shr 24) and 0xFF))
                val chMax = maxOf(dr, dg, db, da)
                if (chMax > threshold) {
                    val intensity = minOf(255, chMax * 10)
                    (0xFF shl 24) or (intensity shl 16)
                } else {
                    val gray = ((a shr 16) and 0xFF) / 3 + ((a shr 8) and 0xFF) / 3 + (a and 0xFF) / 3
                    (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
            }
            File(diffDir, "${name}_diff.png").writeBytes(encodePng(diffPixels, width, height))

            throw AssertionError(
                "Golden image mismatch: $name\n" +
                "  diffPixels=${result.diffPixels}/${result.totalPixels} " +
                "(${String.format("%.4f%%", result.diffRatio * 100)})\n" +
                "  maxChannelError=${result.maxChannelError}\n" +
                "  avgError=${String.format("%.4f", result.avgError)}\n" +
                "  See: ${diffDir.absolutePath}/${name}_diff.png"
            )
        }
        return result
    }
}
