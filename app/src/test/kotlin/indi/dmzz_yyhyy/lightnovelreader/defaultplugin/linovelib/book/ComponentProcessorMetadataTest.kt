package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book

import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.text.ComponentProcessor
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ComponentProcessorMetadataTest {
    @Test
    fun processKeepsTopLevelMetadata() {
        val content = buildJsonObject {
            put("linovelibParserWarning", "warning")
            putJsonArray("linovelibChapterPageMap") {
                addJsonObject {
                    put("chapterId", "12345")
                    put("startWeight", 0)
                    put("endWeight", 100)
                }
            }
            putJsonArray("components") {
                addJsonObject {
                    put("id", SimpleTextComponentData.ID)
                    put("data", SimpleTextComponentData("原文").toJsonElement())
                }
            }
        }
        val processor = ComponentProcessor(
            serializerMap = mapOf(SimpleTextComponentData.ID to SimpleTextComponentData.jsonSerializer),
            dataKClassMap = mapOf(SimpleTextComponentData.ID to SimpleTextComponentData::class),
            content = content
        )

        processor.process<SimpleTextComponentData> {
            SimpleTextComponentData("${it.text}已处理")
        }

        val processed = processor.get()
        val text = processed["components"]!!
            .jsonArray[0]
            .jsonObject["data"]!!
            .jsonObject["text"]!!
            .jsonPrimitive
            .content
        assertEquals("原文已处理", text)
        assertEquals("warning", processed["linovelibParserWarning"]?.jsonPrimitive?.content)
        assertNotNull(processed["linovelibChapterPageMap"])
    }
}
