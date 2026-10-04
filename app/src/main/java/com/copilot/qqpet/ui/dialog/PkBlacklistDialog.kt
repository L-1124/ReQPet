package com.copilot.qqpet.ui.dialog

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.ui.theme.ThemeColors
import com.copilot.qqpet.ui.util.SettingConfigSyncer
import com.copilot.qqpet.ui.util.UiAnimUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object PkBlacklistDialog {

    private val mainHandler = Handler(Looper.getMainLooper())

    data class TargetItem(val uin: Long, var nick: String, var petNick: String, val role: String, var totalAttr: Long = 0L)

    fun formatPkBlacklistSummary(context: Context): String {
        val blacklistUins = PetAdventureEngine.loadSavedPkBlacklistUins(context)
        if (blacklistUins.isEmpty()) {
            return "未设置免战名单 (全部碾压对手均可对决 · 点击管理黑名单)"
        }
        val cachedFriends = PetAdventureEngine.loadCachedHireableFriends(context)
        val matchedNames = blacklistUins.map { uin ->
            val f = cachedFriends.find { it.uin == uin }
            if (f != null && f.friendNick.isNotBlank()) f.friendNick else uin.toString()
        }
        val preview = matchedNames.take(3).joinToString("、")
        val more = if (matchedNames.size > 3) " 等" else ""
        return "已拉黑 ${blacklistUins.size} 位对手 ($preview$more) · 自动跳过免战"
    }

    @SuppressLint("SetTextI18n")
    fun showPkBlacklistDialog(
        context: Context,
        colors: ThemeColors,
        prefs: SharedPreferences,
        engine: PetAdventureEngine?,
        onUpdated: () -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val blacklistUins = LinkedHashSet<Long>(PetAdventureEngine.loadSavedPkBlacklistUins(context))
        val candidates = mutableListOf<TargetItem>()
        val seen = mutableSetOf<Long>()
        initCandidates(context, blacklistUins, candidates, seen)

        var searchQuery = ""

        val rootCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(if (colors.isNight) Color.parseColor("#1C1C1E") else Color.WHITE)
                cornerRadius = UiAnimUtils.dp(context, 16).toFloat()
                if (colors.isNight) setStroke(1, colors.cardBorder)
            }
            setPadding(UiAnimUtils.dp(context, 16), UiAnimUtils.dp(context, 18), UiAnimUtils.dp(context, 16), UiAnimUtils.dp(context, 14))
        }

        buildHeader(context, colors, rootCard)
        val (searchInput, clearSearchBtn) = buildSearchBar(context, colors, rootCard)

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiAnimUtils.dp(context, 2), 0, UiAnimUtils.dp(context, 2), UiAnimUtils.dp(context, 8))
        }
        val statusInfoTv = TextView(context).apply {
            text = "已拉黑 ${blacklistUins.size} 人 · 候选池共 ${candidates.size} 人"
            textSize = 12f
            setTextColor(colors.secondaryText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        val clearSelectedBtn = TextView(context).apply {
            text = "全不选"
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.actionRedText)
            setPadding(UiAnimUtils.dp(context, 6), UiAnimUtils.dp(context, 4), UiAnimUtils.dp(context, 6), UiAnimUtils.dp(context, 4))
        }
        val addManualBtn = TextView(context).apply {
            text = "+ 输入QQ拉黑"
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.actionBlueText)
            setPadding(UiAnimUtils.dp(context, 6), UiAnimUtils.dp(context, 4), UiAnimUtils.dp(context, 2), UiAnimUtils.dp(context, 4))
        }
        statusRow.addView(statusInfoTv)
        statusRow.addView(clearSelectedBtn)
        statusRow.addView(addManualBtn)
        rootCard.addView(statusRow)

        rootCard.addView(View(context).apply {
            setBackgroundColor(colors.dividerColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val screenHeight = context.resources.displayMetrics.heightPixels
        val listScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (screenHeight * 0.44f).toInt())
            addView(listContainer)
        }
        rootCard.addView(listScrollView)

        fun persistSelection() {
            PetAdventureEngine.savePkBlacklistUins(context, blacklistUins)
            SettingConfigSyncer.syncConfig(prefs, engine, context)
            onUpdated()
        }

        fun renderList() {
            listContainer.removeAllViews()
            val q = searchQuery.trim()
            val filtered = if (q.isEmpty()) candidates.toList() else candidates.filter { item ->
                item.nick.contains(q, ignoreCase = true) || item.petNick.contains(q, ignoreCase = true) || item.uin.toString().contains(q)
            }
            statusInfoTv.text = if (q.isNotEmpty()) "搜索到 ${filtered.size} 人 · 已拉黑 ${blacklistUins.size} 人" else "已拉黑 ${blacklistUins.size} 人 · 候选池共 ${candidates.size} 人"

            if (filtered.isEmpty()) {
                val tip = if (q.isNotEmpty()) "未找到匹配「$q」的对象，可点击右上角「+ 输入QQ拉黑」直接添加" else "暂无候选好友，可点击右上角「+ 输入QQ拉黑」添加免战对象"
                listContainer.addView(TextView(context).apply {
                    text = tip; textSize = 13f; setTextColor(colors.secondaryText); gravity = Gravity.CENTER
                    setPadding(UiAnimUtils.dp(context, 16), UiAnimUtils.dp(context, 36), UiAnimUtils.dp(context, 16), UiAnimUtils.dp(context, 36))
                })
                return
            }

            filtered.forEachIndexed { idx, item ->
                renderTargetRow(context, colors, listContainer, item, blacklistUins) { persistSelection(); renderList() }
                if (idx < filtered.size - 1) {
                    listContainer.addView(View(context).apply {
                        setBackgroundColor(colors.dividerColor)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    })
                }
            }
        }

        bindEvents(searchInput, clearSearchBtn, addManualBtn, clearSelectedBtn, context, colors, candidates, blacklistUins, { persistSelection(); renderList() }) { q ->
            searchQuery = q
            renderList()
        }

        asyncLoadVisitors(engine, seen, candidates) { renderList() }

        buildFooter(context, colors, rootCard) { persistSelection(); dialog.dismiss() }
        renderList()

        setupDialogWindow(dialog, rootCard, context)
        dialog.show()
    }

    private fun initCandidates(context: Context, blacklistUins: Set<Long>, candidates: MutableList<TargetItem>, seen: MutableSet<Long>) {
        val cachedFriends = PetAdventureEngine.loadCachedHireableFriends(context)
        for (f in cachedFriends) {
            if (f.uin <= 0L || seen.contains(f.uin)) continue
            seen.add(f.uin)
            candidates.add(TargetItem(f.uin, f.friendNick.ifEmpty { "好友_${f.uin}" }, f.petNick.ifEmpty { "小宠" }, "好友", f.totalAttr))
        }
        for (u in blacklistUins) {
            if (u > 0L && !seen.contains(u)) {
                seen.add(u)
                candidates.add(TargetItem(u, "自定义免战目标", "-", "已拉黑", 0L))
            }
        }
    }

    private fun buildHeader(context: Context, colors: ThemeColors, root: LinearLayout) {
        root.addView(TextView(context).apply { text = "选择 PK 免战黑名单"; textSize = 17f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.primaryText) })
        root.addView(TextView(context).apply { text = "已勾选目标将绝对免战跳过，零发包防误打；未勾选且三维低于我方的对手正常挑战。"; textSize = 12.5f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 4), 0, UiAnimUtils.dp(context, 12)) })
    }

    private fun buildSearchBar(context: Context, colors: ThemeColors, root: LinearLayout): Pair<EditText, TextView> {
        val searchBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(if (colors.isNight) Color.parseColor("#2C2C2E") else Color.parseColor("#F2F2F7"))
                cornerRadius = UiAnimUtils.dp(context, 10).toFloat()
            }
            setPadding(UiAnimUtils.dp(context, 12), UiAnimUtils.dp(context, 6), UiAnimUtils.dp(context, 10), UiAnimUtils.dp(context, 6))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UiAnimUtils.dp(context, 10))
            }
        }
        val clearSearchBtn = TextView(context).apply {
            text = "清空"; textSize = 12.5f; setTextColor(colors.actionBlueText); setPadding(UiAnimUtils.dp(context, 8), UiAnimUtils.dp(context, 4), UiAnimUtils.dp(context, 4), UiAnimUtils.dp(context, 4)); visibility = View.GONE
        }
        val searchInput = EditText(context).apply {
            hint = "搜索好友昵称、QQ 号或小宠名..."; textSize = 13.5f; setTextColor(colors.primaryText); setHintTextColor(colors.secondaryText); background = null; isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT
            setPadding(0, UiAnimUtils.dp(context, 4), 0, UiAnimUtils.dp(context, 4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        clearSearchBtn.setOnClickListener { searchInput.setText("") }
        searchBar.addView(searchInput); searchBar.addView(clearSearchBtn); root.addView(searchBar)
        return Pair(searchInput, clearSearchBtn)
    }

    private fun renderTargetRow(
        context: Context, colors: ThemeColors, container: LinearLayout,
        item: TargetItem, blacklistUins: MutableSet<Long>, onToggle: () -> Unit
    ) {
        val isBlocked = blacklistUins.contains(item.uin)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(UiAnimUtils.dp(context, 4), UiAnimUtils.dp(context, 11), UiAnimUtils.dp(context, 4), UiAnimUtils.dp(context, 11))
        }
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, UiAnimUtils.dp(context, 10), 0)
            }
        }
        textCol.addView(TextView(context).apply {
            text = "${item.nick} (${item.uin})"; textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", if (isBlocked) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (isBlocked) (if (colors.isNight) Color.parseColor("#FF453A") else Color.parseColor("#D70015")) else colors.primaryText)
        })
        val rolePart = "[${item.role}]"
        val petPart = if (item.petNick.isNotBlank() && item.petNick != "-") " · 小宠: ${item.petNick}" else ""
        val attrPart = if (item.totalAttr > 0L) " · 战力 ${item.totalAttr}" else ""
        textCol.addView(TextView(context).apply {
            text = "$rolePart$petPart$attrPart"; textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 2), 0, 0)
        })
        val checkBadge = TextView(context).apply {
            text = if (isBlocked) "🚫 免战" else "正常"; textSize = 12f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (isBlocked) Color.WHITE else colors.secondaryText)
            setPadding(UiAnimUtils.dp(context, 10), UiAnimUtils.dp(context, 5), UiAnimUtils.dp(context, 10), UiAnimUtils.dp(context, 5))
            background = GradientDrawable().apply {
                setColor(if (isBlocked) (if (colors.isNight) Color.parseColor("#FF453A") else Color.parseColor("#FF3B30")) else (if (colors.isNight) Color.parseColor("#2C2C2E") else Color.parseColor("#EBEBED")))
                cornerRadius = UiAnimUtils.dp(context, 8).toFloat()
            }
        }
        row.addView(textCol); row.addView(checkBadge)
        row.setOnClickListener {
            if (blacklistUins.contains(item.uin)) blacklistUins.remove(item.uin) else blacklistUins.add(item.uin)
            onToggle()
        }
        container.addView(row)
    }

    private fun bindEvents(
        input: EditText, clearBtn: TextView, addBtn: TextView, clearSelBtn: TextView,
        context: Context, colors: ThemeColors, candidates: MutableList<TargetItem>,
        blacklistUins: LinkedHashSet<Long>, onUpdated: () -> Unit, onQueryChanged: (String) -> Unit
    ) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty()
                clearBtn.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                onQueryChanged(q)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        clearSelBtn.setOnClickListener { if (blacklistUins.isNotEmpty()) { blacklistUins.clear(); onUpdated() } }
        addBtn.setOnClickListener { showManualAddDialog(context, colors, candidates, blacklistUins, onUpdated) }
    }

    private fun showManualAddDialog(
        context: Context, colors: ThemeColors, candidates: MutableList<TargetItem>,
        blacklistUins: LinkedHashSet<Long>, onUpdated: () -> Unit
    ) {
        val inputDialog = Dialog(context)
        inputDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val inputCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(if (colors.isNight) Color.parseColor("#1C1C1E") else Color.WHITE)
                cornerRadius = UiAnimUtils.dp(context, 14).toFloat()
            }
            setPadding(UiAnimUtils.dp(context, 18), UiAnimUtils.dp(context, 16), UiAnimUtils.dp(context, 18), UiAnimUtils.dp(context, 16))
        }
        inputCard.addView(TextView(context).apply {
            text = "手动添加免战 QQ 号"; textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.primaryText)
        })
        val qqEdit = EditText(context).apply {
            hint = "输入对方 QQ 号..."; inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(colors.primaryText); setHintTextColor(colors.secondaryText); textSize = 14f
            setPadding(UiAnimUtils.dp(context, 10), UiAnimUtils.dp(context, 8), UiAnimUtils.dp(context, 10), UiAnimUtils.dp(context, 8))
            background = GradientDrawable().apply {
                setColor(if (colors.isNight) Color.parseColor("#2C2C2E") else Color.parseColor("#F2F2F7"))
                cornerRadius = UiAnimUtils.dp(context, 8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, UiAnimUtils.dp(context, 12), 0, UiAnimUtils.dp(context, 14))
            }
        }
        inputCard.addView(qqEdit)
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        btnRow.addView(TextView(context).apply {
            text = "取消"; textSize = 14f; setTextColor(colors.secondaryText)
            setPadding(UiAnimUtils.dp(context, 12), UiAnimUtils.dp(context, 6), UiAnimUtils.dp(context, 12), UiAnimUtils.dp(context, 6))
            setOnClickListener { inputDialog.dismiss() }
        })
        btnRow.addView(TextView(context).apply {
            text = "确认免战"; textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.actionBlueText)
            setPadding(UiAnimUtils.dp(context, 12), UiAnimUtils.dp(context, 6), UiAnimUtils.dp(context, 4), UiAnimUtils.dp(context, 6))
            setOnClickListener {
                val rawUin = qqEdit.text.toString().trim().toLongOrNull()
                if (rawUin != null && rawUin > 0L) {
                    blacklistUins.add(rawUin)
                    if (candidates.none { it.uin == rawUin }) {
                        candidates.add(0, TargetItem(rawUin, "手动免战号", "-", "手动免战"))
                    }
                    onUpdated()
                    inputDialog.dismiss()
                } else {
                    Toast.makeText(context, "请输入有效的纯数字 QQ 号", Toast.LENGTH_SHORT).show()
                }
            }
        })
        inputCard.addView(btnRow)
        inputDialog.setContentView(inputCard)
        inputDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        inputDialog.show()
    }

    private fun asyncLoadVisitors(engine: PetAdventureEngine?, seen: MutableSet<Long>, candidates: MutableList<TargetItem>, onUpdated: () -> Unit) {
        val active = engine ?: HookEntry.globalEngine
        if (active != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val (code, visitors) = active.fetchLikeListAwait("")
                    if (code == 0 && visitors.isNotEmpty()) {
                        var added = false
                        for (v in visitors) {
                            if (v.uin > 0L && !seen.contains(v.uin)) {
                                seen.add(v.uin)
                                candidates.add(TargetItem(v.uin, v.nick.ifEmpty { "访客_${v.uin}" }, "小宠", "访客"))
                                added = true
                            }
                        }
                        if (added) mainHandler.post { onUpdated() }
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun buildFooter(context: Context, colors: ThemeColors, root: LinearLayout, onDone: () -> Unit) {
        root.addView(View(context).apply {
            setBackgroundColor(colors.dividerColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, UiAnimUtils.dp(context, 6), 0, UiAnimUtils.dp(context, 10))
            }
        })
        root.addView(TextView(context).apply {
            text = "完成并保存"; textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, UiAnimUtils.dp(context, 11), 0, UiAnimUtils.dp(context, 11))
            background = GradientDrawable().apply { setColor(colors.actionBlueText); cornerRadius = UiAnimUtils.dp(context, 10).toFloat() }
            UiAnimUtils.applyTouchSpringEffect(this)
            setOnClickListener { onDone() }
        })
    }

    private fun setupDialogWindow(dialog: Dialog, root: View, context: Context) {
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((context.resources.displayMetrics.widthPixels * 0.90f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.45f)
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
}
