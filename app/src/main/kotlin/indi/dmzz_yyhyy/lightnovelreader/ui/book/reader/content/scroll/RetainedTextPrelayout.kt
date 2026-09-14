package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** 按正文保留任务；窗口移动只取消离开窗口的工作，不重启仍需使用的排版。 */
internal suspend fun <K, V : Any> Flow<List<K>>.preloadRetainedText(
    retain: (Set<K>) -> Unit,
    isPrepared: (K) -> Boolean,
    prepare: suspend (K) -> V?,
    publish: (K, V) -> Unit
) = coroutineScope {
    val jobs = mutableMapOf<K, Job>()
    collect { orderedKeys ->
        val keys = orderedKeys.toSet()
        retain(keys)
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val (key, job) = iterator.next()
            if (key !in keys || isPrepared(key)) {
                job.cancel()
                iterator.remove()
            }
        }
        for (key in keys) {
            if (isPrepared(key) || jobs[key]?.isActive == true) continue
            jobs[key] = launch {
                val prepared = prepare(key) ?: return@launch
                ensureActive()
                // 前台可能已经完成精确布局，不替换它正在使用的实例。
                if (!isPrepared(key)) publish(key, prepared)
            }
        }
    }
}
