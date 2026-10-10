package io.github.reqpet.protocol.client

import io.github.reqpet.engine.EngineLog
import io.github.reqpet.engine.PetAdventureEngine
import io.github.reqpet.protocol.ProtoWire
import io.github.reqpet.protocol.PurchaseGuard
import io.github.reqpet.protocol.channel.OidbChannel
import io.github.reqpet.protocol.channel.ProtocolBreakers
import io.github.reqpet.protocol.model.BathItemConfig
import io.github.reqpet.protocol.model.BathResult
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 宠物洗澡、香皂道具商城与清洁度协议客户端
 */
class PetBathProtocolClient(
    private val channel: OidbChannel,
    private val purchaseGuard: PurchaseGuard? = channel.context?.let { runCatching { PurchaseGuard.forContext(it) }.getOrNull() },
    private val onCleanUpdated: (newClean: Int) -> Unit = {}
) {
    internal data class BathPbSchema(
        val requestClass: Class<*>,
        val eventClass: Class<*>,
        val extInfoClass: Class<*>,
        val itemClass: Class<*>
    )

    companion object {
        private const val TAG = "PetBathProtocolClient"

        private fun isInstancePublicWritable(field: Field, expectedType: Class<*>): Boolean {
            val mod = field.modifiers
            return Modifier.isPublic(mod) &&
                    !Modifier.isStatic(mod) &&
                    !Modifier.isFinal(mod) &&
                    field.type == expectedType
        }

        internal fun validateEventClass(cls: Class<*>, nanoCls: Class<*>): Boolean {
            if (!nanoCls.isAssignableFrom(cls)) return false
            return try {
                cls.getDeclaredConstructor()
                val fA = cls.getField("a")
                val fB = cls.getField("b")
                val fC = cls.getField("c")
                isInstancePublicWritable(fA, Int::class.javaPrimitiveType!!) &&
                        isInstancePublicWritable(fB, Int::class.javaPrimitiveType!!) &&
                        isInstancePublicWritable(fC, Int::class.javaPrimitiveType!!)
            } catch (_: Throwable) {
                false
            }
        }

        internal fun validateExtInfoClass(cls: Class<*>, nanoCls: Class<*>): Boolean {
            if (!nanoCls.isAssignableFrom(cls)) return false
            return try {
                cls.getDeclaredConstructor()
                val fB = cls.getField("b")
                val fH = cls.getField("h")
                isInstancePublicWritable(fB, Long::class.javaPrimitiveType!!) &&
                        isInstancePublicWritable(fH, Int::class.javaPrimitiveType!!)
            } catch (_: Throwable) {
                false
            }
        }

        internal fun validateItemClass(cls: Class<*>, nanoCls: Class<*>): Boolean {
            if (!nanoCls.isAssignableFrom(cls)) return false
            return try {
                cls.getDeclaredConstructor()
                val fD = cls.getField("d")
                isInstancePublicWritable(fD, Int::class.javaPrimitiveType!!)
            } catch (_: Throwable) {
                false
            }
        }

        internal fun validateRequestClass(
            cls: Class<*>,
            eventCls: Class<*>,
            extInfoCls: Class<*>,
            itemCls: Class<*>,
            nanoCls: Class<*>
        ): Boolean {
            if (!nanoCls.isAssignableFrom(cls)) return false
            return try {
                cls.getDeclaredConstructor()
                val fA = cls.getField("a")
                val fB = cls.getField("b")
                val fC = cls.getField("c")
                val fD = cls.getField("d")
                val fE = cls.getField("e")

                isInstancePublicWritable(fA, String::class.java) &&
                        isInstancePublicWritable(fB, String::class.java) &&
                        isInstancePublicWritable(fC, eventCls) &&
                        isInstancePublicWritable(fD, extInfoCls) &&
                        isInstancePublicWritable(fE, itemCls)
            } catch (_: Throwable) {
                false
            }
        }

        internal fun deriveBathSchema(classLoader: ClassLoader): BathPbSchema? {
            val nanoCls = try {
                classLoader.loadClass("com.google.protobuf.nano.MessageNano")
            } catch (_: Throwable) {
                return null
            }

            val adapterCls = try {
                classLoader.loadClass("com.tencent.ergo.behavior.net.EGBehaviorNetworkAdapter")
            } catch (_: Throwable) {
                return null
            }

            val matchedSchemas = mutableListOf<BathPbSchema>()

            for (method in adapterCls.declaredMethods) {
                val params = method.parameterTypes
                if (params.size != 7) continue
                if (params[0] != String::class.java) continue
                if (params[1] != String::class.java) continue

                val eventCls = params[2]
                val extInfoCls = params[3]
                val itemCls = params[4]
                val behaviorCls = params[5]
                val contCls = params[6]

                if (!nanoCls.isAssignableFrom(eventCls)) continue
                if (!nanoCls.isAssignableFrom(extInfoCls)) continue
                if (!nanoCls.isAssignableFrom(itemCls)) continue
                if (!nanoCls.isAssignableFrom(behaviorCls)) continue
                if (!kotlin.coroutines.Continuation::class.java.isAssignableFrom(contCls) &&
                    contCls.name != "kotlin.coroutines.Continuation"
                ) {
                    continue
                }

                if (!validateEventClass(eventCls, nanoCls)) continue
                if (!validateExtInfoClass(extInfoCls, nanoCls)) continue
                if (!validateItemClass(itemCls, nanoCls)) continue

                val pkg = itemCls.name.substringBeforeLast('.')
                val reqCandidates = mutableListOf<Class<*>>()
                for (c in 'a'..'z') {
                    val candidateName = "$pkg.$c"
                    if (candidateName == itemCls.name) continue
                    val candidate = try {
                        classLoader.loadClass(candidateName)
                    } catch (_: Throwable) {
                        continue
                    }
                    if (validateRequestClass(candidate, eventCls, extInfoCls, itemCls, nanoCls)) {
                        reqCandidates.add(candidate)
                    }
                }

                if (reqCandidates.size == 1) {
                    val schema = BathPbSchema(reqCandidates.single(), eventCls, extInfoCls, itemCls)
                    if (matchedSchemas.none { it.requestClass == schema.requestClass && it.eventClass == schema.eventClass }) {
                        matchedSchemas.add(schema)
                    }
                } else if (reqCandidates.size > 1) {
                    EngineLog.w(
                        TAG,
                        "推导洗澡 requestCls 存在候选歧义 (${reqCandidates.map { it.name }})，执行 Fail-Closed"
                    )
                    return null
                }
            }

            if (matchedSchemas.size > 1) {
                EngineLog.w(TAG, "存在多个洗澡 Schema 歧义，执行 Fail-Closed")
                return null
            }

            return matchedSchemas.singleOrNull()
        }

        internal fun resolvePetStatus(classLoader: ClassLoader, targetUin: String): Int? {
            return try {
                val upmCls = classLoader.loadClass("com.tencent.ergo.user.UserPetManager")
                val instanceField = upmCls.getField("a")
                val managerInstance = instanceField.get(null) ?: return null
                val mMethod = upmCls.getMethod("m", String::class.java)
                val petObj = try {
                    mMethod.invoke(managerInstance, targetUin)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    val cause = e.targetException ?: e.cause
                    if (cause is kotlinx.coroutines.CancellationException) throw cause
                    return null
                }
                if (petObj == null) return 0

                val eField = petObj.javaClass.getField("e")
                val statusObj = eField.get(petObj) ?: return 0

                val bField = statusObj.javaClass.getField("b")
                if (!isInstancePublicWritable(bField, Int::class.javaPrimitiveType!!)) return null
                bField.getInt(statusObj)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
        }

        internal fun buildBathRequestBody(
            petId: String,
            petUin: String,
            cleanValue: Int,
            now: Long,
            status: Int,
            schema: BathPbSchema,
            nanoCls: Class<*>
        ): ByteArray {
            val eventInst = schema.eventClass.getDeclaredConstructor().newInstance()
            schema.eventClass.getField("a").set(eventInst, 5000)
            schema.eventClass.getField("b").set(eventInst, 500)
            schema.eventClass.getField("c").set(eventInst, 501)

            val extInfoInst = schema.extInfoClass.getDeclaredConstructor().newInstance()
            schema.extInfoClass.getField("b").set(extInfoInst, now)
            schema.extInfoClass.getField("h").set(extInfoInst, status)

            val itemInst = schema.itemClass.getDeclaredConstructor().newInstance()
            schema.itemClass.getField("d").set(itemInst, cleanValue)

            val reqInst = schema.requestClass.getDeclaredConstructor().newInstance()
            schema.requestClass.getField("a").set(reqInst, petId)
            schema.requestClass.getField("b").set(reqInst, petUin)
            schema.requestClass.getField("c").set(reqInst, eventInst)
            schema.requestClass.getField("d").set(reqInst, extInfoInst)
            schema.requestClass.getField("e").set(reqInst, itemInst)

            val toByteArrayMethod = nanoCls.getMethod("toByteArray", nanoCls)
            return toByteArrayMethod.invoke(null, reqInst) as ByteArray
        }
    }

    @Volatile
    private var cachedSchema: BathPbSchema? = null

    internal fun tryReflectBathBody(
        petId: String,
        petUin: String,
        cleanValue: Int,
        now: Long
    ): ByteArray? {
        return try {
            val nanoCls = channel.classLoader.loadClass("com.google.protobuf.nano.MessageNano")
            val schema =
                cachedSchema ?: deriveBathSchema(channel.classLoader)?.also { cachedSchema = it } ?: return null
            val targetUin = petUin.ifEmpty { channel.getCurrentRuntimeUin() }
            val status = resolvePetStatus(channel.classLoader, targetUin) ?: return null
            buildBathRequestBody(petId, petUin, cleanValue, now, status, schema, nanoCls)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EngineLog.w(TAG, "反射构造洗澡 PB 异常: ${e.message}")
            null
        }
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

    fun fetchBathItemConfig(callback: (code: Int, items: List<BathItemConfig>) -> Unit) {
        channel.sendOidb("OidbSvcTrpcTcp.0x9bf1_1", 39921, 1, ByteArray(0)) { code, data, err ->
            val list = mutableListOf<BathItemConfig>()
            if (code == 0 && data != null) {
                val itemBytesList = ProtoWire.allBytes(data, 1)
                for (bBytes in itemBytesList) {
                    val name = ProtoWire.firstString(bBytes, 1) ?: "香皂片"
                    val itemId = ProtoWire.firstString(bBytes, 2) ?: ""
                    val gold = (ProtoWire.firstVarint(bBytes, 5) ?: 5L).toInt()
                    val cleanVal = (ProtoWire.firstVarint(bBytes, 6) ?: 0L).toInt()
                    val defBuy = (ProtoWire.firstVarint(bBytes, 8) ?: 5L).toInt()
                    if (itemId.isNotEmpty()) {
                        list.add(BathItemConfig(itemId, name, gold, cleanVal, defBuy))
                    }
                }
                EngineLog.i("PetBathClient", "fetchBathItemConfig 成功: count=${list.size}")
            } else {
                EngineLog.w("PetBathClient", "fetchBathItemConfig 失败: code=$code, err=$err")
            }
            callback(if (code == 0 && (data == null || list.isEmpty())) -104 else code, list)
        }
    }

    fun fetchBathInventory(callback: (code: Int, balances: Map<String, Int>) -> Unit) {
        val uin = PetAdventureEngine.currentActiveUin
        channel.sendOidb("OidbSvcTrpcTcp.0x9bf2_1", 39922, 1, ByteArray(0)) { code, data, err ->
            val map = linkedMapOf<String, Int>()
            if (code == 0 && ProtoWire.firstBytes(data, 1) != null) {
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
                map.forEach { (item, balance) ->
                    purchaseGuard?.observeInventory(uin, "bath:$item", balance)
                }
            } else {
                EngineLog.w("PetBathClient", "fetchBathInventory 失败: code=$code, err=$err")
            }
            callback(if (code == 0 && ProtoWire.firstBytes(data, 1) == null) -104 else code, map)
        }
    }

    fun buyBathItem(
        petId: String,
        itemId: String,
        count: Int = 5,
        scene: Long = 21L,
        callback: (code: Int, orderResult: Int, errorMsg: String?) -> Unit
    ) {
        val itemIdLong = itemId.toLongOrNull()
        if (petId.isBlank() || itemIdLong == null || itemIdLong <= 0L || count <= 0) {
            callback(-1, 0, "无效的香皂购买参数")
            return
        }
        if (count > PurchaseGuard.MAX_PER_PURCHASE || purchaseGuard == null) {
            callback(PurchaseGuard.BLOCKED_CODE, 0, "采购保护不可用或单次数量超过 10，暂停补购")
            return
        }
        fetchBathInventory { inventoryCode, inventory ->
            if (inventoryCode != 0 || (inventory[itemId] ?: 0) != 0) {
                callback(PurchaseGuard.BLOCKED_CODE, 0, "库存未确认缺货，暂停补购")
                return@fetchBathInventory
            }
            val (reservation, reason) = purchaseGuard.reserveForPet(petId, "bath:$itemId", count, 0)
            if (reservation == null) {
                EngineLog.w(TAG, "采购拦截: $reason")
                callback(PurchaseGuard.BLOCKED_CODE, 0, reason)
                return@fetchBathInventory
            }
            sendBathPurchase(petId, itemIdLong, count, scene) { code, orderResult, error ->
                val outcome = when {
                    code == 0 && orderResult == 1 -> PurchaseGuard.Outcome.SUCCESS
                    code == 0 && orderResult == 2 -> PurchaseGuard.Outcome.REJECTED
                    code == OidbChannel.MASTER_OFF_CODE || code == ProtocolBreakers.FAST_FAIL_CODE -> PurchaseGuard.Outcome.REJECTED
                    else -> PurchaseGuard.Outcome.UNKNOWN
                }
                purchaseGuard.complete(reservation, outcome)
                callback(code, orderResult, error)
            }
        }
    }

    private fun sendBathPurchase(
        petId: String, itemIdLong: Long, count: Int, scene: Long,
        callback: (Int, Int, String?) -> Unit
    ) {
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
        if (petId.isBlank() || itemId.isBlank() || useNum <= 0) {
            callback(BathResult(-1, -1, 0, -1, false, "无效的洗澡参数"))
            return
        }
        val body = ProtoWire.message()
            .writeString(1, petId)
            .writeString(2, itemId)
            .writeVarint(3, useNum.toLong())
            .writeString(4, petUin)
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x9bf3_1", 39923, 1, body) { code, data, err ->
            val result = parseBathResult(code, data, err)
            if (result.code == 0 && petUin.isEmpty() && result.newClean > 0) {
                onCleanUpdated(result.newClean)
            }
            EngineLog.i(TAG, "doBathOnce: code=${result.code}, clean=${result.newClean}, balance=${result.remainBalance}, full=${result.isFullClean}")
            callback(result)
        }
    }

    internal fun parseBathResult(code: Int, data: ByteArray?, errorMsg: String?): BathResult {
        if (code != 0) return BathResult(code, -1, 0, -1, false, errorMsg)
        if (data == null || data.isEmpty()) return BathResult(-1, -1, 0, -1, false, "洗澡回包为空")
        return try {
            val clean = ProtoWire.firstVarint(data, 1) ?: 0L
            val balance = ProtoWire.firstVarint(data, 3) ?: 0L
            val full = (ProtoWire.firstVarint(data, 4) ?: 0L) != 0L
            require(clean in 0..Int.MAX_VALUE.toLong() && balance in 0..Int.MAX_VALUE.toLong())
            require(clean > 0L || full) { "缺少有效清洁度" }
            // 增量由任务层按前后清洁度计算，不猜测未确认字段。
            BathResult(0, clean.toInt(), 0, balance.toInt(), full, null)
        } catch (e: IllegalArgumentException) {
            BathResult(-1, -1, 0, -1, false, "洗澡回包格式错误: ${e.message}")
        }
    }
}
