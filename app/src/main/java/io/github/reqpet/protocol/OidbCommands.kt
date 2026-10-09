package io.github.reqpet.protocol

/**
 * QQ 宠物 OIDB 协议接口清单（已由开源社区与逆向实测验证）
 */
object OidbCommands {
    data class Command(val name: String, val cmd: Int, val subCmd: Int, val description: String)

    // 1. 查询类接口
    val OWN_PROFILE = Command("OidbSvcTrpcTcp.0x99f2_1", 39410, 1, "查询自身宠物属性（体力、清洁、心情、金币）")
    val STORY_STATUS = Command("OidbSvcTrpcTcp.0x975a_1", 38746, 1, "查询正在进行的任务剩余时间（学习/打工/冒险倒计时）")
    val BATH_INVENTORY = Command("OidbSvcTrpcTcp.0x9bf2_1", 39922, 1, "查询洗护用品库存（香皂、沐浴球）")
    val FEED_TIMES = Command("OidbSvcTrpcTcp.0x9949_1", 39241, 1, "查询今日喂食次数与限制")

    // 2. 状态照顾接口
    val FEED = Command("OidbSvcTrpcTcp.0x992d_1", 39213, 1, "喂食饼干提升体力")
    val BUY_BATH_ITEM = Command("OidbSvcTrpcTcp.0x9bd0_0", 39888, 0, "购买洗澡用品")

    // 3. 学习与打工
    val SCHOOL_START = Command("OidbSvcTrpcTcp.0x975e_1", 38750, 1, "开始指定课程学习")
    val WORK_START = Command("OidbSvcTrpcTcp.0x975e_1", 38750, 1, "开始指定地点打工")
    val STORY_SETTLE = Command("OidbSvcTrpcTcp.0x9760_1", 38752, 1, "任务完成收尾结算（领取收益与经验）")
    val STORY_ENCOURAGE = Command("OidbSvcTrpcTcp.0x9c44_1", 40004, 1, "学习/打工过程中鼓励宠物提升心情")

    // 4. 冒险与好友互动
    val ADVENTURE_START = Command("OidbSvcTrpcTcp.0x975e_1", 38750, 1, "发起冒险探索")
    val FRIEND_POKE = Command("OidbSvcTrpcTcp.0x985b_0", 39003, 0, "好友宠物互动踩踩")
    val REPORT_EVENT = Command("OidbSvcTrpcTcp.0x96a6_1", 38566, 1, "行为埋点上报（维持与客户端行为一致）")
}
