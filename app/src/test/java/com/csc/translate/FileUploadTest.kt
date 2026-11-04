package com.csc.translate

import com.csc.translate.session.mimeFromFileName
import com.csc.translate.session.safeUploadFileName
import org.junit.Assert.assertEquals
import org.junit.Test

class FileUploadTest {
    @Test
    fun prefersResolverMimeWhenSpecific() {
        assertEquals("audio/mp4", mimeFromFileName("clip.m4a", "audio/mp4"))
    }

    @Test
    fun guessesFromExtensionWhenResolverIsGeneric() {
        assertEquals("audio/mpeg", mimeFromFileName("talk.mp3", "application/octet-stream"))
        assertEquals("audio/wav", mimeFromFileName("take.wav", null))
        assertEquals("audio/ogg", mimeFromFileName("note.opus", ""))
    }

    @Test
    fun sanitizesUploadFileName() {
        assertEquals("voice.m4a", safeUploadFileName("voice.m4a"))
        assertEquals("dir_a_b.wav", safeUploadFileName("dir/a\\b.wav"))
        assertEquals("audio", safeUploadFileName("   "))
    }
}
