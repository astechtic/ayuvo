package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.DuplicateReason

/** §15 dHash + MinHash, ported from the reference (dhash_hex, minhash_signature, near_duplicate). */
object NearDuplicate {
    const val WIDTH = 9
    const val HEIGHT = 8
    const val MAX_HAMMING = 6

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    fun fnv1a64(data: ByteArray, seed: Long = FNV_OFFSET): Long {
        var h = seed
        for (b in data) {
            h = h xor (b.toLong() and 0xff)
            h *= FNV_PRIME
        }
        return h
    }

    fun le64(x: Long): ByteArray = ByteArray(8) { i -> (x ushr (8 * i)).toByte() }

    fun hex(x: Long): String = java.lang.Long.toHexString(x).padStart(16, '0')

    /** 8 rows × 9 columns of 0..255, row-major ([gray] has 72 values); bit = gray[r][c] > gray[r][c+1], MSB first. */
    fun dHash(gray: IntArray): String {
        require(gray.size == WIDTH * HEIGHT) { "dhash needs 8 rows of 9 values" }
        var v = 0L
        for (r in 0 until HEIGHT) for (c in 0 until 8) {
            v = (v shl 1) or (if (gray[r * WIDTH + c] > gray[r * WIDTH + c + 1]) 1L else 0L)
        }
        return hex(v)
    }

    fun hamming(a: String, b: String): Int =
        java.lang.Long.bitCount(java.lang.Long.parseUnsignedLong(a, 16) xor java.lang.Long.parseUnsignedLong(b, 16))

    /** Distinct 5-word shingles of words(fold(text)), sorted; 1–4 words → one shingle; none → empty. */
    fun shingles(text: String): List<String> {
        val ws = RecordText.words(RecordText.fold(text))
        if (ws.isEmpty()) return emptyList()
        if (ws.size < 5) return listOf(ws.joinToString(" "))
        return (0..ws.size - 5).map { ws.subList(it, it + 5).joinToString(" ") }.toSortedSet().toList()
    }

    private val SEEDS: LongArray by lazy { LongArray(64) { fnv1a64(le64((it + 1).toLong())) } }

    /** 64 comma-separated hex slots, or "" when the text has no words. */
    fun minhashSignature(text: String): String {
        val sh = shingles(text)
        if (sh.isEmpty()) return ""
        val mins = LongArray(64) { -1L }
        for (x in sh) {
            val base = le64(fnv1a64(x.toByteArray(Charsets.UTF_8)))
            for (k in 0 until 64) {
                val v = fnv1a64(base, SEEDS[k])
                if (java.lang.Long.compareUnsigned(v, mins[k]) < 0) mins[k] = v
            }
        }
        return mins.joinToString(",") { hex(it) }
    }

    /** Facade: signature of a record's text, null when it has no words. */
    fun textSignature(foldedText: String): String? = minhashSignature(foldedText).ifEmpty { null }

    fun signatureSimilarity(a: String?, b: String?): Double {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return 0.0
        val xa = a.split(',')
        val xb = b.split(',')
        if (xa.size != 64 || xb.size != 64) return 0.0
        return (0 until 64).count { xa[it] == xb[it] } / 64.0
    }

    data class Decision(val candidate: Boolean, val reason: String?, val score: Double)

    fun nearDuplicate(phashA: String?, phashB: String?, sigA: String?, sigB: String?): Decision {
        val sim = signatureSimilarity(sigA, sigB)
        if (!phashA.isNullOrEmpty() && !phashB.isNullOrEmpty()) {
            val hd = hamming(phashA, phashB)
            if (hd <= MAX_HAMMING && (sim >= 0.9 || (sigA.isNullOrEmpty() && sigB.isNullOrEmpty()))) {
                return Decision(true, "phash", RecordText.round2(if (!sigA.isNullOrEmpty()) sim else 1 - hd / 64.0))
            }
        }
        if (sim >= 0.95) return Decision(true, "content", RecordText.round2(sim))
        return Decision(false, null, RecordText.round2(sim))
    }

    fun isCandidate(phashA: String?, phashB: String?, sigA: String?, sigB: String?): Pair<DuplicateReason, Double>? {
        val d = nearDuplicate(phashA, phashB, sigA, sigB)
        if (!d.candidate) return null
        return (if (d.reason == "phash") DuplicateReason.PHASH else DuplicateReason.CONTENT) to d.score
    }
}
