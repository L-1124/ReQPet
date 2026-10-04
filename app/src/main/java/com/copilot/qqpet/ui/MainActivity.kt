package com.copilot.qqpet.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.R
import com.copilot.qqpet.databinding.ActivityMainBinding
import com.copilot.qqpet.engine.PetAdventureEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var logReceiver: BroadcastReceiver? = null
    private val logDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logBuffer = ArrayDeque<String>(30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initStatusCard()
        initOfficialGroupCard()
        initActionButtons()
        registerLogReceiver()
    }

    override fun onResume() {
        super.onResume()
        initStatusCard()
        pingQQHost()
        try {
            val intent = Intent(HookEntry.ACTION_TRIGGER_ACTION).apply {
                setPackage(HookEntry.TARGET_PACKAGE)
                putExtra(PetAdventureEngine.EXTRA_ACTION, "query_account_status")
            }
            sendBroadcast(intent)
        } catch (_: Throwable) {}
    }

    private fun pingQQHost() {
        try {
            val intent = Intent(HookEntry.ACTION_PING).apply {
                setPackage(HookEntry.TARGET_PACKAGE)
            }
            sendBroadcast(intent)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        logReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Throwable) {}
        }
    }

    /**
     * 经典 Xposed 激活自检钩子 (API 82/93):
     * 若模块在支持的作用域内被 Hook，则返回 true；否则依赖双向 Ping-Pong 握手。
     */
    fun isModuleActive(): Boolean = false

    private fun updateStatusCard(active: Boolean) {
        runOnUiThread {
            if (active) {
                binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_green_bg))
                binding.tvStatusTitle.text = getString(R.string.status_active)
                binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            } else {
                binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                binding.tvStatusTitle.text = getString(R.string.status_inactive)
                binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            }
        }
    }

    private fun initStatusCard() {
        val prefs = PreferencesHelper.getPrefs(this)
        val lastActive = prefs.getLong("key_module_last_active_time", 0L)
        val active = isModuleActive() || prefs.getBoolean("key_module_active_verified", false) ||
                (lastActive > 0 && System.currentTimeMillis() - lastActive < 7 * 86400000L)
        updateStatusCard(active)

        binding.cardStatus.applyApplePressEffect()
        binding.cardStatus.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val currentActive = isModuleActive() || prefs.getBoolean("key_module_active_verified", false) ||
                    (lastActive > 0 && System.currentTimeMillis() - lastActive < 7 * 86400000L)
            if (!currentActive) {
                pingQQHost()
                Toast.makeText(this, "正在向 QQ 发送激活探测，请确保 QQ 在后台运行~", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "模块服务正常，与 QQ 内核通信良好~", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initOfficialGroupCard() {
        binding.cardGroup.applyApplePressEffect()
        binding.btnJoinGroup.applyApplePressEffect()

        val joinListener = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            joinOfficialQQGroup()
        }
        binding.cardGroup.setOnClickListener(joinListener)
        binding.btnJoinGroup.setOnClickListener(joinListener)
    }

    private fun joinOfficialQQGroup(groupKey: String = "FY6w7PMH2c", groupNum: String = "1087942084") {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("QQGroup", groupNum)
            clipboard?.setPrimaryClip(clip)
        } catch (_: Throwable) {}

        val nativeUri = "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$groupNum&card_type=group&source=qrcode"
        // 优先直达主 QQ 进程，避免分身系统弹窗拦截
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(nativeUri)).apply {
                setPackage(HookEntry.TARGET_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "正在直接拉起官方群名片 (群号已复制)", Toast.LENGTH_SHORT).show()
            return
        } catch (_: Throwable) {}

        // 通用系统拉起保底
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(nativeUri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "正在拉起官方群名片 (群号已复制)", Toast.LENGTH_SHORT).show()
            return
        } catch (_: Throwable) {}

        try {
            val directIntent = Intent().apply {
                data = Uri.parse("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$groupKey")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(directIntent)
            return
        } catch (_: Throwable) {}

        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://qm.qq.com/q/$groupKey")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(webIntent)
        } catch (t: Throwable) {
            Toast.makeText(this, "已复制群号 $groupNum，可在 QQ 中搜索添加", Toast.LENGTH_LONG).show()
        }
    }

    private fun initActionButtons() {
        binding.btnTestCycle.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("cycle", "全流程策略调度循环")
        }
        binding.btnTestCare.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("care", "照顾实测 (喂食+洗澡)")
        }
        binding.btnTestWork.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("work", "兼职打工实测")
        }
        binding.btnTestStudy.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("school", "学园学习实测")
        }
        binding.btnTestAdventure.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("adventure", "森林冒险实测")
        }
        binding.btnTestSettle.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("settle", "收益结算实测")
        }
        binding.btnTestFriendCare.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("friend_care", "好友喂食清洁实测")
        }
        binding.btnTestPk.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("pk_auto", "自动 PK 挑战 (10场实测)")
        }
        binding.btnTestClaimCoinBag.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("coinbag", "拆领金币福袋实测")
        }
        binding.btnTestActiveVisit.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("active_visit", "全量串门与陌生人回踩实测")
        }
        binding.btnClearLogs.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            synchronized(logBuffer) {
                logBuffer.clear()
            }
            binding.tvEngineLogs.text = "日志已清空，等待下次测试..."
        }

        // 注入 iOS 物理触觉微动效
        binding.btnTestCycle.applyApplePressEffect()
        binding.btnTestCare.applyApplePressEffect()
        binding.btnTestWork.applyApplePressEffect()
        binding.btnTestStudy.applyApplePressEffect()
        binding.btnTestAdventure.applyApplePressEffect()
        binding.btnTestSettle.applyApplePressEffect()
        binding.btnTestFriendCare.applyApplePressEffect()
        binding.btnTestPk.applyApplePressEffect()
        binding.btnTestClaimCoinBag.applyApplePressEffect()
        binding.btnTestActiveVisit.applyApplePressEffect()
        binding.btnClearLogs.applyApplePressEffect()
    }

    private fun sendActionToQQ(action: String, actionName: String) {
        appendLog("[指令] 已向 QQ 下发「$actionName」调度广播...")
        try {
            val intent = Intent(HookEntry.ACTION_TRIGGER_ACTION).apply {
                setPackage(HookEntry.TARGET_PACKAGE)
                putExtra(PetAdventureEngine.EXTRA_ACTION, action)
            }
            sendBroadcast(intent)
            Toast.makeText(this, "已下发 $actionName 指令", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            appendLog("[异常] 下发指令失败: ${t.message}")
        }
    }

    private fun registerLogReceiver() {
        val filter = IntentFilter().apply {
            addAction(PetAdventureEngine.ACTION_ENGINE_LOG)
            addAction(PetAdventureEngine.ACTION_SYNC_WORK_PLACES)
            addAction(PetAdventureEngine.ACTION_SYNC_ACCOUNT_STATUS)
            addAction(HookEntry.ACTION_PONG)
        }
        logReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updateStatusCard(true)
                PreferencesHelper.getPrefs(this@MainActivity).edit()
                    .putBoolean("key_module_active_verified", true)
                    .putLong("key_module_last_active_time", System.currentTimeMillis())
                    .apply()
                if (intent.action == PetAdventureEngine.ACTION_ENGINE_LOG) {
                    val msg = intent.getStringExtra(PetAdventureEngine.EXTRA_LOG_TEXT) ?: return
                    appendLog(msg)
                } else if (intent.action == HookEntry.ACTION_PONG) {
                    val reason = intent.getStringExtra("extra_reason") ?: "心跳回传"
                    appendLog("🟢 [在线确认] 收到 QQ 内核 Pong 握手 ($reason)")
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    private fun appendLog(line: String) {
        val time = logDateFormat.format(Date())
        val logEntry = "[$time] $line"
        runOnUiThread {
            synchronized(logBuffer) {
                if (logBuffer.size >= 30) {
                    logBuffer.removeFirst()
                }
                logBuffer.addLast(logEntry)
                binding.tvEngineLogs.text = logBuffer.joinToString("\n")
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.applyApplePressEffect() {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .alpha(0.85f)
                        .setDuration(120)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(1.0f)
                        .setDuration(180)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
            false
        }
    }
}
