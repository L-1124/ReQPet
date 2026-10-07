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
        var bodyBytes: ByteArray? = tryReflectBathBody(petId, petUin, cleanValue, now)
        if (bodyBytes == null) {
            bodyBytes = buildProtoBathBody(petId, petUin, cleanValue, now)
        }
        channel.sendOidb("OidbSvcTrpcTcp.0x96a6_1", 38566, 1, bodyBytes) { code, data, err ->
            callback(code, data, err)
        }
    }

    private fun tryReflectBathBody(petId: String, petUin: String, cleanValue: Int, now: Long): ByteArray? {
        return try {
            val dCls = channel.classLoader.loadClass("ci5.d")
            val dInst = dCls.newInstance()
            dCls.getField("a").set(dInst, petId)
            dCls.getField("b").set(dInst, petUin)
            val jCls = channel.classLoader.loadClass("uh5.j")
            val jInst = jCls.newInstance()
            jCls.getField("a").set(jInst, 5000)
            jCls.getField("b").set(jInst, 500)
            jCls.getField("c").set(jInst, 501)
            dCls.getField("c").set(dInst, jInst)
            val iCls = channel.classLoader.loadClass("uh5.i")
            val iInst = iCls.newInstance()
            iCls.getField("b").set(iInst, now - 3000L)
            iCls.getField("h").set(iInst, 1)
            dCls.getField("d").set(dInst, iInst)
            val bCls = channel.classLoader.loadClass("ci5.b")
            val bInst = bCls.newInstance()
            bCls.getField("d").set(bInst, cleanValue)
            dCls.getField("e").set(dInst, bInst)
            val nanoCls = channel.classLoader.loadClass("com.google.protobuf.nano.MessageNano")
            val toByteArrayMethod = nanoCls.getMethod("toByteArray", nanoCls)
            toByteArrayMethod.invoke(null, dInst) as ByteArray
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildProtoBathBody(petId: String, petUin: String, cleanValue: Int, now: Long): ByteArray {
        val pathBytes = ProtoWire.message()
            .writeVarint(1, 5000L)
            .writeVarint(2, 500L)
            .writeVarint(3, 501L)
            .toByteArray()
        val exeExtBytes = ProtoWire.message()
            .writeVarint(7, now - 3000L)
            .writeVarint(13, 1L)
            .toByteArray()
        val extBytes = ProtoWire.message()
            .writeVarint(5, cleanValue.toLong())
            .toByteArray()
        return ProtoWire.message()
            .writeString(1, petId)
            .writeString(2, petUin)
            .writeBytes(3, pathBytes)
            .writeBytes(4, exeExtBytes)
            .writeBytes(5, extBytes)
            .toByteArray()
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
        val bodyBytes = ProtoWire.message()
            .writeString(1, petId)
            .writeString(2, itemId)
            .writeVarint(3, useNum.toLong())
            .writeString(4, petUin)
            .toByteArray()

        channel.sendOidb("OidbSvcTrpcTcp.0x9bf3_1", 39923, 1, bodyBytes) { code, data, err ->
            if (code == 0 && data != null) {
                val newClean = (ProtoWire.firstVarint(data, 1) ?: 0L).toInt()
                val addedClean = (ProtoWire.firstVarint(data, 2) ?: 0L).toInt()
                val remainBalance = (ProtoWire.firstVarint(data, 3) ?: 0L).toInt()
                val isFullClean = (ProtoWire.firstVarint(data, 4) ?: 0L) != 0L
                if (petUin.isEmpty()) {
                    onCleanUpdated(newClean)
                }
                EngineLog.i("PetBathClient", "doBathOnce 成功: newClean=$newClean, added=$addedClean, remain=$remainBalance")
                callback(BathResult(0, newClean, addedClean, remainBalance, isFullClean, null))
            } else {
                EngineLog.w("PetBathClient", "doBathOnce 失败: code=$code, err=$err")
                callback(BathResult(code, -1, 0, -1, false, err))
            }
        }
    }
}
