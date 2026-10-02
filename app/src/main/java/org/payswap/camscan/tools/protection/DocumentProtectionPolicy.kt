package org.payswap.camscan.tools.protection

// DocumentProtectionPolicy (CAMSCAN-PROD-011 section 6.5): the sealed
// protection policy for a document. One protection record per document;
// the pinRecordId is the DOCUMENT id (the registry is keyed by document
// ids per the durable Document model, and one document carries at most
// one PIN record - documented).

/** Protection policy attached to a document. */
sealed class DocumentProtectionPolicy {

    /** No protection: the document is freely openable. */
    object Unprotected : DocumentProtectionPolicy() {
        override fun toString(): String = "DocumentProtectionPolicy.Unprotected"
    }

    /** A PIN must be verified before the document can be opened. */
    class PinRequired(val pinRecordId: String) : DocumentProtectionPolicy() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PinRequired) return false
            return pinRecordId == other.pinRecordId
        }

        override fun hashCode(): Int = pinRecordId.hashCode()

        override fun toString(): String =
            "DocumentProtectionPolicy.PinRequired[record=" + pinRecordId + "]"
    }
}
