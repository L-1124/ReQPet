package com.copilot.qqpet.protocol

import android.content.Context
import com.copilot.qqpet.hook.HookLog

/**
 * QQ 宠物发包嗅探器：
 * 在已完全实现 0x9ab2_1 与 0x9b60_1 官方全量动态协议后，主动禁用运行时 Method Hook，
 * 避免篡改 ART 虚拟机中的 ArtMethod 函数入口指针，从根源上避开 libfekit.so 内存扫描。
 */
object PacketSniffer {
    private const val TAG = "QQPetPacketSniffer"

    fun install(classLoader: ClassLoader, context: Context) {
        // 完全静默禁用：不进行任何运行时 Method Hook 操作，保持 ART 内存结构纯净
        HookLog.log(TAG, "已采用全动态协议自适应，运行时 Method Hook 已彻底关闭（纯净隐身模式）")
    }
}
