package org.payswap.camscan.tools.protection

// Rc4 (CAMSCAN-PROD-011 section 6.5): a documented, self-contained RC4
// implementation (identical key scheduling for encryption and decryption
// because RC4 is a XOR stream cipher - crypt == decrypt).
//
// Algorithm (public, from Rivest's RC4 as published):
//   KSA: S[i] = i for i in 0..255; j = 0; for i in 0..255:
//        j = (j + S[i] + key[i mod keyLen]) mod 256; swap S[i], S[j].
//   PRGA: i = j = 0; per output byte: i = (i + 1) mod 256;
//        j = (j + S[i]) mod 256; swap S[i], S[j];
//        K = S[(S[i] + S[j]) mod 256]; out = in XOR K.
//
// Known-answer tests in Rc4Test use the two public vectors from the RC4
// literature ("Key"/"Plaintext" and "Wiki"/"pedia").

/** Pure RC4 stream cipher. */
object Rc4 {

    /** Encrypts or decrypts [data] under [key]; returns a new array. */
    fun crypt(key: ByteArray, data: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "RC4 key must not be empty" }
        val s = IntArray(256)
        for (index in 0 until 256) {
            s[index] = index
        }
        var j = 0
        for (index in 0 until 256) {
            j = (j + s[index] + (key[index % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[index]
            s[index] = s[j]
            s[j] = tmp
        }
        val out = ByteArray(data.size)
        var i = 0
        j = 0
        for (index in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
            val k = s[(s[i] + s[j]) and 0xFF]
            out[index] = (data[index].toInt() xor k).toByte()
        }
        return out
    }

    /** Convenience overload for key strings (US-ASCII bytes). */
    fun crypt(key: String, data: ByteArray): ByteArray =
        crypt(key.toByteArray(Charsets.US_ASCII), data)
}
