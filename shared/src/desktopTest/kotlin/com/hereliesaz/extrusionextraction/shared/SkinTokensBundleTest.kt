package com.hereliesaz.extrusionextraction.shared

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.*

class SkinTokensBundleTest {
    private val component=SkinTokensComponent("fixture",3,"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    @Test fun verifiesAndCopiesKnownSha256Fixture() {
        val output=ByteArrayOutputStream()
        verifySkinTokensStream("abc".byteInputStream(),component,output)
        assertContentEquals(byteArrayOf(97,98,99),output.toByteArray())
    }
    @Test fun rejectsWrongSizeOrDigest() {
        for(text in listOf("ab","abcd","abd")) assertFailsWith<IllegalArgumentException>{
            verifySkinTokensStream(text.byteInputStream(),component)
        }
    }
    @Test fun cancellationPropagates() {
        val failure=assertFailsWith<IllegalStateException>{
            verifySkinTokensStream("abc".byteInputStream(),component){error("cancel")}
        }
        assertEquals("cancel",failure.message)
    }
    @Test fun readinessRequiresEveryExactLength() {
        val folder=Files.createTempDirectory("ee-pins-test").toFile()
        try {
            assertFalse(skinTokensBundlePresent(folder))
            for(c in skinTokensComponents) RandomAccessFile(File(folder,c.name),"rw").use{it.setLength(c.bytes)}
            assertTrue(skinTokensBundlePresent(folder))
            RandomAccessFile(File(folder,"tokenrig.gguf"),"rw").use{it.setLength(1)}
            assertFalse(skinTokensBundlePresent(folder))
        } finally {folder.deleteRecursively()}
    }
    @Test fun publishedBundleTotal() {
        assertEquals(1250390624L,skinTokensComponents.sumOf{it.bytes})
        assertTrue(skinTokensComponents.all{it.sha256.matches(Regex("[a-f0-9]{64}"))})
    }
}
