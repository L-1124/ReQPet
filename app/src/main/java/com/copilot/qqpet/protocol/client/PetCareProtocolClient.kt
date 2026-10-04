package com.copilot.qqpet.protocol.client

import com.copilot.qqpet.hook.HookLog as Log
import com.copilot.qqpet.protocol.ProtoWire
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.protocol.QQPetDirectBridge.PetAttributes
import com.copilot.qqpet.protocol.channel.OidbChannel
import com.copilot.qqpet.protocol.model.FeedDetailResult
import com.copilot.qqpet.protocol.model.FoodInventoryItem

/**
 * 宠物基础照料与属性维护协议客户端 (投喂、食物商城、三围拉取与状态同步)
 */
class PetCareProtocolClient(
    private val channel: OidbChannel,
    private val onAttributesUpdated: (PetAttributes) -> Unit = {},
    private val onOwnBagFound: (bagId: String) -> Unit = {}
) {
    companion object {
        private const val TAG = "PetCareProtocolClient"
        private const val DEFAULT_FOOD_ID = 9990032L
    }

    fun queryOwnPet(callback: (code: Int, petId: String?, rawData: ByteArray?) -> Unit) {
        channel.sendOidb("OidbSvcTrpcTcp.0x95e1_0", 38369, 0, ByteArray(0)) { code, data, _ ->
            var petId: String? = null
            if (code == 0 && data != null) {
                val allStrings = ProtoWire.extractAllStrings(data)
                Log.i(TAG, "0x95e1_0 回包所有字符串: $allStrings")
                val petBytes = ProtoWire.firstBytes(data, 1)
                petId = ProtoWire.firstString(petBytes, 101)
                val bagFromPet = ProtoWire.firstString(ProtoWire.firstBytes(petBytes, 21), 1)?.trim().orEmpty()
                val bagFromRoot = ProtoWire.firstString(ProtoWire.firstBytes(data, 21), 1)?.trim().orEmpty()
                val ownBag = bagFromPet.ifEmpty { bagFromRoot }
                if (ownBag.isNotEmpty()) {
                    onOwnBagFound(ownBag)
                    Log.i(TAG, "🧧 [0x95e1_0] 在本人主宠资料中捕获到地面福袋: $ownBag")
                }
            }
            callback(code, petId, data)
        }
    }

    fun resolveFoodId(): Long {
        try {
            val mgrCls = channel.classLoader.loadClass("com.tencent.ergo.view.mainpage.util.PetHomeResourceManager")
            val mgrInst = mgrCls.getField("a").get(null)
            val mObj = mgrCls.getMethod("j").invoke(mgrInst) ?: return DEFAULT_FOOD_ID
            val aField = mObj.javaClass.getField("a")
            val map = aField.get(mObj) as? Map<*, *>
            if (!map.isNullOrEmpty()) {
                for (entry in map.values) {
                    if (entry == null) continue
                    val arr = entry.javaClass.getField("a").get(entry) as? Array<*>
                    val first = arr?.firstOrNull()
                    val strVal = first?.javaClass?.getField("a")?.get(first) as? String
                    val fId = strVal?.toLongOrNull()
                    if (fId != null && fId > 0L) {
                        Log.d(TAG, "从 PetHomeResourceManager 成功解析到动态 foodId: $fId")
                        return fId
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "从 PetHomeResourceManager 获取动态 foodId 失败: ${t.message}")
        }
        return DEFAULT_FOOD_ID
    }

    fun feed(
        petId: String,
        foodId: Long = 0L,
        petUin: String = "",
        foodItemId: String = "",
        callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        val targetFoodId = if (foodId > 0L) foodId else resolveFoodId()
        var bodyBytes: ByteArray? = null
        if (petUin.isEmpty() && foodItemId.isEmpty()) {
            bodyBytes = tryReflectFeedBody(petId, targetFoodId)
        }
        if (bodyBytes == null) {
            bodyBytes = buildProtoFeedBody(petId, targetFoodId, petUin, foodItemId)
        }
        channel.sendOidb("OidbSvcTrpcTcp.0x992d_1", 39213, 1, bodyBytes) { code, data, err ->
            callback(code, data, err)
        }
    }

    private fun tryReflectFeedBody(petId: String, targetFoodId: Long): ByteArray? {
        return try {
            val bCls = channel.classLoader.loadClass("zh5.b")
            val bInst = bCls.newInstance()
            bCls.getField("a").set(bInst, "")
            bCls.getField("b").set(bInst, "")
            bCls.getField("c").set(bInst, "")
            bCls.getField("d").set(bInst, petId)
            bCls.getField("e").set(bInst, targetFoodId.toInt())
            val nanoCls = channel.classLoader.loadClass("com.google.protobuf.nano.MessageNano")
            val toByteArrayMethod = nanoCls.getMethod("toByteArray", nanoCls)
            toByteArrayMethod.invoke(null, bInst) as ByteArray
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildProtoFeedBody(petId: String, targetFoodId: Long, petUin: String, foodItemId: String): ByteArray {
        val extBytes = ProtoWire.message()
            .writeVarint(6, 1L)
            .writeVarint(13, 0L)
            .toByteArray()
        val msg = ProtoWire.message()
            .writeString(1, petUin)
            .writeString(2, "")
            .writeString(3, "")
            .writeString(4, petId)
            .writeVarint(5, targetFoodId)
            .writeBytes(10, extBytes)
        if (foodItemId.isNotEmpty()) {
            msg.writeString(11, foodItemId)
        }
        return msg.toByteArray()
    }

    fun feedDetailed(
        petId: String,
        foodId: Long = 0L,
        petUin: String = "",
        foodItemId: String = "",
        callback: (FeedDetailResult) -> Unit
    ) {
        feed(petId, foodId, petUin, foodItemId) { code, data, err ->
            var feedState = 0
            var tipText: String? = null
            if (data != null) {
                feedState = (ProtoWire.firstVarint(data, 1) ?: 0L).toInt()
                val rawTip = ProtoWire.firstString(data, 3)
                tipText = if (!rawTip.isNullOrBlank()) rawTip else null
            }
            Log.i(TAG, "🥣 feedDetailed 回包: petId=$petId, code=$code, feedState=$feedState, tip=$tipText")
            callback(FeedDetailResult(code, feedState, tipText, err))
        }
    }

    fun fetchFoodInventory(
        callback: (code: Int, remain: Int, total: Int, items: List<FoodInventoryItem>) -> Unit
    ) {
        channel.sendOidb("OidbSvcTrpcTcp.0x9949_1", 39241, 1, ByteArray(0)) { code, data, err ->
            val items = mutableListOf<FoodInventoryItem>()
            var remain = 0
            var total = 0
            if (code == 0 && data != null) {
                remain = (ProtoWire.firstVarint(data, 1) ?: 0L).toInt()
                total = (ProtoWire.firstVarint(data, 2) ?: 0L).toInt()
                val itemNodes = ProtoWire.allBytes(data, 4)
                for (node in itemNodes) {
                    val name = ProtoWire.firstString(node, 1) ?: "爱心饼干"
                    val balance = (ProtoWire.firstVarint(node, 2) ?: 0L).toInt()
                    val itemId = ProtoWire.firstString(node, 4) ?: ""
                    val energyVal = (ProtoWire.firstVarint(node, 7) ?: 20L).toInt()
                    if (itemId.isNotEmpty()) {
                        items.add(FoodInventoryItem(itemId, name, balance, if (energyVal > 0) energyVal else 20))
                    }
                }
                Log.i(TAG, "🥣 fetchFoodInventory 成功: remain=$remain, total=$total, items=${items.size}")
            } else {
                Log.w(TAG, "🥣 fetchFoodInventory 失败: code=$code, err=$err")
            }
            callback(code, remain, total, items)
        }
    }

    fun queryFeedTimes(callback: (code: Int, remain: Int, total: Int) -> Unit) {
        val bodyBytes = tryReflectFeedTimesBody() ?: ByteArray(0)
        channel.sendOidb("OidbSvcTrpcTcp.0x9949_1", 39241, 1, bodyBytes) { code, data, err ->
            if (code == 0 && data != null) {
                val remain = (ProtoWire.firstVarint(data, 1) ?: 0L).toInt()
                val total = (ProtoWire.firstVarint(data, 2) ?: 0L).toInt()
                Log.i(TAG, "📊 查询喂食状态回包: remain=$remain, total=$total")
                callback(0, remain, total)
                return@sendOidb
            }
            Log.w(TAG, "📊 查询喂食状态失败: code=$code, err=$err")
            callback(code, 0, 0)
        }
    }

    private fun tryReflectFeedTimesBody(): ByteArray? {
        return try {
            val dCls = channel.classLoader.loadClass("zh5.d")
            val dInst = dCls.newInstance()
            val nanoCls = channel.classLoader.loadClass("com.google.protobuf.nano.MessageNano")
            val toByteArrayMethod = nanoCls.getMethod("toByteArray", nanoCls)
            toByteArrayMethod.invoke(null, dInst) as ByteArray
        } catch (_: Throwable) {
            null
        }
    }

    fun queryPetAttributes(
        petId: String,
        isSelf: Boolean = true,
        callback: (code: Int, attrs: PetAttributes?) -> Unit
    ) {
        val bodyBytes = ProtoWire.message()
            .writeString(1, petId)
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x96f2_1", 38642, 1, bodyBytes) { code, data, err ->
            if (code == 0 && data != null) {
                val displayBytes = ProtoWire.firstBytes(data, 1)
                if (displayBytes != null) {
                    val attrs = parseAttributesFromDisplayBytes(displayBytes)
                    if (isSelf) {
                        onAttributesUpdated(attrs)
                        captureOwnBagFromProfile(data, displayBytes)
                    }
                    Log.i(TAG, "📊 实时三围: energy=${attrs.energy}/${attrs.maxEnergy}, clean=${attrs.clean}/${attrs.maxClean}")
                    callback(0, attrs)
                    return@sendOidb
                }
            }
            Log.w(TAG, "📊 查询实时三围失败 (petId=$petId): code=$code, err=$err")
            callback(code, null)
        }
    }

    private fun parseAttributesFromDisplayBytes(displayBytes: ByteArray): PetAttributes {
        val feelingBytes = ProtoWire.firstBytes(displayBytes, 1)
        val hungerBytes = ProtoWire.firstBytes(displayBytes, 2)
        val cleanBytes = ProtoWire.firstBytes(displayBytes, 3)
        val moodCur = if (feelingBytes != null) (ProtoWire.firstFloat(feelingBytes, 3) ?: 0f) else 0f
        val energyMax = if (hungerBytes != null) (ProtoWire.firstFloat(hungerBytes, 2) ?: 100f) else 100f
        val energyCur = if (hungerBytes != null) (ProtoWire.firstFloat(hungerBytes, 3) ?: 0f) else 0f
        val cleanMax = if (cleanBytes != null) (ProtoWire.firstFloat(cleanBytes, 2) ?: 100f) else 100f
        val cleanCur = if (cleanBytes != null) (ProtoWire.firstFloat(cleanBytes, 3) ?: 0f) else 0f
        return PetAttributes(
            energy = energyCur,
            maxEnergy = if (energyMax > 0f) energyMax else 100f,
            clean = cleanCur,
            maxClean = if (cleanMax > 0f) cleanMax else 100f,
            mood = moodCur
        )
    }

    private fun captureOwnBagFromProfile(data: ByteArray, displayBytes: ByteArray) {
        val bagFromRoot = ProtoWire.firstString(ProtoWire.firstBytes(data, 21), 1)?.trim().orEmpty()
        val bagFromDisplay = ProtoWire.firstString(ProtoWire.firstBytes(displayBytes, 21), 1)?.trim().orEmpty()
        val ownBag = bagFromRoot.ifEmpty { bagFromDisplay }
        if (ownBag.isNotEmpty()) {
            onOwnBagFound(ownBag)
            Log.i(TAG, "🧧 [0x96f2_1] 实时捕获到地面福袋: $ownBag")
        }
    }

    fun buyFood(
        petId: String,
        count: Long = 5L,
        itemType: String = "1",
        callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeVarint(1, count)
            .writeString(2, petId)
            .writeString(3, itemType)
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x99df_1", 39391, 1, body) { code, data, errorMsg ->
            Log.i(TAG, "buyFood 回包: code=$code, err=$errorMsg")
            callback(code, data, errorMsg)
        }
    }

    fun refreshProfile(callback: ((code: Int) -> Unit)? = null) {
        channel.sendOidb("OidbSvcTrpcTcp.0x99f2_1", 39410, 1, ByteArray(0)) { code, _, _ ->
            Log.d(TAG, "refreshProfile 回包: code=$code")
            callback?.invoke(code)
        }
    }

    fun getPetAttributes(petId: String): PetAttributes? {
        try {
            val mgrCls = channel.classLoader.loadClass("com.tencent.ergo.user.DisplayValueManager")
            val mgrInst = mgrCls.getField("a").get(null) ?: return null
            val cMethod = mgrCls.getMethod("c")
            val liveData = cMethod.invoke(mgrInst) ?: return null
            val displayObj = liveData.javaClass.getMethod("getValue").invoke(liveData) ?: return null

            var energy = -1f
            var maxEnergy = 100f
            var clean = -1f
            var maxClean = 100f
            var mood = 0f

            for (m in displayObj.javaClass.methods) {
                if (m.parameterTypes.isEmpty() && m.returnType.name.endsWith("\$c")) {
                    val cVal = m.invoke(displayObj)
                    if (cVal != null) {
                        val cur = (cVal.javaClass.getMethod("b").invoke(cVal) as? Number)?.toFloat() ?: 0f
                        val max = (cVal.javaClass.getMethod("d").invoke(cVal) as? Number)?.toFloat() ?: 100f
                        when (m.name) {
                            "f" -> { energy = cur; maxEnergy = max }
                            "c" -> { clean = cur; maxClean = max }
                            "d" -> { mood = cur }
                        }
                    }
                }
            }
            if (energy >= 0f || clean >= 0f) {
                val attrs = PetAttributes(energy, maxEnergy, clean, maxClean, mood)
                onAttributesUpdated(attrs)
                return attrs
            }
        } catch (t: Throwable) {
            Log.w(TAG, "反射读取宠物属性异常: ${t.message}")
        }
        return null
    }
}
