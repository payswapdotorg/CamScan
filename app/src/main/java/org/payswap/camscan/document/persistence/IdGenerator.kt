package org.payswap.camscan.document.persistence

/**

Injectable id minting — deterministic under test fakes. Production wires
UUIDs; tests wire counting generators.
*/
fun interface IdGenerator {
fun newId(): String
}
