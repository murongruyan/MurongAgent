package com.murong.agent.core.tool

/**
 * Stable Android package mapping used by the dedicated phone controller.
 *
 * Keep aliases here instead of asking a model to guess package names.
 */
object PhoneAgentApps {
    private val packages = linkedMapOf(
        "美团" to "com.sankuai.meituan",
        "饿了么" to "me.ele",
        "京东" to "com.jingdong.app.mall",
        "京东秒送" to "com.jingdong.app.mall",
        "淘宝" to "com.taobao.taobao",
        "淘宝闪购" to "com.taobao.taobao",
        "抖音" to "com.ss.android.ugc.aweme",
        "微信" to "com.tencent.mm",
        "支付宝" to "com.eg.android.AlipayGphone",
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "小红书" to "com.xingin.xhs",
        "哔哩哔哩" to "tv.danmaku.bili",
        "拼多多" to "com.xunmeng.pinduoduo",
        "携程" to "ctrip.android.view",
        "去哪儿" to "com.Qunar",
        "滴滴出行" to "com.sdu.didi.psnger",
        "肯德基" to "com.yek.android.kfc.activitys",
        "麦当劳" to "com.mcdonalds.gma.cn",
        "大众点评" to "com.dianping.v1"
    )

    fun packageFor(appOrPackage: String): String {
        val value = appOrPackage.trim()
        if (value.isBlank()) return value
        packages[value]?.let { return it }
        packages.entries.firstOrNull { (name, _) ->
            value.contains(name, ignoreCase = true)
        }?.let { return it.value }
        return value
    }

    fun labelForPackage(packageName: String?): String? {
        val value = packageName?.trim().orEmpty()
        if (value.isBlank()) return null
        return packages.entries.firstOrNull { it.value == value }?.key ?: value
    }

    fun supportedAppsDescription(): String = packages.entries
        .joinToString("、") { "${it.key}=${it.value}" }
}
