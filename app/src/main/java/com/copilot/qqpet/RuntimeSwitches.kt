package com.copilot.qqpet

/** 运行期总开关：默认关闭，关闭时模块不发任何请求。reloadConfig 写入，发包闸门读取。 */
object RuntimeSwitches {
    @Volatile
    var masterEnabled: Boolean = false
}
