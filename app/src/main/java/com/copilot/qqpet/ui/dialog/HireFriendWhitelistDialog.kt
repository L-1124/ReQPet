package com.copilot.qqpet.ui.dialog

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
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
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.theme.ThemeColors
import com.copilot.qqpet.ui.util.SettingConfigSyncer
import com.copilot.qqpet.ui.util.UiAnimUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object HireFriendWhitelistDialog {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun formatHireWhitelistSummary(context: Context): String {
        val selectedUins = PetAdventureEngine.loadSavedHireFriendUins(context)
        if (selectedUins.isEmpty()) {
            return "当前未勾选好友 (未选择的好友不会雇佣 · 点击搜索勾选)"
        }
        val cachedFriends = PetAdventureEngine.loadCachedHireableFriends(context)
        val matchedNames = selectedUins.mapNotNull { uin ->
            val f = cachedFriends.find { it.uin == uin }
            if (f != null && f.friendNick.isNotBlank()) f.friendNick else uin.toString()
        }
        val preview = matchedNames.take(3).joinToString("、")
        val more = if (matchedNames.size > 3) " 等" else ""
        return "已勾选 ${selectedUins.size} 位好友 ($preview$more) · 优先空闲最高收益"
    }

    @SuppressLint("SetTextI18n")
    fun showHireFriendWhitelistDialog(
        context: Context,
        colors: ThemeColors,
        engine: PetAdventureEngine?,
        onUpdated: () -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val selectedUins = LinkedHashSet<Long>(PetAdventureEngine.loadSavedHireFriendUins(context))
        val allFriends = mutableListOf<QQPetDirectBridge.HireableFriend>().apply {
            addAll(PetAdventureEngine.loadCachedHireableFriends(context))
        }
        var searchQuery = ""
        var isRefreshing = false

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
            text = "已勾选 ${selectedUins.size} 人 · 共 ${allFriends.size} 位养宠好友"
            textSize = 12f
            setTextColor(colors.secondaryText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        val clearSelectedBtn = TextView(context).apply {
            text = "全不选"
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.actionRedText)
            setPadding(UiAnimUtils.dp(context, 8), UiAnimUtils.dp(context, 4), UiAnimUtils.dp(context, 8), UiAnimUtils.dp(context, 4))
        }
        val refreshBtn = TextView(context).apply {
            text = "刷新好友与资质"
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.actionBlueText)
            setPadding(UiAnimUtils.dp(context, 8), UiAnimUtils.dp(context, 4), UiAnimUtils.dp(context, 2), UiAnimUtils.dp(context, 4))
        }
        statusRow.addView(statusInfoTv)
        statusRow.addView(clearSelectedBtn)
        statusRow.addView(refreshBtn)
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
            PetAdventureEngine.saveHireFriendUins(context, selectedUins)
            SettingConfigSyncer.syncConfig(engine, context)
            onUpdated()
        }

        fun renderList() {
            listContainer.removeAllViews()
            val q = searchQuery.trim()
            val filtered = if (q.isEmpty()) allFriends.toList() else allFriends.filter { f ->
                f.friendNick.contains(q, ignoreCase = true) || f.petNick.contains(q, ignoreCase = true) || f.uin.toString().contains(q)
            }
            statusInfoTv.text = if (isRefreshing) "正在同步好友列表与实测资质..." else if (q.isNotEmpty()) "搜索到 ${filtered.size} 人 · 已勾选 ${selectedUins.size} 人" else "已勾选 ${selectedUins.size} 人 · 共 ${allFriends.size} 位养宠好友"

            if (filtered.isEmpty()) {
                val tip = if (isRefreshing) "正在拉取养宠好友列表，请稍候..." else if (q.isNotEmpty()) "未找到匹配「$q」的养宠好友" else "暂无数据，请点击「刷新好友与资质」"
                listContainer.addView(TextView(context).apply {
                    text = tip; textSize = 13f; setTextColor(colors.secondaryText); gravity = Gravity.CENTER
                    setPadding(UiAnimUtils.dp(context, 16), UiAnimUtils.dp(context, 36), UiAnimUtils.dp(context, 16), UiAnimUtils.dp(context, 36))
                })
                return
            }

            filtered.forEachIndexed { idx, friend ->
                renderFriendRow(context, colors, listContainer, friend, selectedUins, engine) {
                    persistSelection()
                    renderList()
                }
                if (idx < filtered.size - 1) {
                    listContainer.addView(View(context).apply {
                        setBackgroundColor(colors.dividerColor)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    })
                }
            }
        }

        fun triggerRefreshFriends() {
            val active = engine ?: HookEntry.globalEngine
            if (active == null) {
                Toast.makeText(context, "引擎尚未就绪，请稍候再试", Toast.LENGTH_SHORT).show()
                return
            }
            if (isRefreshing) return
            isRefreshing = true
            renderList()
            CoroutineScope(Dispatchers.IO).launch {
                val fetched = active.fetchAllHireableFriendsAwait(context, enrichSelectedAndTop = true)
                mainHandler.post {
                    isRefreshing = false
                    allFriends.clear()
                    allFriends.addAll(fetched)
                    renderList()
                    onUpdated()
                }
            }
        }

        bindSearchEvents(searchInput, clearSearchBtn) { q -> searchQuery = q; renderList() }
        clearSelectedBtn.setOnClickListener { if (selectedUins.isNotEmpty()) { selectedUins.clear(); persistSelection(); renderList() } }
        refreshBtn.setOnClickListener { triggerRefreshFriends() }

        buildFooter(context, colors, rootCard) { persistSelection(); dialog.dismiss() }
        renderList()
        if (allFriends.isEmpty()) triggerRefreshFriends()

        setupDialogWindow(dialog, rootCard, context)
        dialog.show()
    }

    private fun buildHeader(context: Context, colors: ThemeColors, root: LinearLayout) {
        root.addView(TextView(context).apply {
            text = "选择雇佣好友白名单"; textSize = 17f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.primaryText)
        })
        root.addView(TextView(context).apply {
            text = "未勾选的好友不会雇佣；已勾选好友中默认优先雇佣空闲且总资质最高者。"; textSize = 12.5f; setTextColor(colors.secondaryText)
            setPadding(0, UiAnimUtils.dp(context, 4), 0, UiAnimUtils.dp(context, 12))
        })
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
            hint = "输入好友名字、宠物名或 QQ 号搜索..."; textSize = 13.5f; setTextColor(colors.primaryText); setHintTextColor(colors.secondaryText); background = null; isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT
            setPadding(0, UiAnimUtils.dp(context, 4), 0, UiAnimUtils.dp(context, 4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        clearSearchBtn.setOnClickListener { searchInput.setText("") }
        searchBar.addView(searchInput); searchBar.addView(clearSearchBtn); root.addView(searchBar)
        return Pair(searchInput, clearSearchBtn)
    }

    private fun bindSearchEvents(input: EditText, clearBtn: TextView, onQueryChanged: (String) -> Unit) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty()
                clearBtn.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                onQueryChanged(q)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun renderFriendRow(
        context: Context, colors: ThemeColors, container: LinearLayout,
        friend: QQPetDirectBridge.HireableFriend, selectedUins: LinkedHashSet<Long>,
        engine: PetAdventureEngine?, onToggle: () -> Unit
    ) {
        val isChecked = selectedUins.contains(friend.uin)
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
        val displayName = friend.friendNick.ifEmpty { "QQ好友" }
        textCol.addView(TextView(context).apply {
            text = "$displayName (${friend.uin})"; textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", if (isChecked) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(colors.primaryText)
        })
        val petPart = "小宠: ${friend.petNick.ifEmpty { "未知" }}"
        val attrPart = if (friend.totalAttr > 0L) " · 总资质 ${friend.totalAttr} (力${friend.power}/智${friend.intel}/魅${friend.charm})" else " · 勾选刷新探测资质"
        val idlePart = if (friend.totalAttr > 0L || !friend.isIdle) (if (friend.isIdle) " · 空闲" else " · 忙碌中") else ""
        textCol.addView(TextView(context).apply {
            text = "$petPart$attrPart$idlePart"; textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 2), 0, 0)
        })

        val checkBadge = TextView(context).apply {
            text = if (isChecked) "✓ 已选" else "未选"; textSize = 12f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (isChecked) Color.WHITE else colors.secondaryText)
            setPadding(UiAnimUtils.dp(context, 10), UiAnimUtils.dp(context, 5), UiAnimUtils.dp(context, 10), UiAnimUtils.dp(context, 5))
            background = GradientDrawable().apply {
                setColor(if (isChecked) (if (colors.isNight) Color.parseColor("#30D158") else Color.parseColor("#34C759")) else (if (colors.isNight) Color.parseColor("#2C2C2E") else Color.parseColor("#EBEBED")))
                cornerRadius = UiAnimUtils.dp(context, 8).toFloat()
            }
        }
        row.addView(textCol); row.addView(checkBadge)
        row.setOnClickListener {
            val nowSelected = if (selectedUins.contains(friend.uin)) { selectedUins.remove(friend.uin); false } else { selectedUins.add(friend.uin); true }
            onToggle()
            if (nowSelected && friend.totalAttr <= 0L) {
                val active = engine ?: HookEntry.globalEngine
                if (active != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val enriched = active.enrichFriendDetailsAwait(friend)
                        val cached = PetAdventureEngine.loadCachedHireableFriends(context).toMutableList()
                        val i = cached.indexOfFirst { it.uin == friend.uin }
                        if (i >= 0) {
                            cached[i] = enriched
                            PetAdventureEngine.saveCachedHireableFriends(context, cached)
                        }
                    }
                }
            }
        }
        container.addView(row)
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
            val width = (context.resources.displayMetrics.widthPixels * 0.90f).toInt()
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.45f)
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
}
