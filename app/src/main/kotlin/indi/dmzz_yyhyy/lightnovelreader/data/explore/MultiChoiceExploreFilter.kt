package indi.dmzz_yyhyy.lightnovelreader.data.explore

import io.nightfish.lightnovelreader.api.util.LocalString
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter

internal class MultiChoiceExploreFilter(
    private val title: LocalString,
    val dialogTitle: LocalString,
    val description: LocalString,
    val choices: List<String>,
    val defaultChoice: String,
    val maxSelections: Int
) : Filter<Set<String>>(setOf(defaultChoice)) {
    override fun getTitle(): LocalString = title

    fun normalizeSelection(selection: Set<String>): Set<String> {
        if (defaultChoice in selection) return setOf(defaultChoice)
        return choices
            .filter(selection::contains)
            .take(maxSelections)
            .toCollection(linkedSetOf())
            .ifEmpty { linkedSetOf(defaultChoice) }
    }
}
