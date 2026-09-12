package indi.dmzz_yyhyy.lightnovelreader.data.content.component

/** 只保留阅读窗口中的内容；调用方在主线程访问，后台任务只提交完整结果。 */
internal class RetainedLayoutCache<K, V> {
    private val values = mutableMapOf<K, V>()
    private var retained: Set<K>? = null

    operator fun get(key: K): V? = values[key]

    fun put(key: K, value: V) {
        if (retained?.contains(key) != false) values[key] = value
    }

    fun retain(keys: Set<K>) {
        retained = keys.toSet()
        values.keys.retainAll(keys)
    }
}
