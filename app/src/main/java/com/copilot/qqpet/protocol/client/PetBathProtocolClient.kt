package com.copilot.qqpet.protocol.client

import com.copilot.qqpet.engine.EngineLog
import com.copilot.qqpet.protocol.ProtoWire
import com.copilot.qqpet.protocol.channel.OidbChannel
import com.copilot.qqpet.protocol.model.BathItemConfig
import com.copilot.qqpet.protocol.model.BathResult

/**
 * 宠物洗澡、香皂道具商城与清洁度协议客户端
 */
class PetBathProtocolClient(
    private val channel: OidbChannel,
    private val onCleanUpdated: (newClean: Int) -> Unit = {}
) {
    companion object {
        private const val TAG = "PetBathProtocolClient"
    }

    fun bath(
        petId: String,
        cleanValue: Int = 100,
        stage: Int = 2,
        petUin: String = "",
        callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        val now = System.currentTimeMillis()
        val bodyBytes: ByteArray? = tryReflectBathBody(petId, petUin, cleanValue, now)
        if (bodyBytes == null) {
            EngineLog.w(TAG, "bath 宿主反射未就绪，严格落实 Safe-Fail 静默安全退出，不私造伪造数据包")
            callback(-1, null, "bath 宿主反射未就绪 (Safe-Fail)")
            return
        }
        channel.sendOidb("OidbSvcTrpcTcp.0x96a6_1", 38566, 1, bodyBytes) { code, data, err ->
            callback(code, data, err)
        }
    }

    private fun tryReflectBathBody(petId: String, petUin: String, cleanValue: Int, now: Long): ByteArray? {
        return try {
            val dCls = channel.classLoader.loadClass("ci5.d")
            val dInst = dCls.getDeclaredConstructor().newInstance()
            dCls.getField("a").set(dInst, petId)
            dCls.getField("b").set(dInst, petUin)
            val jCls = channel.classLoader.loadClass("uh5.j")
            val jInst = jCls.getDeclaredConstructor().newInstance()
            jCls.getField("a").set(jInst, 5000)
            jCls.getField("b").set(jInst, 500)
            jCls.getField("c").set(jInst, 501)
            dCls.getField("c").set(dInst, jInst)
            val iCls = channel.classLoader.loadClass("uh5.i")
            val iInst = iCls.getDeclaredConstructor().newInstance()
            iCls.getField("b").set(iInst, now - 3000L)
            iCls.getField("h").set(iInst, 1)
            dCls.getField("d").set(dInst, iInst)
            val bCls = channel.classLoader.loadClass("ci5.b")
            val bInst = bCls.getDeclaredConstructor().newInstance()
            bCls.getField("d").set(bInst, cleanValue)
            dCls.getField("e").set(dInst, bInst)
            val nanoCls = channel.classLoader.loadClass("com.google.protobuf.nano.MessageNano")
            val toByteArrayMethod = nanoCls.getMethod("toByteArray", nanoCls)
            toByteArrayMethod.invoke(null, dInst) as ByteArray
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }


    fun fetchBathItemConfig(callback: (code: Int, items: List<BathItemConfig>) -> Unit) {
        channel.sendOidb("OidbSvcTrpcTcp.0x9bf1_1", 39921, 1, ByteArray(0)) { code, data, err ->
            val list = mutableListOf<BathItemConfig>()
            if (code == 0 && data != null) {
                val itemBytesList = ProtoWire.allBytes(data, 1)
                for (bBytes in itemBytesList) {
                    val name = ProtoWire.firstString(bBytes, 1) ?: "香皂片"
                    val itemId = ProtoWire.firstString(bBytes, 2) ?: ""
                    val gold = (ProtoWire.firstVarint(bBytes, 5) ?: 5L).toInt()
                    val cleanVal = (ProtoWire.firstVarint(bBytes, 6) ?: 10L).toInt()
                    val defBuy = (ProtoWire.firstVarint(bBytes, 8) ?: 5L).toInt()
                    if (itemId.isNotEmpty()) {
                        list.add(BathItemConfig(itemId, name, gold, cleanVal, defBuy))
                    }
                }
                EngineLog.i("PetBathClient", "fetchBathItemConfig 成功: count=${list.size}")
            } else {
                EngineLog.w("PetBathClient", "fetchBathItemConfig 失败: code=$code, err=$err")
            }
            callback(code, list)
        }
    }

    fun fetchBathInventory(callback: (code: Int, balances: Map<String, Int>) -> Unit) {
        channel.sendOidb("OidbSvcTrpcTcp.0x9bf2_1", 39922, 1, ByteArray(0)) { code, data, err ->
            val map = linkedMapOf<String, Int>()
            if (code == 0 && data != null) {
                val invInfoBytes = ProtoWire.firstBytes(data, 1)
                val itemBytesList = ProtoWire.allBytes(invInfoBytes, 1)
                for (cBytes in itemBytesList) {
                    val itemId = ProtoWire.firstString(cBytes, 1) ?: ""
                    val balance = (ProtoWire.firstVarint(cBytes, 2) ?: 0L).toInt()
                    if (itemId.isNotEmpty()) {
                        map[itemId] = balance
                    }
                }
                EngineLog.i("PetBathClient", "fetchBathInventory 成功: balances=$map")
            } else {
                EngineLog.w("PetBathClient", "fetchBathInventory 失败: code=$code, err=$err")
            }
            callback(code, map)
        }
    }

    fun buyBathItem(
        petId: String,
        itemId: String,
        count: Int = 5,
        scene: Long = 21L,
        callback: (code: Int, orderResult: Int, errorMsg: String?) -> Unit
    ) {
        val itemIdLong = itemId.toLongOrNull() ?: 2010104L
        val userInfoBytes = ProtoWire.message()
            .writeVarint(1, 1L)
            .writeVarint(2, 1001L)
            .writeString(3, petId)
            .toByteArray()
        val mallItemBytes = ProtoWire.message()
            .writeVarint(1, 355L)
            .writeVarint(2, itemIdLong)
            .writeVarint(3, count.toLong())
            .toByteArray()
        val bodyBytes = ProtoWire.message()
            .writeBytes(1, userInfoBytes)
            .writeVarint(2, 1001L)
            .writeBytes(3, mallItemBytes)
            .writeVarint(4, scene)
            .toByteArray()

        channel.sendOidb("OidbSvcTrpcTcp.0x9bd0_0", 39888, 0, bodyBytes) { code, data, err ->
            val orderResult = if (code == 0 && data != null) {
                (ProtoWire.firstVarint(data, 1) ?: 0L).toInt()
            } else 0
            EngineLog.i("PetBathClient", "buyBathItem 回包: code=$code, orderResult=$orderResult, err=$err")
            callback(code, orderResult, err)
        }
    }

    fun doBathOnce(
        petId: String,
        itemId: String,
        useNum: Int = 1,
        petUin: String = "",
        callback: (BathResult) -> Unit
    ) {
        // DEF-14: 彻底移除废弃 0x9bf3_1 请求，洗澡逻辑切换为 0x96a6_1 (行为上报) 驱动
        bath(petId = petId, cleanValue = 100, stage = 2, petUin = petUin) { code, data, err ->
            if (code == 0) {
                if (petUin.isEmpty()) {
                    onCleanUpdated(100)
                }
                EngineLog.i(
                    TAG,
                    "doBathOnce 行为上报 (0x96a6_1) 成功: petId=$petId"
                )
                callback(BathResult(0, 100, 20, 0, true, null))
            } else {
                EngineLog.w(TAG, "doBathOnce 行为上报失败或无宿主支持 (Safe-Fail): code=$code, err=$err")
                callback(BathResult(code, -1, 0, -1, false, err))
            }
        }
    }
}
