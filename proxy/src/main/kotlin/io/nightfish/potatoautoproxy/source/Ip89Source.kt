package io.nightfish.potatoautoproxy.source

import io.nightfish.potatoautoproxy.Proxy
import io.nightfish.potatoautoproxy.ProxySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

object Ip89Source : ProxySource {
    override suspend fun getProxies(): List<Proxy> = withContext(Dispatchers.IO) {
        return@withContext Jsoup
            .connect("https://api.89ip.cn/tqdl.html?api=1&num=40&port=&address=&isp=")
            .ignoreContentType(true)
            .get()
            .body()
            .text()
            .replace("更好用的代理ip请访问：www.qiyunip.com", "")
            .split(" ")
            .filter(String::isNotBlank)
            .map(Proxy::fromString)
    }
}
