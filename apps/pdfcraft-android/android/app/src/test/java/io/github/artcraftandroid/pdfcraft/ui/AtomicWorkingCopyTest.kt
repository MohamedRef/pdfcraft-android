package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.engine.publishWorkingCopy
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AtomicWorkingCopyTest {
    @Test fun failedPublicationKeepsBothThePreviousCopyAndTheCompletedStage() {
        val dir = Files.createTempDirectory("pdfcraft-copy").toFile()
        try {
            val old = File(dir, "document.pdf").apply { writeBytes(byteArrayOf(1,2,3)) }
            val stage = File(dir, ".document.part").apply { writeBytes(byteArrayOf(4,5)) }
            assertThrows(IOException::class.java) { publishWorkingCopy(stage,old) { _,_ -> false } }
            assertArrayEquals(byteArrayOf(1,2,3),old.readBytes())
            assertArrayEquals(byteArrayOf(4,5),stage.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun atomicReplacementPreservesExistingMappedReaders() {
        val dir = Files.createTempDirectory("pdfcraft-copy").toFile()
        try {
            val old = File(dir,"document.pdf").apply { writeBytes(byteArrayOf(1,2,3)) }
            val stage = File(dir,".document.part").apply { writeBytes(byteArrayOf(4,5)) }
            RandomAccessFile(old,"r").use { file ->
                val previous = file.channel.map(FileChannel.MapMode.READ_ONLY,0,3)
                publishWorkingCopy(stage,old)
                assertArrayEquals(byteArrayOf(4,5),old.readBytes())
                assertArrayEquals(byteArrayOf(1,2,3),ByteArray(3).also { previous.get(it) })
                assertFalse(stage.exists())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun publicationRejectsTheSameFileAndAStageFromAnotherDirectory() {
        val dir = Files.createTempDirectory("pdfcraft-copy").toFile()
        try {
            val target = File(dir,"document.pdf").apply { writeBytes(byteArrayOf(1,2,3)) }
            val other = File(dir,"other").apply { mkdir() }
            val stage = File(other,"stage.pdf").apply { writeBytes(byteArrayOf(4,5)) }
            assertThrows(IOException::class.java) { publishWorkingCopy(target,target) }
            assertThrows(IOException::class.java) { publishWorkingCopy(stage,target) }
            assertArrayEquals(byteArrayOf(1,2,3),target.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
