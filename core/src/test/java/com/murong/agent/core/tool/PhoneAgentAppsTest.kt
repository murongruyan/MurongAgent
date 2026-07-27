package com.murong.agent.core.tool

import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneAgentAppsTest {
    @Test
    fun resolvesFoodDeliveryAliasesWithoutModelGuessingPackages() {
        assertEquals("com.sankuai.meituan", PhoneAgentApps.packageFor("美团"))
        assertEquals("me.ele", PhoneAgentApps.packageFor("请打开饿了么"))
        assertEquals("com.jingdong.app.mall", PhoneAgentApps.packageFor("京东秒送"))
        assertEquals("com.taobao.taobao", PhoneAgentApps.packageFor("淘宝闪购"))
    }

    @Test
    fun preservesExplicitPackageNames() {
        assertEquals(
            "com.example.custom",
            PhoneAgentApps.packageFor("com.example.custom")
        )
    }
}
