package io.github.reqpet.hook

/**
 * 原网络告密阻断与防踢防护盾已全面下线。
 *
 * 架构复盘与实测结论：
 * 1. 拦截图灵盾（TuringFD / TuringDID）或置空设备指纹，会导致腾讯网关直接判定为篡改/无头客户端并强制吊销会话 Token；
 * 2. 阻断 trpc.o3 告密上报会导致客户端探针挑战超时，触发频繁掉线；
 * 3. 客户端 NTKickProcessor.b 防踢仅为掩耳盗铃，云端注销无法通过客户端屏蔽阻止。
 *
 * 因此保持底层 SDK 与通信协议 100% 原生纯净，放行图灵盾正常生成硬件签名与上报，
 * 依赖上层业务逻辑（StealthScheduler 随机延时与拟人作息）防范时序聚类风控。
 */
object NetworkSecurityShield {
    fun install(classLoader: ClassLoader) {
        // 彻底下线所有激进 Hook，保持底层原生纯净放行
    }
}
