package io.github.reqpet.protocol.client

import io.github.reqpet.engine.EngineLog
import io.github.reqpet.protocol.ProtoWire
import io.github.reqpet.protocol.ProtoWireText
import io.github.reqpet.protocol.QQPetDirectBridge
import io.github.reqpet.protocol.QQPetDirectBridge.PetAttributes
import io.github.reqpet.protocol.channel.OidbChannel
import io.github.reqpet.protocol.model.FeedDetailResult
import io.github.reqpet.protocol.model.FoodInventoryItem
import io.github.reqpet.protocol.model.PetProfileDetail
import io.github.reqpet.engine.AccountSessionGuard
import io.github.reqpet.engine.PetAdventureEngine
import io.github.reqpet.engine.state.AccountSessionStore
import java.lang.reflect.Modifier

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
        private val FEED_PB_CANDIDATES = listOf("p54.b", "t64.b", "zh5.b")
        internal fun validateFeedPbSchema(candidateCls: Class<*>, nanoCls: Class<*>): Boolean {
            if (!nanoCls.isAssignableFrom(candidateCls)) return false
            return try {
                candidateCls.getDeclaredConstructor()
                val fieldA = candidateCls.getField("a")
                val fieldB = candidateCls.getField("b")
                val fieldC = candidateCls.getField("c")
                val fieldD = candidateCls.getField("d")
                val fieldE = candidateCls.getField("e")

                !Modifier.isStatic(fieldA.modifiers) &&
                        !Modifier.isStatic(fieldB.modifiers) &&
                        !Modifier.isStatic(fieldC.modifiers) &&
                        !Modifier.isStatic(fieldD.modifiers) &&
                        !Modifier.isStatic(fieldE.modifiers) &&
                        fieldA.type == String::class.java &&
                        fieldB.type == String::class.java &&
                        fieldC.type == String::class.java &&
                        fieldD.type == String::class.java &&
                        fieldE.type == Int::class.javaPrimitiveType
            } catch (_: Throwable) {
                false
            }
        }

        internal fun findFeedPbClass(classLoader: ClassLoader): Class<*>? {
            val nanoCls = try {
                classLoader.loadClass("com.google.protobuf.nano.MessageNano")
            } catch (_: Throwable) {
                return null
            }

            var matched: Class<*>? = null
            var matchCount = 0
            for (candidate in FEED_PB_CANDIDATES) {
                try {
                    val cls = classLoader.loadClass(candidate)
                    if (validateFeedPbSchema(cls, nanoCls)) {
                        matched = cls
                        matchCount++
                    }
                } catch (_: Throwable) {
                }
            }
            if (matchCount > 1) {
                EngineLog.w(TAG, "检测到多个符合结构的喂食 PB 候选类歧义，执行 Fail-Closed")
                return null
            }

            return matched
        }
    }

    fun queryOwnPet(callback: (code: Int, petId: String?, rawData: ByteArray?) -> Unit) {
        channel.sendOidb("OidbSvcTrpcTcp.0x95e1_0", 38369, 0, ByteArray(0)) { code, data, _ ->
            var petId: String? = null
            if (code == 0 && data != null) {
                val allStrings = ProtoWireText.extractAllStrings(data)
                EngineLog.i(TAG, "0x95e1_0 回包所有字符串: $allStrings")
                val petBytes = ProtoWire.firstBytes(data, 1)
                // DEF-22: 对齐宿主 UserPetManager.q()，优先提取 Tag 101 (c2.i)
                var resolvedPetId = ProtoWire.firstString(petBytes, 101)?.takeIf { it.isNotBlank() }
                if (resolvedPetId == null && petBytes != null) {
                    // 若为空，兜底提取 Tag 4 Profile (c2.d) 嵌套下的 Tag 8 (c2.d.h)
                    val profileBytes = ProtoWire.firstBytes(petBytes, 4)
                    if (profileBytes != null) {
                        resolvedPetId = ProtoWire.firstString(profileBytes, 8)?.takeIf { it.isNotBlank() }
                        if (resolvedPetId != null) {
                            EngineLog.i(TAG, "从 Tag 4 Profile 嵌套下的 Tag 8 成功兜底解析到老号 petId: $resolvedPetId")
                        }
                    }
                }
                // 顶层兜底（防止层级差异）
                if (resolvedPetId == null) {
                    val rootProfileBytes = ProtoWire.firstBytes(data, 4)
                    if (rootProfileBytes != null) {
                        resolvedPetId = ProtoWire.firstString(rootProfileBytes, 8)?.takeIf { it.isNotBlank() }
                    }
                    if (resolvedPetId == null) {
                        resolvedPetId = ProtoWire.firstString(data, 101)?.takeIf { it.isNotBlank() }
                    }
                }
                petId = resolvedPetId

                val bagFromPet = ProtoWire.firstString(ProtoWire.firstBytes(petBytes, 21), 1)?.trim().orEmpty()
                val bagFromRoot = ProtoWire.firstString(ProtoWire.firstBytes(data, 21), 1)?.trim().orEmpty()
                val ownBag = bagFromPet.ifEmpty { bagFromRoot }
                if (ownBag.isNotEmpty()) {
                    val activeUin = PetAdventureEngine.currentActiveUin.ifEmpty { channel.resolveUin(petId.orEmpty()) }
                    AccountSessionStore.saveGroundBagId(activeUin, ownBag)
                    onOwnBagFound(ownBag)
                    EngineLog.i(TAG, "[0x95e1_0] 在本人主宠资料中捕获到地面福袋: $ownBag (uin=$activeUin)")
                }
            }
            callback(code, petId, data)
        }
    }

    fun queryPetProfile(
        petId: String = "",
        callback: (code: Int, profile: PetProfileDetail?, rawData: ByteArray?) -> Unit
    ) {
        val body = if (petId.isBlank()) {
            ByteArray(0)
        } else {
            ProtoWire.message().writeString(1, petId).toByteArray()
        }
        channel.sendOidb("OidbSvcTrpcTcp.0x95e1_0", 38369, 0, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val profile = parsePetProfileDetail(petId, data)
                callback(0, profile, data)
            } else {
                EngineLog.w(TAG, "queryPetProfile 失败 (petId=$petId): code=$code, err=$errorMsg")
                callback(code, null, data)
            }
        }
    }

    internal fun parsePetProfileDetail(targetPetId: String, data: ByteArray): PetProfileDetail {
        val petBytes = ProtoWire.firstBytes(data, 1) ?: data
        val species = ProtoWire.firstString(petBytes, 1).orEmpty()
        val levelNode = ProtoWire.firstBytes(petBytes, 4)
        val level = (ProtoWire.firstVarint(levelNode, 1) ?: 0L).toInt()
        val nameNode = ProtoWire.firstBytes(petBytes, 7)
        val petName = ProtoWire.firstString(nameNode, 1).orEmpty()
        val resolvedPetId = ProtoWire.firstString(petBytes, 101)?.trim().orEmpty().ifEmpty { targetPetId }
        return PetProfileDetail(
            petId = resolvedPetId,
            species = species,
            petName = petName,
            level = level
        )
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
                    if (fId != null && fId == DEFAULT_FOOD_ID) {
                        EngineLog.d(TAG, "从 PetHomeResourceManager 验证官方白名单 foodId: $fId")
                        return fId
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            EngineLog.w(TAG, "从 PetHomeResourceManager 获取动态 foodId 失败: ${t.message}")
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
        // DEF-16: 食物 ID 严格锁定官方白名单 9990032L
        val targetFoodId = if (foodId == DEFAULT_FOOD_ID) DEFAULT_FOOD_ID else resolveFoodId()
        val bodyBytes = tryReflectFeedBody(petId, targetFoodId, petUin)
        if (bodyBytes == null) {
            // 若宿主反射未命中，严禁发送私造畸形字节流，直接执行 Safe-Fail 记录日志并安全退出
            EngineLog.w(TAG, "feed: 宿主反射未命中，严格落实 Safe-Fail 静默安全退出，严禁私造字节流")
            callback(-1, null, "feed 宿主反射未命中 (Safe-Fail)")
            return
        }
        channel.sendOidb("OidbSvcTrpcTcp.0x992d_1", 39213, 1, bodyBytes, callback)
    }

    @Volatile
    private var cachedFeedPbClass: Class<*>? = null

    internal fun resolveFeedPbClass(): Class<*>? {
        val cached = cachedFeedPbClass
        if (cached != null) return cached
        synchronized(this) {
            val existing = cachedFeedPbClass
            if (existing != null) return existing
            val resolved = findFeedPbClass(channel.classLoader)
            if (resolved != null) {
                cachedFeedPbClass = resolved
            }
            return resolved
        }
    }

    internal fun tryReflectFeedBody(
        petId: String,
        targetFoodId: Long,
        petUin: String = ""
    ): ByteArray? {
        return try {
            val bCls = resolveFeedPbClass() ?: return null
            val bInst = bCls.getDeclaredConstructor().newInstance()

            bCls.getField("a").set(bInst, petUin)
            bCls.getField("b").set(bInst, "")
            bCls.getField("c").set(bInst, "")
            bCls.getField("d").set(bInst, petId)
            bCls.getField("e").set(bInst, targetFoodId.toInt())

            val nanoCls = channel.classLoader.loadClass("com.google.protobuf.nano.MessageNano")
            val toByteArrayMethod = nanoCls.getMethod("toByteArray", nanoCls)
            toByteArrayMethod.invoke(null, bInst) as ByteArray
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EngineLog.w(TAG, "反射构造喂食 PB 异常: ${e.message}")
            null
        }
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
            EngineLog.i(
                "PetCareClient",
                "feedDetailed 回包: petId=$petId, code=$code, feedState=$feedState, tip=$tipText"
            )
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
                EngineLog.i(
                    "PetCareClient",
                    "fetchFoodInventory 成功: remain=$remain, total=$total, items=${items.size}"
                )
            } else {
                EngineLog.w("PetCareClient", "fetchFoodInventory 失败: code=$code, err=$err")
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
                EngineLog.i("PetCareClient", "查询喂食状态回包: remain=$remain, total=$total")
                callback(0, remain, total)
                return@sendOidb
            }
            EngineLog.w("PetCareClient", "查询喂食状态失败: code=$code, err=$err")
            callback(code, 0, 0)
        }
    }

    private fun tryReflectFeedTimesBody(): ByteArray? {
        val candidates = listOf("p54.d", "t64.d", "zh5.d")
        for (candidate in candidates) {
            try {
                val dCls = channel.classLoader.loadClass(candidate)
                val dInst = dCls.getDeclaredConstructor().newInstance()
                val nanoCls = channel.classLoader.loadClass("com.google.protobuf.nano.MessageNano")
                val toByteArrayMethod = nanoCls.getMethod("toByteArray", nanoCls)
                return toByteArrayMethod.invoke(null, dInst) as ByteArray
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        return null
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
                    EngineLog.i(
                        "PetCareClient",
                        "实时三围: energy=${attrs.energy}/${attrs.maxEnergy}, clean=${attrs.clean}/${attrs.maxClean}"
                    )
                    callback(0, attrs)
                    return@sendOidb
                }
            }
            EngineLog.w("PetCareClient", "查询实时三围失败 (petId=$petId): code=$code, err=$err")
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
            val activeUin = PetAdventureEngine.currentActiveUin.ifEmpty { channel.getCurrentRuntimeUin() }
            AccountSessionStore.saveGroundBagId(activeUin, ownBag)
            onOwnBagFound(ownBag)
            EngineLog.i("PetCareClient", "[0x96f2_1] 实时捕获到地面福袋: $ownBag (uin=$activeUin)")
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
        channel.sendOidb("OidbSvcTrpcTcp.0x99df_1", 39391, 1, body, callback)
    }

    fun refreshProfile(callback: ((code: Int) -> Unit)? = null) {
        channel.sendOidb("OidbSvcTrpcTcp.0x99f2_1", 39410, 1, ByteArray(0)) { code, _, _ ->
            EngineLog.d("PetCareClient", "refreshProfile 回包: code=$code")
            callback?.invoke(code)
        }
    }

    fun getPetAttributes(petId: String): PetAttributes? {
        try {
            val mgrCls = channel.classLoader.loadClass("com.tencent.ergo.user.DisplayValueManager")
            val mgrInst = try {
                mgrCls.getField("a").get(null)
            } catch (_: Throwable) {
                try {
                    mgrCls.getField("INSTANCE").get(null)
                } catch (_: Throwable) {
                    null
                }
            } ?: return null

            var displayObj: Any? = null

            // 1. 优先调用全量快照方法 "d" (DEF-15: 修复误调单事件方法 "c")
            try {
                val dMethod = mgrCls.getMethod("d")
                val liveData = dMethod.invoke(mgrInst)
                if (liveData != null) {
                    val v = liveData.javaClass.getMethod("getValue").invoke(liveData)
                    if (v != null && (v.javaClass.name.contains("DisplayValues") || v.toString()
                            .contains("DisplayValues"))
                    ) {
                        displayObj = v
                    }
                }
            } catch (_: Throwable) {
            }

            // 2. 特征自适应动态探测：遍历返回 LiveData 且载荷对象包含 "DisplayValues" 的无参方法
            if (displayObj == null) {
                for (m in mgrCls.methods) {
                    if (m.parameterTypes.isEmpty() && m.returnType.name.contains("LiveData")) {
                        try {
                            val liveData = m.invoke(mgrInst) ?: continue
                            val v = liveData.javaClass.getMethod("getValue").invoke(liveData) ?: continue
                            if (v.javaClass.name.contains("DisplayValues") || v.toString().contains("DisplayValues")) {
                                displayObj = v
                                break
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            }

            if (displayObj == null) {
                EngineLog.w(TAG, "DisplayValueManager 未能定位到有效 DisplayValues 快照对象 (Safe-Fail)")
                return null
            }

            var energy = -1f
            var maxEnergy = 100f
            var clean = -1f
            var maxClean = 100f
            var mood = 0f

            // 3. 提取 hunger (体力), clean (清洁), feel (心情)
            var hungerObj: Any? = null
            var cleanObj: Any? = null
            var feelObj: Any? = null

            // 3.1 语义名称探测 (优先匹配含 hunger, clean, feel/mood 的无参方法)
            for (m in displayObj.javaClass.methods) {
                if (m.parameterTypes.isNotEmpty()) continue
                val nameLower = m.name.lowercase()
                if (hungerObj == null && nameLower.contains("hunger")) {
                    hungerObj = m.invoke(displayObj)
                } else if (cleanObj == null && nameLower.contains("clean")) {
                    cleanObj = m.invoke(displayObj)
                } else if (feelObj == null && (nameLower.contains("feel") || nameLower.contains("mood"))) {
                    feelObj = m.invoke(displayObj)
                }
            }

            // 3.2 语义声明字段探测
            if (hungerObj == null || cleanObj == null || feelObj == null) {
                for (f in displayObj.javaClass.declaredFields) {
                    f.isAccessible = true
                    val nameLower = f.name.lowercase()
                    if (hungerObj == null && nameLower.contains("hunger")) {
                        hungerObj = f.get(displayObj)
                    } else if (cleanObj == null && nameLower.contains("clean")) {
                        cleanObj = f.get(displayObj)
                    } else if (feelObj == null && (nameLower.contains("feel") || nameLower.contains("mood"))) {
                        feelObj = f.get(displayObj)
                    }
                }
            }

            // 3.3 混淆单字母方法兼容回退 (f=hunger, c=clean, d=feel)
            if (hungerObj == null || cleanObj == null || feelObj == null) {
                for (m in displayObj.javaClass.methods) {
                    if (m.parameterTypes.isEmpty() && m.returnType.name.endsWith($$"$c")) {
                        when (m.name) {
                            "f" -> if (hungerObj == null) hungerObj = m.invoke(displayObj)
                            "c" -> if (cleanObj == null) cleanObj = m.invoke(displayObj)
                            "d" -> if (feelObj == null) feelObj = m.invoke(displayObj)
                        }
                    }
                }
            }

            if (hungerObj != null) {
                val (cur, max) = extractValueAndMax(hungerObj)
                if (cur >= 0f) {
                    energy = cur
                    maxEnergy = max
                }
            }
            if (cleanObj != null) {
                val (cur, max) = extractValueAndMax(cleanObj)
                if (cur >= 0f) {
                    clean = cur
                    maxClean = max
                }
            }
            if (feelObj != null) {
                val (cur, _) = extractValueAndMax(feelObj)
                if (cur >= 0f) {
                    mood = cur
                }
            }

            if (energy >= 0f || clean >= 0f) {
                val attrs = PetAttributes(energy, maxEnergy, clean, maxClean, mood)
                onAttributesUpdated(attrs)
                return attrs
            } else {
                EngineLog.w(TAG, "从 DisplayValues 提取属性数值未命中有效值 (Safe-Fail)")
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            EngineLog.w(TAG, "反射读取宠物属性异常: ${t.message}")
        }
        return null
    }

    private fun extractValueAndMax(obj: Any): Pair<Float, Float> {
        if (obj is Number) {
            return Pair(obj.toFloat(), 100f)
        }
        var cur = -1f
        var max = 100f

        for (m in obj.javaClass.methods) {
            if (m.parameterTypes.isNotEmpty()) continue
            val nameLower = m.name.lowercase()
            if (nameLower == "b" || nameLower == "getcur" || nameLower == "getcurrent" || nameLower == "getvalue") {
                try {
                    val v = m.invoke(obj)
                    if (v is Number) {
                        cur = v.toFloat()
                        break
                    }
                } catch (_: Throwable) {
                }
            }
        }

        for (m in obj.javaClass.methods) {
            if (m.parameterTypes.isNotEmpty()) continue
            val nameLower = m.name.lowercase()
            if (nameLower == "d" || nameLower == "getmax" || nameLower == "getmaxvalue") {
                try {
                    val v = m.invoke(obj)
                    if (v is Number) {
                        max = v.toFloat()
                        break
                    }
                } catch (_: Throwable) {
                }
            }
        }

        if (cur < 0f) {
            for (fieldName in listOf("b", "cur", "current", "value", "a")) {
                try {
                    val f = obj.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
                    val v = f.get(obj)
                    if (v is Number) {
                        cur = v.toFloat()
                        break
                    }
                } catch (_: Throwable) {
                }
            }
        }

        for (fieldName in listOf("d", "max", "maxValue")) {
            try {
                val f = obj.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
                val v = f.get(obj)
                if (v is Number) {
                    max = v.toFloat()
                    break
                }
            } catch (_: Throwable) {
            }
        }

        return Pair(cur, if (max > 0f) max else 100f)
    }
}
