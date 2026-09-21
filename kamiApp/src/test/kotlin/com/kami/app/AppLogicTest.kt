package com.kami.app

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentsTest {
    @Test
    fun textMimeDetection() {
        assertTrue(Attachments.isTextMime("text/plain", "a.txt"))
        assertTrue(Attachments.isTextMime("", "notes.md"))
        assertTrue(Attachments.isTextMime("application/json", "d.json"))
        assertTrue(Attachments.isTextMime("application/octet-stream", "main.kt"))
        assertFalse(Attachments.isTextMime("application/octet-stream", "blob.bin"))
        assertFalse(Attachments.isTextMime("image/png", "p.png"))
    }

    @Test
    fun textPayloadDecodesUtf8() {
        val p = Attachments.payload("a.txt", "text/plain", "你好 hello".toByteArray(Charsets.UTF_8))!!
        assertEquals("你好 hello", p.text)
        assertNull(p.imageB64)
        assertEquals("a.txt", p.name)
    }

    @Test
    fun imagePayloadBase64AndCap() {
        val p = Attachments.payload("p.png", "image/png", byteArrayOf(1, 2, 3))!!
        assertEquals("AQID", p.imageB64)
        assertNull(p.text)
        assertNull(Attachments.payload("big.png", "image/png", ByteArray(Attachments.MAX_IMAGE_BYTES + 1)))
        assertNull(Attachments.payload("e.bin", "application/octet-stream", byteArrayOf(1)))
        assertNull(Attachments.payload("e.txt", "text/plain", ByteArray(0)))
    }

    @Test
    fun buildContentPlainWhenNoAttachments() {
        assertEquals("hi", Attachments.buildContent("hi", emptyList()))
    }

    @Test
    fun buildContentMultimodalParts() {
        val atts = listOf(
            AttachmentPayload("a.txt", "text/plain", "hello", null),
            AttachmentPayload("p.png", "image/png", null, "AQID"),
        )
        val parts = Attachments.buildContent("hi", atts) as org.json.JSONArray
        assertEquals(2, parts.length())
        val text = parts.getJSONObject(0)
        assertEquals("text", text.getString("type"))
        assertTrue(text.getString("text").startsWith("hi"))
        assertTrue(text.getString("text").contains("【附件 a.txt】"))
        assertTrue(text.getString("text").contains("hello"))
        val img = parts.getJSONObject(1)
        assertEquals("image_url", img.getString("type"))
        assertEquals("data:image/png;base64,AQID", img.getJSONObject("image_url").getString("url"))
    }

    @Test
    fun fallbackKeepsTextDropsImages() {
        val atts = listOf(
            AttachmentPayload("a.txt", "text/plain", "hello", null),
            AttachmentPayload("p.png", "image/png", null, "AQID"),
        )
        val fb = Attachments.fallbackText("hi", atts)
        assertTrue(fb.contains("【附件 a.txt】"))
        assertTrue(fb.contains("p.png"))
        assertTrue(fb.contains("无法被当前模型读取"))
        assertFalse(fb.contains("data:image"))
    }

    @Test
    fun summaryFormatsNames() {
        assertEquals("", Attachments.summary(emptyList()))
        assertEquals("\n📎 a.txt、b.png", Attachments.summary(listOf("a.txt", "b.png")))
    }
}

class L10nTest {
    @Test
    fun sFollowsLangState() {
        L10n.lang.value = L10n.ZH
        assertEquals("设置", L10n.s("设置", "Settings"))
        L10n.lang.value = L10n.EN
        assertEquals("Settings", L10n.s("设置", "Settings"))
        L10n.lang.value = L10n.ZH
    }
}

class TimetableStoreTest {
    @Test
    fun weekOfCountsStartWeekAsOne() {
        assertEquals(1, TimetableStore.weekOf("2026-09-14", LocalDate.parse("2026-09-14")))
        assertEquals(1, TimetableStore.weekOf("2026-09-14", LocalDate.parse("2026-09-20")))
        assertEquals(2, TimetableStore.weekOf("2026-09-14", LocalDate.parse("2026-09-21")))
    }

    @Test
    fun weekOfSurvivesBadStart() {
        assertEquals(1, TimetableStore.weekOf("not-a-date", LocalDate.parse("2026-09-14")))
    }

    @Test
    fun weekInRangeParsesRangesAndLists() {
        assertTrue(weekInRange("1-16", 1))
        assertTrue(weekInRange("1-16", 16))
        assertFalse(weekInRange("1-16", 17))
        assertTrue(weekInRange("1,3,5", 3))
        assertFalse(weekInRange("1,3,5", 4))
        assertTrue(weekInRange("1-8,11-16", 12))
        assertFalse(weekInRange("1-8,11-16", 10))
        assertTrue(weekInRange("", 99))
    }
}
