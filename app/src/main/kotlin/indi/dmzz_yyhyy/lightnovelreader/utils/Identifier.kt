package indi.dmzz_yyhyy.lightnovelreader.utils

import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.identifier.toId

fun String.convertOldId(): Identifier {
    if (this == "-791439186") return "Wenku8".ofId()
    if (this == LinovelibConstants.LEGACY_SOURCE_ID.toString()) return LinovelibConstants.SOURCE_ID
    this.toId()
        .onOk {
            return it
        }
    return this.ofId()
}

internal fun String.ofId() = Identifier("lightnovelreader", this)