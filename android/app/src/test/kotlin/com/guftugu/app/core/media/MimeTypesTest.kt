package com.guftugu.app.core.media

import com.guftugu.app.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTypesTest {
    @Test
    fun `content type follows the mime family`() {
        assertEquals(ContentType.IMAGE, MimeTypes.contentTypeFor("image/jpeg"))
        assertEquals(ContentType.IMAGE, MimeTypes.contentTypeFor("IMAGE/PNG"))
        assertEquals(ContentType.IMAGE, MimeTypes.contentTypeFor("image/gif; charset=binary"))
        assertEquals(ContentType.VIDEO, MimeTypes.contentTypeFor("video/mp4"))
        assertEquals(ContentType.AUDIO, MimeTypes.contentTypeFor("audio/mp4"))
        assertEquals(ContentType.FILE, MimeTypes.contentTypeFor("application/pdf"))
        assertEquals(ContentType.FILE, MimeTypes.contentTypeFor(null))
        assertEquals(ContentType.FILE, MimeTypes.contentTypeFor(""))
        assertEquals(ContentType.FILE, MimeTypes.contentTypeFor("garbage"))
    }

    @Test
    fun `octet-stream falls back to the file name extension`() {
        assertEquals(ContentType.IMAGE, MimeTypes.contentTypeFor("application/octet-stream", "IMG_0001.JPG"))
        assertEquals(ContentType.VIDEO, MimeTypes.contentTypeFor(null, "clip.mkv"))
        assertEquals(ContentType.AUDIO, MimeTypes.contentTypeFor(null, "note.m4a"))
        assertEquals(ContentType.FILE, MimeTypes.contentTypeFor(null, "report.pdf"))
        assertEquals(ContentType.FILE, MimeTypes.contentTypeFor(null, "noext"))
    }

    @Test
    fun `normalize strips parameters and lower-cases`() {
        assertEquals("image/jpeg", MimeTypes.normalize(" Image/JPEG; q=0.9 "))
        assertEquals(MimeTypes.OCTET_STREAM, MimeTypes.normalize(null))
        assertEquals(MimeTypes.OCTET_STREAM, MimeTypes.normalize("text"))
    }

    @Test
    fun `extensions round trip`() {
        assertEquals("jpg", MimeTypes.extensionFor("image/jpeg"))
        assertEquals("m4a", MimeTypes.extensionFor("audio/mp4"))
        assertEquals("mp4", MimeTypes.extensionFor("video/mp4"))
        assertEquals("pdf", MimeTypes.extensionFor("application/pdf"))
        assertEquals("bin", MimeTypes.extensionFor("application/vnd.some-very-long+thing"))
        assertEquals("bin", MimeTypes.extensionFor(null))
        assertEquals("jpeg", MimeTypes.extensionOf("a/b/photo.JPEG"))
        assertNull(MimeTypes.extensionOf(".hidden"))
        assertNull(MimeTypes.extensionOf("trailingdot."))
        assertNull(MimeTypes.extensionOf(null))
        assertEquals("image/png", MimeTypes.guessFromName("x.png"))
        assertNull(MimeTypes.guessFromName("x.unknownext"))
    }

    @Test
    fun `family predicates`() {
        assertTrue(MimeTypes.isImage("image/webp"))
        assertTrue(MimeTypes.isGif("image/gif"))
        assertFalse(MimeTypes.isGif("image/png"))
        assertTrue(MimeTypes.isVideo("video/3gpp"))
        assertTrue(MimeTypes.isAudio("audio/ogg"))
        assertFalse(MimeTypes.isAudio("video/mp4"))
    }

    @Test
    fun `human sizes`() {
        assertEquals("0 B", MimeTypes.formatSize(0))
        assertEquals("812 B", MimeTypes.formatSize(812))
        assertEquals("1 KB", MimeTypes.formatSize(1024))
        assertEquals("12.4 KB", MimeTypes.formatSize(12_700))
        assertEquals("3.1 MB", MimeTypes.formatSize(3_250_000))
        assertEquals("120 MB", MimeTypes.formatSize(125_829_120))
        assertEquals("1.5 GB", MimeTypes.formatSize(1_610_612_736))
    }
}
