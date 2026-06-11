package cn.ppps.forwarder.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.EnumMap
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object CloneQrUtils {
    const val CHUNK_SIZE = 500
    const val QR_BITMAP_SIZE = 512

    private const val MAGIC = "SMSFCLONE"
    private const val VERSION = "1"
    private const val MAX_TOTAL_CHUNKS = 2000
    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
    private val SESSION_REGEX = Regex("^[A-Za-z0-9_-]{4,64}$")

    data class QrPackage(
        val sessionId: String,
        val total: Int,
        val chunks: List<String>,
    )

    data class QrChunk(
        val sessionId: String,
        val index: Int,
        val total: Int,
        val sha256: String,
        val payload: String,
    )

    fun buildPackage(json: String): QrPackage {
        require(json.isNotBlank()) { "empty json" }
        val compressed = gzip(json.toByteArray(Charsets.UTF_8))
        val sha256 = sha256Hex(compressed)
        val encoded = Base64.encodeToString(compressed, BASE64_FLAGS)
        val payloadParts = encoded.chunked(CHUNK_SIZE)
        require(payloadParts.size <= MAX_TOTAL_CHUNKS) { "too many qr chunks: ${payloadParts.size}" }

        val sessionId = newSessionId()
        val total = payloadParts.size
        val chunks = payloadParts.mapIndexed { index, payload ->
            "$MAGIC|$VERSION|$sessionId|${index + 1}|$total|$sha256|$payload"
        }
        return QrPackage(sessionId, total, chunks)
    }

    fun parseChunk(text: String): QrChunk {
        val parts = text.trim().split("|", limit = 7)
        require(parts.size == 7) { "invalid qr format" }
        require(parts[0] == MAGIC) { "invalid qr magic" }
        require(parts[1] == VERSION) { "unsupported qr version" }

        val sessionId = parts[2]
        require(SESSION_REGEX.matches(sessionId)) { "invalid session" }

        val index = parts[3].toIntOrNull() ?: throw IllegalArgumentException("invalid chunk index")
        val total = parts[4].toIntOrNull() ?: throw IllegalArgumentException("invalid chunk total")
        require(total in 1..MAX_TOTAL_CHUNKS) { "invalid chunk total" }
        require(index in 1..total) { "chunk index out of range" }

        val sha256 = parts[5].lowercase()
        require(SHA256_REGEX.matches(sha256)) { "invalid sha256" }

        val payload = parts[6]
        require(payload.isNotBlank()) { "empty payload" }

        return QrChunk(sessionId, index, total, sha256, payload)
    }

    fun mergeChunks(chunks: Collection<QrChunk>): String {
        require(chunks.isNotEmpty()) { "no chunks" }
        val first = chunks.first()
        val byIndex = linkedMapOf<Int, QrChunk>()

        chunks.forEach { chunk ->
            require(chunk.sessionId == first.sessionId) { "session mismatch" }
            require(chunk.total == first.total) { "total mismatch" }
            require(chunk.sha256 == first.sha256) { "sha256 mismatch" }

            val old = byIndex[chunk.index]
            if (old != null) {
                require(old.payload == chunk.payload) { "duplicate chunk mismatch" }
            } else {
                byIndex[chunk.index] = chunk
            }
        }

        require(byIndex.size == first.total) { "missing chunks: ${byIndex.size}/${first.total}" }
        val encoded = (1..first.total).joinToString(separator = "") { index ->
            byIndex[index]?.payload ?: throw IllegalArgumentException("missing chunk: $index")
        }

        val compressed = try {
            Base64.decode(encoded, BASE64_FLAGS)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("invalid base64", e)
        }
        require(sha256Hex(compressed) == first.sha256) { "sha256 mismatch" }
        return String(gunzip(compressed), Charsets.UTF_8)
    }

    fun createQrBitmap(content: String, size: Int = QR_BITMAP_SIZE): Bitmap {
        require(size > 0) { "invalid qr size" }
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.L
        hints[EncodeHintType.MARGIN] = 1

        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }

        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(bytes)
        }
        return output.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
            gzip.readBytes()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val result = StringBuilder(digest.size * 2)
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            if (value < 16) result.append('0')
            result.append(value.toString(16))
        }
        return result.toString()
    }

    private fun newSessionId(): String {
        val timestamp = System.currentTimeMillis().toString(36)
        val random = UUID.randomUUID().toString().replace("-", "").take(8)
        return timestamp + random
    }
}
