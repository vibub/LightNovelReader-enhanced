package indi.dmzz_yyhyy.lightnovelreader.data.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.ErrorContentComponentData
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.ImageComponentRender
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.ParagraphComponentRender
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.SimpleTextComponentRender
import indi.dmzz_yyhyy.lightnovelreader.data.plugin.injector.PluginInjectorProvider
import io.nightfish.lightnovelreader.api.content.ContentComponentRepositoryApi
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentRender
import io.nightfish.lightnovelreader.api.content.component.ComponentDataJsonElementSerializer
import io.nightfish.lightnovelreader.api.content.component.ComponentRender
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.content.component.data.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.data.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.data.ParagraphComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

@Singleton
class ContentComponentRepository @Inject constructor(
    val pluginInjectorProvider: PluginInjectorProvider
) : ContentComponentRepositoryApi, ComponentRender {
    companion object {
        const val TAG = "ContentComponentRepository"
    }

    private val serializeMutableMap =
        mutableMapOf<String, ComponentDataJsonElementSerializer<out AbstractContentComponentData>>()
    val serializeMap get() = serializeMutableMap.toMap()
    private val renderMutableMap =
        mutableMapOf<String, AnyContentComponentRender>()
    private val dataKClassMutableMap =
        mutableMapOf<String, KClass<out AbstractContentComponentData>>()
    val dataKClassMap get() = dataKClassMutableMap.toMap()

    fun getContentDataListFromJson(jsonObject: JsonObject): List<AbstractContentComponentData> =
        jsonObject["components"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.map { component ->
                val id = component["id"]?.jsonPrimitive?.content
                    ?.let {
                        if (it.contains(":")) return@let it
                        return@let "lightnovelreader:$it"
                    }
                    ?: return@map ErrorContentComponentData("component id not found")
                val data = component["data"]?.jsonObject
                    ?: return@map ErrorContentComponentData("component data not found\nid=$id")
                val serializer = serializeMutableMap[id]
                    ?: return@map ErrorContentComponentData("component data serializer not found\nid=$id")
                return@map serializer.fromJsonElement(data)
            } ?: listOf(ErrorContentComponentData("error to load components from json"))

    fun getContentDataFromJson(jsonObject: JsonObject): ReaderContentData {
        val injector = checkNotNull(pluginInjectorProvider.value)
        val context = injector.injectMap[android.content.Context::class.java] as android.content.Context
        val userData = injector.injectMap[io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi::class.java]
            as io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
        val dataList = getContentDataListFromJson(jsonObject)
        return ReaderContentData(dataList.mapIndexed { index, data ->
            if (data is SimpleTextComponentData) {
                indi.dmzz_yyhyy.lightnovelreader.data.content.component.SimpleTextComponent(data, userData, context)
            } else {
                val previous = dataList.getOrNull(index - 1)
                val next = dataList.getOrNull(index + 1)
                RenderContentComponent(
                    data, this,
                    hasParagraphBefore = previous is SimpleTextComponentData ||
                        (previous is ParagraphComponentData && previous.endsParagraph),
                    hasParagraphAfter = next is SimpleTextComponentData ||
                        (next is ParagraphComponentData && next.index == 1),
                    hasImageBefore = previous is ImageComponentData,
                    hasImageAfter = next is ImageComponentData
                )
            }
        })
    }

    interface Registrar : ContentComponentRepositoryApi.Registrar {
        override fun id(id: Identifier): RegisterBuilder
    }

    override val registrar = object : Registrar {
        override fun id(id: Identifier) =
            RegisterBuilder(
                pluginInjectorProvider,
                serializeMutableMap,
                renderMutableMap,
                dataKClassMutableMap,
                id
            )
    }

    @Suppress("UNCHECKED_CAST")
    class RegisterBuilder(
        private val pluginInjectorProvider: PluginInjectorProvider,
        private val serializerMap: MutableMap<String, ComponentDataJsonElementSerializer<out AbstractContentComponentData>>,
        private val renderMap: MutableMap<String, AnyContentComponentRender>,
        private val dataKClassMap: MutableMap<String, KClass<out AbstractContentComponentData>>,
        val id: Identifier
    ) : ContentComponentRepositoryApi.RegisterBuilder {
        var componentRender: (() -> AnyContentComponentRender?)? = null
        var componentDataKClass: KClass<out AbstractContentComponentData>? = null
        var serializer: ComponentDataJsonElementSerializer<out AbstractContentComponentData>? = null

        fun <Render, Data> Render.erase(): AnyContentComponentRender
                where Render : AbstractContentComponentRender<Data>,
                      Data : AbstractContentComponentData {
            return object : AnyContentComponentRender {

                @Suppress("UNCHECKED_CAST")
                @Composable
                override fun Content(
                    modifier: Modifier,
                    data: AbstractContentComponentData
                ) {
                    this@erase.Content(
                        modifier,
                        data as Data
                    )
                }
            }
        }

        override fun <Render, Data> component(
            value: KClass<Render>
        ): ContentComponentRepositoryApi.RegisterBuilder
                where Render : AbstractContentComponentRender<Data>,
                      Data : AbstractContentComponentData {
            this.componentRender = {
                pluginInjectorProvider.value!!.provide<Render>(value.java)?.erase()
            }
            return this
        }

        override fun data(value: KClass<out AbstractContentComponentData>): RegisterBuilder {
            this.componentDataKClass = value
            return this
        }

        override fun serializer(value: ComponentDataJsonElementSerializer<out AbstractContentComponentData>): RegisterBuilder {
            this.serializer = value
            return this
        }

        override fun register() {
            if (componentRender == null || componentDataKClass == null || serializer == null) throw Error(
                "builder missing parameters"
            )
            renderMap[id.toString()] = componentRender!!.invoke() ?: return
            dataKClassMap[id.toString()] = componentDataKClass!!
            serializerMap[id.toString()] = serializer!!
        }
    }

    fun forEachComponent(content: JsonObject, block: (AbstractContentComponentData) -> Unit) {
        content["components"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.forEach {
                val id = it["id"]?.jsonPrimitive?.content
                    ?.let { value -> if (":" in value) value else "lightnovelreader:$value" }
                    ?: return@forEach
                val data = it["data"]?.jsonObject
                    ?: return@forEach
                val serializer = serializeMap[id]
                    ?: return@forEach
                block(serializer.fromJsonElement(data))
            }
    }

    interface AnyContentComponentRender {
        @Composable
        fun Content(
            modifier: Modifier,
            data: AbstractContentComponentData
        )
    }

    @Composable
    override fun Component(
        modifier: Modifier,
        componentData: AbstractContentComponentData
    ) {
        if (componentData is ErrorContentComponentData) {
            indi.dmzz_yyhyy.lightnovelreader.data.content.component.ErrorContentComponentRender()
                .Content(modifier, componentData)
        } else {
            renderMutableMap[componentData.id.toString()]?.Content(modifier, componentData)
        }
    }

    fun initRegister() {
        // 保留旧章节缓存和 Linovelib 注释；新书源使用上游 paragraph 数据模型。
        registrar
            .id(SimpleTextComponentData.id)
            .component(SimpleTextComponentRender::class)
            .data(SimpleTextComponentData::class)
            .serializer(SimpleTextComponentData.jsonSerializer)
            .register()

        registrar
            .id(ParagraphComponentData.id)
            .component(ParagraphComponentRender::class)
            .data(ParagraphComponentData::class)
            .serializer(ParagraphComponentData.jsonSerializer)
            .register()

        registrar
            .id(ImageComponentData.id)
            .component(ImageComponentRender::class)
            .data(ImageComponentData::class)
            .serializer(ImageComponentData.jsonSerializer)
            .register()
    }
}