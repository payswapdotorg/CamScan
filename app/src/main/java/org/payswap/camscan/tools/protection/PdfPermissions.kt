package org.payswap.camscan.tools.protection

// PdfPermissions (CAMSCAN-PROD-011 section 6.5): the documented minimal
// permission set for the PDF standard security handler. ONLY printing is
// controllable; every other permission is denied.
//
// The P integer is derived from the public PDF specification's user
// access permission bit table (1-based bit positions, value 2^(bit-1)):
//   - bits 1-2 (values 1, 2): reserved, MUST be 0;
//   - bit 3 (value 4): printing allowed;
//   - bit 4 (value 8): modify contents - DENIED here;
//   - bit 5 (value 16): copy/extract - DENIED here;
//   - bit 6 (value 32): modify annotations - DENIED here;
//   - bits 7-8 (values 64, 128): reserved, MUST be 1;
//   - bit 9 (256): fill forms - DENIED here;
//   - bit 10 (512): extract for accessibility - DENIED here;
//   - bit 11 (1024): assemble - DENIED here;
//   - bit 12 (2048): high-quality printing - DENIED here.
//
// Therefore:
//   - printing allowed:  P = 4 + 64 + 128 = 196;
//   - nothing allowed:   P = 64 + 128 = 192.

/** Minimal PDF permission set: printing on/off, everything else denied. */
class PdfPermissions(val printingAllowed: Boolean) {

    /** The exact P integer per the spec bit table documented above. */
    val pValue: Int = if (printingAllowed) P_PRINTING_ONLY else P_NOTHING

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PdfPermissions) return false
        return printingAllowed == other.printingAllowed
    }

    override fun hashCode(): Int = printingAllowed.hashCode()

    override fun toString(): String =
        "PdfPermissions[printing=" + printingAllowed + ";P=" + pValue + "]"

    companion object {
        /** Print allowed (bit 3) + reserved bits 7-8 set per the spec. */
        const val P_PRINTING_ONLY: Int = 196

        /** All permissions denied, reserved bits 7-8 still set per the spec. */
        const val P_NOTHING: Int = 192

        /** Decodes the printing bit (bit 3, value 4) of a raw P value. */
        fun printingAllowedOf(pValue: Int): Boolean = (pValue and 4) != 0

        val PRINTING_ALLOWED: PdfPermissions = PdfPermissions(true)
        val NOTHING_ALLOWED: PdfPermissions = PdfPermissions(false)
    }
}
