package com.kami.app

import org.json.JSONObject
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

class SmCryptoTest {

    private fun ct(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun sm3StandardVector() {
        // GM/T 0004 附录 A："abc"
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            SmCrypto.hex(SmCrypto.sm3(ct("abc"))),
        )
    }

    @Test
    fun sm2InteropsWithGmssl() {
        // PC gmssl CryptSM2(mode=1) 用 GM/T 0003.5 标准示例私钥生成的密文
        val sk = "3945208F7B2144B13F36E38AC6D39F95889393692860B51A42FB81EF4DF7C5B8"
        val ctHex = "04b691b90e9e9229b20f1c4118b10d8247d8b18b542be1bfd67c0ee37a536afc1e0" +
            "2d62d5841ef04006d99bf813b32efb3b6c353be00b8d881744d80840c2d3fdedb4f3d9bedfcd4b8b5c51351d05a448c5b61e01a900cd869fc44ccbfb579017660d329f42bafb30b2bbccf019d"
        assertEquals("kami-kat-2026", String(SmCrypto.decryptHex(sk, ctHex), Charsets.UTF_8))
    }

    @Test
    fun sm2EncryptRoundTripAndFormat() {
        val pub = "0409f9df311e5421a150dd7d161e4bc5c672179fad1833fc076bb08ff356f35020" +
            "ccea490ce26775a52dc6ea718cc1aa600aed05fbf35e084a6632f6072da9ad13"
        val msg = "统一认证密码-π".toByteArray(Charsets.UTF_8)
        val k = java.math.BigInteger("5911e5f20ac1c1e9f36ad9e6b6dbe7d59b3a5eb1cf9fe1bf4ecb1a5d4fd1e0d1", 16)
        val out = SmCrypto.encryptHex(pub, msg, k)
        assertTrue(out.startsWith("04"))
        assertEquals(2 * (65 + 32 + msg.size), out.length)
        assertTrue(
            SmCrypto.decryptHex(
                "3945208F7B2144B13F36E38AC6D39F95889393692860B51A42FB81EF4DF7C5B8",
                out,
            ).contentEquals(msg),
        )
    }

    @Test
    fun totpRfcVector() {
        // RFC 6238 SHA1 测试种子（ASCII "12345678901234567890"），t=59 → 6 位 287082
        assertEquals("287082", SmCrypto.totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59L))
    }
}

class ModelCatalogTest {

    private val js = """
        (function(){
          var e = {
            modelList: [
              {value: "DeepSeek-V4-Flash", label: "DeepSeek V4 Flash", supportImage: !0,
               thinkingParam: "thinking", effortOptions: ["low", "high"], thinkingField: "reasoning_content"},
              {value: "DeepSeek-R1-W8A8", label: "R1 W8A8", supportImage: !1,
               thinkingParam: null, effortOptions: ["medium"]},
              {value: "Qwen3-32B", label: "Qwen3", supportImage: !1,
               thinkingParam: "enable_thinking", effortOptions: []}
            ]
          };
        })()
    """.trimIndent()

    @Test
    fun parsesModelListMetadata() {
        val raw = ModelCatalog.parseModelList(js)
        assertEquals(3, raw.size)
        assertEquals("DeepSeek-V4-Flash", raw[0].value)
        assertEquals("DeepSeek V4 Flash", raw[0].label)
        assertTrue(raw[0].supportImage)
        assertEquals("thinking", raw[0].thinkingParam)
        assertEquals(listOf("low", "high"), raw[0].effortOptions)
        assertEquals(null, raw[1].thinkingParam)
        assertEquals(listOf("medium"), raw[1].effortOptions)
        assertEquals("enable_thinking", raw[2].thinkingParam)
        assertEquals(emptyList<String>(), raw[2].effortOptions)
    }

    @Test
    fun expandsRouterVariants() {
        val v = ModelCatalog.expand(ModelCatalog.parseModelList(js))
        assertEquals(
            setOf(
                "DeepSeek-V4-Flash", "DeepSeek-V4-Flash-nothink", "DeepSeek-V4-Flash-think-low", "DeepSeek-V4-Flash-think-high",
                "DeepSeek-R1-W8A8", "DeepSeek-R1-W8A8-effort-medium",
                "Qwen3-32B", "Qwen3-32B-nothink", "Qwen3-32B-think-on",
            ),
            v.keys,
        )
        val think = v["DeepSeek-V4-Flash-think-high"]!!
        assertEquals("DeepSeek-V4-Flash", think.base)
        assertEquals(true, think.thinking)
        assertEquals("high", think.effort)
        assertEquals("thinking", think.thinkingParam)
        assertEquals(true, think.supportImage)
        val effortOnly = v["DeepSeek-R1-W8A8-effort-medium"]!!
        assertEquals("DeepSeek-R1-W8A8", effortOnly.base)
        assertEquals(null, effortOnly.thinking)
        assertEquals(null, effortOnly.thinkingParam)
        assertEquals("medium", effortOnly.effort)
        val nothink = v["Qwen3-32B-nothink"]!!
        assertEquals("Qwen3-32B", nothink.base)
        assertEquals(false, nothink.thinking)
    }

    @Test
    fun injectionMatchesRouterSemantics() {
        val v = ModelCatalog.expand(ModelCatalog.parseModelList(js))
        fun body(id: String): JSONObject = ModelCatalog.applyTo(JSONObject().put("model", id), id, v[id])
        // think+effort: kwargs true + reasoning_effort
        val b1 = body("DeepSeek-V4-Flash-think-high")
        assertEquals("DeepSeek-V4-Flash", b1.getString("model"))
        assertEquals(true, b1.getJSONObject("chat_template_kwargs").getBoolean("thinking"))
        assertEquals("high", b1.getString("reasoning_effort"))
        // nothink: kwargs false, 无 effort
        val b2 = body("DeepSeek-V4-Flash-nothink")
        assertEquals("DeepSeek-V4-Flash", b2.getString("model"))
        assertEquals(false, b2.getJSONObject("chat_template_kwargs").getBoolean("thinking"))
        assertFalse(b2.has("reasoning_effort"))
        // effort-only（无思考开关）: 仅 reasoning_effort
        val b3 = body("DeepSeek-R1-W8A8-effort-medium")
        assertEquals("DeepSeek-R1-W8A8", b3.getString("model"))
        assertEquals("medium", b3.getString("reasoning_effort"))
        assertFalse(b3.has("chat_template_kwargs"))
        // 原样透传变体与非目录模型：body 不动
        val b4 = body("DeepSeek-V4-Flash")
        assertFalse(b4.has("chat_template_kwargs"))
        assertFalse(b4.has("reasoning_effort"))
        assertEquals("DeepSeek-V4-Flash", b4.getString("model"))
        val b5 = body("whatever-model")
        assertEquals("whatever-model", b5.getString("model"))
    }
}
