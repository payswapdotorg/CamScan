package org.payswap.camscan.document.persistence

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** JVM tests for the file-backed ContentStore (temp dir, no android.*). */
class FileContentStoreTest {

private lateinit var root: File
private lateinit var store: FileContentStore

@Before
fun setUp() {
root = Files.createTempDirectory("contentstore").toFile()
store = FileContentStore(root)
}

@Test
fun putOpenRoundtrip_bytesIdentical() {
val bytes = byteArrayOf(0, 1, 2, -1, 127, -128, 42, 0, 0, 7)
val ref = store.put(bytes)
assertTrue(ref.endsWith(".bin"))
assertArrayEquals(bytes, store.open(ref)!!)
}

@Test
fun put_usesInjectedNamer_forGeneratedRefs() {
var counter = 0
val named = FileContentStore(root) { "blob-${++counter}" }
assertEquals("blob-1.bin", named.put(byteArrayOf(1)))
assertEquals("blob-2.bin", named.put(byteArrayOf(2)))
}

@Test
fun existsAndDelete_lifecycle_secondDeleteFalse() {
val ref = store.put(byteArrayOf(9, 9))
assertTrue(store.exists(ref))
assertTrue(store.delete(ref))
assertFalse(store.exists(ref))
assertFalse(store.delete(ref))
}

@Test
fun put_sameHint_overwritesAtomically_noTmpLeftovers() {
val ref = store.put(byteArrayOf(1, 2, 3), "page-0")
val refAgain = store.put(byteArrayOf(4, 5, 6, 7), "page-0")
assertEquals(ref, refAgain)
assertArrayEquals(byteArrayOf(4, 5, 6, 7), store.open(ref)!!)
assertTrue(root.listFiles()!!.none { it.name.endsWith(".tmp") })
}

@Test
fun open_unknownRef_returnsNull() {
assertNull(store.open("missing.bin"))
assertNull(store.open("no-such-file"))
}

@Test
fun traversalRefs_areRefused_onAllVerbs() {
assertFalse(store.exists("../index.json"))
assertNull(store.open("../index.json"))
assertFalse(store.delete("..%2Findex.json"))
assertNull(store.open("..\\index.json"))
// A hostile hint cannot escape rootDir: separators are outside the ref
// vocabulary, so the put falls back to a generated in-root name.
val ref = store.put(byteArrayOf(1), "../escape")
assertTrue(ref.endsWith(".bin"))
assertFalse(ref.contains(".."))
assertTrue(store.exists(ref))
}

@Test
fun put_invalidHint_fallsBackToGeneratedName() {
val ref = store.put(byteArrayOf(1), "///")
assertTrue(ref.endsWith(".bin"))
assertNotEquals("///", ref)
assertArrayEquals(byteArrayOf(1), store.open(ref)!!)
}

}
