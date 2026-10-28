package com.crabot.voice

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private enum class Page { HOME, SETTINGS, CONTACTS, NEW_CONTACT, CONTACT, RENAME, ENROLL, OWNER, CONNECT }
    private lateinit var controller: VoiceController
    private lateinit var content: LinearLayout
    private lateinit var connectionState: TextView
    private lateinit var settingsAction: Button
    private lateinit var mainAction: Button
    private lateinit var footer: TextView
    private var page = Page.HOME
    private var renderedKey = ""
    private var selectedId: String? = null
    private var ownerDraft = ""
    private var nameDraft = ""
    private var renameDraft = ""
    private var shownTask: String? = null
    private var diagnostics = false
    private var replyExpanded = false
    private var pendingMicAction: (() -> Unit)? = null
    private val ink = Color.rgb(24, 45, 32)
    private val muted = Color.rgb(82, 102, 87)
    private val accent = Color.rgb(36, 94, 60)
    private val surface = Color.rgb(244, 247, 243)
    private val line = Color.rgb(221, 230, 221)
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingMicAction; pendingMicAction = null
        if (granted && controller.foreground) action?.invoke() else controller.mute()
    }
    private val scan = registerForActivityResult(ScanContract()) { result -> result.contents?.let { controller.scan(it) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = surface
        window.navigationBarColor = surface
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(surface)
            setPadding(dp(22), dp(14), dp(22), dp(14))
            setOnApplyWindowInsetsListener { view, insets -> view.setPadding(dp(22), insets.systemWindowInsetTop + dp(14), dp(22), insets.systemWindowInsetBottom + dp(14)); insets }
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text(brand, "Crabot", 26f).setTypeface(null, Typeface.BOLD)
        connectionState = text(brand, "正在连接", 12f, true).apply { setPadding(0, 0, 0, dp(4)) }
        header.addView(brand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        settingsAction = button(header, "设置", false) { navigate(if (page == Page.SETTINGS) Page.HOME else Page.SETTINGS) }.apply {
            contentDescription = "设置"; layoutParams = LinearLayout.LayoutParams(dp(64), dp(48)); textSize = 14f
        }
        root.addView(header)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(18), 0, dp(12)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        mainAction = button(root, "开启语音待机", true) {
            if (controller.ownerEnrollmentActive) controller.mute()
            else if (page == Page.CONNECT && controller.paired) { if (controller.ownerActivation?.getString("status") == "pending") controller.retrySynchronization() else navigate(Page.SETTINGS) }
            else if (controller.needsOwnerEnrollment) {
                if (page == Page.OWNER) withMicrophone { controller.enrollOwner(ownerDraft) } else navigate(Page.OWNER)
            } else if (!controller.paired && controller.owner != null) launchScan()
            else if (!controller.paired) navigate(Page.SETTINGS)
            else if (controller.mode != VoiceController.Mode.CLOSED || controller.enrollmentName != null || controller.pendingManagement) controller.mute()
            else withMicrophone { controller.toggleWake(); navigate(Page.HOME) }
        }
        footer = text(root, "开启一次，随时唤醒", 12f, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        setContentView(root)
        controller = VoiceController(this) { render() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page == Page.OWNER) controller.mute()
                if (page in listOf(Page.HOME, Page.OWNER, Page.CONNECT)) { isEnabled = false; onBackPressedDispatcher.onBackPressed(); return }
                if (page == Page.ENROLL) controller.cancelEnrollment()
                navigate(if (page in listOf(Page.CONTACT, Page.NEW_CONTACT, Page.RENAME, Page.ENROLL)) Page.CONTACTS else Page.HOME)
            }
        })
        render()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun panel() = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat(); setStroke(dp(1), line) }
    private fun text(parent: LinearLayout, value: String, size: Float = 16f, secondary: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(if (secondary) muted else ink); setPadding(0, dp(5), 0, dp(7)); parent.addView(this)
    }
    private fun button(parent: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 16f; isAllCaps = false; minHeight = dp(52); setPadding(dp(12), dp(8), dp(12), dp(8))
        stateListAnimator = null; elevation = 0f
        styleButton(this, primary)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(4) }
        setOnClickListener { action() }; parent.addView(this)
    }
    private fun styleButton(view: Button, primary: Boolean) {
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, if (primary) Color.WHITE else ink)))
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), GradientDrawable().apply { setColor(line); cornerRadius = dp(16).toFloat() })
            addState(intArrayOf(), GradientDrawable().apply { setColor(if (primary) accent else Color.WHITE); cornerRadius = dp(16).toFloat(); if (!primary) setStroke(dp(1), line) })
        }
        view.background = RippleDrawable(ColorStateList.valueOf(Color.argb(35, 36, 94, 60)), states, null)
    }
    private fun settingsGroup(title: String): LinearLayout {
        text(content, title, 13f, true).setPadding(dp(4), dp(18), 0, dp(10))
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panel(); clipToOutline = true; content.addView(this) }
    }
    private fun settingsRow(parent: LinearLayout, title: String, summary: String, action: (() -> Unit)? = null): LinearLayout {
        if (parent.childCount > 0) parent.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(10), dp(16), dp(10)); minimumHeight = dp(72)
            val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            text(labels, title, 17f)
            text(labels, summary, 13f, true)
            addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (action != null) {
                text(this, "›", 24f, true).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = RippleDrawable(ColorStateList.valueOf(Color.argb(35, 36, 94, 60)), null, GradientDrawable().apply { setColor(Color.WHITE) })
                isFocusable = true; contentDescription = "$title，$summary"; setOnClickListener { action() }
            }
            parent.addView(this)
        }
    }
    private fun input(label: String, initial: String, change: (String) -> Unit): EditText {
        val caption = text(content, label, 14f, true)
        return EditText(this).apply {
            id = View.generateViewId(); caption.labelFor = id; contentDescription = label
            textSize = 18f; setTextColor(ink); isSingleLine = true; minHeight = dp(52)
            setText(initial); setSelection(text.length); background = panel(); setPadding(dp(14), dp(10), dp(14), dp(10))
            filters = arrayOf(android.text.InputFilter.LengthFilter(128)); content.addView(this)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { change(s.toString()) }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
    }
    private fun withMicrophone(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingMicAction = action; microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    private fun launchScan() {
        if (controller.owner == null) { Toast.makeText(this, "请先完成本人声纹登记或连接原 Crabot 恢复引用", Toast.LENGTH_LONG).show(); return }
        scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setOrientationLocked(true).setBeepEnabled(false))
    }
    private fun navigate(next: Page) {
        if (page == Page.OWNER && next != Page.OWNER && controller.ownerEnrollmentActive) controller.mute()
        page = next; renderedKey = ""; render()
    }
    private fun selectedContact(): JSONObject? {
        val contacts = controller.contacts
        return (0 until contacts.length()).map { contacts.getJSONObject(it) }.find { it.getString("platform_user_id") == selectedId }
    }
    private fun activeSample(): JSONObject? {
        val registrations = controller.voiceRegistrations
        return (0 until registrations.length()).map { registrations.getJSONObject(it) }.find { it.getString("platform_user_id") == selectedId && it.getString("status") == "active" }
    }
    private fun render() {
        if (!::controller.isInitialized) return
        val mode = controller.mode
        if (controller.needsOwnerEnrollment && page == Page.HOME) page = Page.OWNER
        if (!controller.paired && controller.owner != null && page in listOf(Page.HOME, Page.OWNER)) { page = Page.CONNECT; renderedKey = "" }
        if (page == Page.CONNECT && controller.paired && controller.ownerActivation?.getString("status") == "completed") { page = Page.HOME; renderedKey = "" }
        settingsAction.text = if (page == Page.SETTINGS) "完成" else "设置"
        settingsAction.contentDescription = if (page == Page.SETTINGS) "返回对话" else "设置"
        connectionState.text = if (!controller.connected) "● 未连接" else if (!controller.synchronizedCatalog) "● 已连接 · 正在同步" else "● 已连接"
        val taskId = controller.pendingTask?.getJSONObject("registration")?.getString("registration_id")
        if (taskId != null && taskId != shownTask) { shownTask = taskId; page = Page.ENROLL; renderedKey = "" }
        val inactive = mode == VoiceController.Mode.CLOSED && controller.enrollmentName == null && !controller.pendingManagement
        mainAction.text = when {
            controller.ownerEnrollmentActive -> "取消登记"
            page == Page.CONNECT && controller.paired -> if (controller.ownerActivation?.getString("status") == "pending") "重试关联同步" else "查看连接状态"
            controller.needsOwnerEnrollment -> if (page == Page.OWNER) "开始登记我的声音" else "登记我的声音"
            !controller.paired && controller.owner != null -> "扫描二维码连接"
            !controller.paired -> "连接 Crabot"
            inactive -> "开启语音待机"
            else -> "静音"
        }
        mainAction.isEnabled = if (page == Page.CONNECT) true else if (controller.needsOwnerEnrollment && !controller.ownerEnrollmentActive && page == Page.OWNER) controller.modelReady && ownerDraft.trim().isNotEmpty()
            else !controller.paired || !inactive || controller.modelReady && controller.readyForConversation
        styleButton(mainAction, inactive || !controller.paired)
        footer.visibility = if (page == Page.HOME) View.VISIBLE else View.GONE
        footer.text = if (controller.wakeEnabled && mode != VoiceController.Mode.CLOSED) "待机已开启 · 对话后自动回到本机唤醒" else "开启一次，随时唤醒 · 退出前台后关麦"
        if (controller.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val key = when (page) {
            Page.NEW_CONTACT, Page.RENAME -> "$page|$selectedId|${controller.synchronizedCatalog}|${controller.modelReady}|${controller.pendingManagement}"
            else -> "$page|$selectedId|${controller.catalogRevision}|$mode|${controller.lastReply}|${controller.pendingResults}|${controller.status}|${controller.lastOutcome}|${controller.enrollmentStep}|${controller.pendingManagement}|${controller.waitingConfirmation}|$diagnostics|$replyExpanded"
        }
        if (key == renderedKey) return
        renderedKey = key; content.removeAllViews()
        when (page) {
            Page.HOME -> home()
            Page.SETTINGS -> settings()
            Page.CONTACTS -> contacts()
            Page.CONTACT -> contact()
            Page.NEW_CONTACT -> {
                text(content, "录入声纹", 26f); input("联系人名字", nameDraft) { nameDraft = it }
                text(content, "录三段短句，成功确认后会自动同步为 Crabot 的渠道联系人。", 15f, true)
                button(content, "开始录入", true) {
                    if (nameDraft.trim().isEmpty()) Toast.makeText(this, "请填写联系人名字", Toast.LENGTH_SHORT).show()
                    else withMicrophone { controller.enrollNew(nameDraft); navigate(Page.ENROLL) }
                }.isEnabled = controller.synchronizedCatalog && controller.modelReady && !controller.pendingManagement
                button(content, "取消") { navigate(Page.CONTACTS) }
            }
            Page.RENAME -> {
                text(content, "修改名字", 26f); input("联系人名字", renameDraft) { renameDraft = it }
                text(content, "只修改渠道联系人名字，好友绑定和历史保持。", 15f, true)
                button(content, "保存名字", true) { if (renameDraft.trim().isNotEmpty()) { controller.renameContact(selectedId!!, renameDraft); navigate(Page.CONTACT) } }.isEnabled = controller.synchronizedCatalog && !controller.pendingManagement
                button(content, "取消") { navigate(Page.CONTACT) }
            }
            Page.ENROLL -> enrollment()
            Page.OWNER -> ownerEnrollment()
            Page.CONNECT -> connectOwner()
        }
    }
    private fun ownerEnrollment() {
        text(content, "先认识你的声音", 27f)
        text(content, "1 登记本人  ·  2 扫码连接  ·  3 自动关联", 13f, true)
        if (!controller.ownerEnrollmentActive) {
            input("你的名字", ownerDraft) {
                ownerDraft = it
                mainAction.isEnabled = controller.modelReady && ownerDraft.trim().isNotEmpty()
            }
            text(content, "接下来读三句不同的话。点一次开始，手机会依次提示、自动录制。", 17f)
            text(content, "声纹保存在本机。扫码后自动关联，无需再录一遍或手动绑定本人。", 14f, true)
            if (!controller.modelReady) text(content, "正在准备本机语音能力，请稍候。", 14f, true)
        } else {
            text(content, "第 ${controller.enrollmentStep.coerceAtMost(3)} / 3 段", 20f)
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panel(); setPadding(dp(20), dp(18), dp(20), dp(18)) }
            text(card, if (controller.mode == VoiceController.Mode.OWNER_RECORDING) "正在录音，请自然朗读" else "请准备朗读", 14f, true)
            text(card, controller.ownerPrompt, 23f)
            content.addView(card)
            text(content, "说完稍作停顿即可，每段约 3–5 秒。不需要逐段点按钮。", 14f, true)
            if (controller.ownerNeedsRetry) button(content, "重新录这一段", true) { withMicrophone { controller.retryOwnerClip() } }
        }
        text(content, controller.status, 14f, true)
    }
    private fun connectOwner() {
        text(content, if (controller.paired) "正在完成关联" else "连接你的 Crabot", 27f)
        text(content, "1 本人已登记  ·  2 扫码连接  ·  3 自动关联", 13f, true)
        text(content, "已记住 ${controller.owner?.getString("display_name") ?: "你"} 的声音", 21f)
        if (!controller.paired) {
            text(content, "在 Crabot 后台创建语音渠道，然后扫描配对二维码。", 18f)
            text(content, "核对名字和代码后，后台会自动完成所有者关联，无需再录一次。", 15f, true)
        } else text(content, "本人声纹已保存在本机，正在等待后台确认联系人和 Master 关联。", 17f)
        text(content, controller.status, 14f, true)
        text(content, "麦克风保持关闭。语音服务在 Crabot 后台配置。", 14f, true)
    }
    private fun home() {
        val reply = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panel(); setPadding(dp(24), dp(20), dp(24), dp(20)) }
        val replyHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        text(replyHeader, "Crabot", 13f, true).layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        text(replyHeader, if (controller.mode == VoiceController.Mode.PLAYING) "正在回答" else if (controller.lastReply.isEmpty()) "很高兴见到你" else "最近回答", 12f, true)
        reply.addView(replyHeader)
        val replyText = text(reply, controller.lastReply.ifEmpty { if (controller.readyForConversation) "想聊什么？\n我在这里。" else "连接你的 Crabot，\n从一句问候开始。" }, 22f).apply {
            setLineSpacing(dp(4).toFloat(), 1.15f)
            if (!replyExpanded) { maxLines = 6; ellipsize = TextUtils.TruncateAt.END }
        }
        replyText.doOnLayout {
            val lines = replyText.layout ?: return@doOnLayout
            if (replyExpanded || lines.lineCount > 0 && lines.getEllipsisCount(lines.lineCount - 1) > 0) {
                button(reply, if (replyExpanded) "收起回答" else "展开完整回答") { replyExpanded = !replyExpanded; render() }
            }
        }
        content.addView(reply)
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(32), 0, dp(20)) }
        hero.addView(VoiceStatusView(this, controller.mode).apply {
            contentDescription = "手动开始说话"; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(100), dp(100)).apply { bottomMargin = dp(18) }
            setOnClickListener { withMicrophone { controller.recordButton() } }
            isEnabled = controller.readyForConversation && controller.mode in listOf(VoiceController.Mode.CLOSED, VoiceController.Mode.WAKE, VoiceController.Mode.FOLLOWUP)
        })
        val (title, hint) = when (controller.mode) {
            VoiceController.Mode.WAKE -> "等你唤醒" to "喊「${controller.wakePhrase}」\n看到“我在听”后，就可以说话。"
            VoiceController.Mode.RECORDING -> "我在听" to "说完稍作停顿，会自动结束收音。"
            VoiceController.Mode.PROCESSING, VoiceController.Mode.WAITING -> "正在处理" to "麦克风已关闭，正在准备回答。"
            VoiceController.Mode.PLAYING -> "正在回答" to "需要插话，可以点下面的打断。"
            VoiceController.Mode.FOLLOWUP -> "可以接着说" to "直接说下一句。暂时不聊，\n我会回到唤醒待机。"
            VoiceController.Mode.GUIDED_PLAYING -> "请听提示" to controller.guidedPromptText
            VoiceController.Mode.GUIDED_WAITING -> "正在记住声音" to controller.status
            VoiceController.Mode.ENROLLING -> "请自然朗读" to "第 ${controller.enrollmentStep}/3 段，说完稍作停顿，会自动继续。"
            else -> if (controller.connected && !controller.readyForConversation) "正在准备语音" to controller.status
                else "麦克风已关闭" to if (controller.connected) "想聊的时候，开启一次语音待机。" else "请先连接 Crabot。"
        }
        text(hero, title, 25f).apply { gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        text(hero, hint, 15f, true).apply { gravity = Gravity.CENTER; setLineSpacing(dp(3).toFloat(), 1f) }
        text(hero, when (controller.mode) { VoiceController.Mode.WAKE -> "本机唤醒检测 · 环境音不上传"; VoiceController.Mode.FOLLOWUP -> "8 秒接话 · 之后仍可用唤醒词"; else -> if (controller.microphoneState == "closed") "麦克风关闭" else "正在收音" }, 12f, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply { setColor(line); cornerRadius = dp(18).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        content.addView(hero)
        if (controller.pendingResults > 0) text(content, "还有一些新进展，等你方便时接着聊。", 13f, true)
        if (controller.speakerSummary.isNotEmpty()) text(content, "本轮识别：${controller.speakerSummary}", 13f, true)
        if (controller.speakerReason.isNotEmpty()) text(content, controller.speakerReason, 14f, true)
        if (controller.ownerRecoveryHint.isNotEmpty()) text(content, controller.ownerRecoveryHint, 14f, true)
        if (controller.mode == VoiceController.Mode.PLAYING) button(content, "打断并说话", true) { withMicrophone { controller.recordButton() } }
        if (controller.mode == VoiceController.Mode.RECORDING) button(content, "结束说话") { controller.recordButton() }
        if (controller.guidedEnrollmentActive) button(content, "取消本次录入") { controller.cancelEnrollment() }
        if (!controller.readyForConversation && controller.connected) text(content, controller.status, 14f, true)
        if (controller.lastOutcome.isNotEmpty()) text(content, "上一轮：${controller.lastOutcome}", 14f)
        if ((controller.lastOutcome.isEmpty() || !controller.status.startsWith(controller.lastOutcome)) &&
            listOf("失败", "拒绝", "未检测", "失效", "过期", "超时", "尚未确认", "无法", "错误", "不兼容", "中断").any { controller.status.contains(it) }) text(content, controller.status, 14f)
    }
    private fun settings() {
        text(content, "设置", 27f)
        val conversation = settingsGroup("日常使用")
        if (controller.paired) settingsRow(conversation, "声纹与联系人", "录入、重录和改名，自动同步到 Crabot") { navigate(Page.CONTACTS) }
        settingsRow(conversation, "唤醒词 · ${controller.wakePhrase}", "Crabot 下发配置，手机本机检测")
        settingsRow(conversation, "手动说话", "不方便喊唤醒词时，点按开始") { withMicrophone { controller.recordButton(); navigate(Page.HOME) } }.apply {
            isEnabled = controller.readyForConversation; alpha = if (isEnabled) 1f else .5f
        }
        val connection = settingsGroup("连接与诊断")
        if (controller.paired) settingsRow(connection, "扫描配对二维码", "在 Crabot 后台创建语音渠道") { launchScan() }.apply {
            isEnabled = controller.owner != null; alpha = if (isEnabled) 1f else .5f
        }
        settingsRow(connection, "重新连接", if (controller.connected) "当前已连接" else "检查网络后重试连接") { controller.reconnect() }
        settingsRow(connection, if (diagnostics) "收起诊断详情" else "查看诊断详情", "连接、声音识别及服务状态") { diagnostics = !diagnostics; renderedKey = ""; render() }
        if (diagnostics) { text(content, controller.status, 14f, true); text(content, controller.destinations, 13f, true); text(content, controller.detail, 13f, true) }
        controller.owner?.let { owner ->
            text(content, "设备所有者：${owner.getString("display_name")}", 18f)
            text(content, when (controller.ownerActivation?.getString("status")) {
                "completed" -> "所有者关联已完成。删除或重录声纹不会更换所有者。"
                "pending" -> "本人声纹已保存，正在等待 Crabot 完成关联。"
                else -> "本人声纹已录入。请在 Crabot 后台新建语音渠道，再扫描配对二维码。"
            }, 14f, true)
        }
        if (!controller.paired) text(content, "扫码时请核对名字和代码。语音识别、播报服务在 Crabot 后台配置。", 14f, true)
        if (controller.ownerActivation?.getString("status") == "pending") button(content, "重试关联同步") { controller.retrySynchronization() }
        text(content, "退后台、锁屏或断开连接会关闭麦克风和播放。恢复后需明确重新开启语音。", 14f, true)
        settingsRow(settingsGroup("设备数据"), "清除本机配对与声纹", "清除后需重新连接和登记声音") {
            android.app.AlertDialog.Builder(this).setTitle("清除本机数据？").setMessage("本机配对和声纹将无法恢复。后台联系人与好友绑定保留；之后需重新配对和录入。").setNegativeButton("取消", null).setPositiveButton("确认清除") { _, _ -> controller.forget(); navigate(Page.HOME) }.show()
        }
    }
    private fun contacts() {
        text(content, "声纹与联系人", 25f)
        text(content, if (!controller.connected) "离线 · 上次同步" else if (controller.synchronizedCatalog) "已与 Crabot 同步" else "正在核对目录", 13f, true)
        val items = controller.contacts
        val registrations = controller.voiceRegistrations
        if (items.length() == 0) { text(content, "还没有声纹", 23f); text(content, "录入成功后会自动同步为渠道联系人。", 15f, true) }
        for (i in 0 until items.length()) {
            val contact = items.getJSONObject(i); val id = contact.getString("platform_user_id")
            val sample = (0 until registrations.length()).map { registrations.getJSONObject(it) }.find { it.getString("platform_user_id") == id && it.getString("status") == "active" }
            val clearing = (0 until registrations.length()).any { val r = registrations.getJSONObject(it); r.getString("platform_user_id") == id && r.getString("status") == "revoked" && !r.getBoolean("terminal_cleared") }
            val state = if (sample == null) { if (clearing) "后台已撤销 · 本机清除待确认" else "已删除声纹 · 联系人保留" } else if (sample.getString("model_id") != Models.EMBEDDING || !controller.hasVoiceSample(sample.getString("registration_id"))) "需重新录制" else "声纹可识别"
            button(content, "${contact.getString("display_name")}\n$state") { selectedId = id; navigate(Page.CONTACT) }
        }
        button(content, "录入声纹", true) { nameDraft = ""; navigate(Page.NEW_CONTACT) }.isEnabled = controller.canManageContacts && controller.synchronizedCatalog && controller.modelReady && controller.pendingTask == null && !controller.pendingManagement
        if (controller.pendingTask != null) button(content, "继续当前录入") { navigate(Page.ENROLL) }
        if (controller.pendingManagement || controller.waitingConfirmation) { text(content, controller.status, 14f, true); button(content, "重试同步") { controller.retrySynchronization() } }
        button(content, "返回设置") { navigate(Page.SETTINGS) }
    }
    private fun contact() {
        val contact = selectedContact() ?: run { page = Page.CONTACTS; contacts(); return }
        text(content, contact.getString("display_name"), 27f)
        text(content, "重录和改名保持同一联系人。好友绑定在 Crabot 管理后台维护。", 15f, true)
        button(content, "重新录制", true) { withMicrophone { controller.reEnroll(selectedId!!); navigate(Page.ENROLL) } }.isEnabled = controller.canManageContacts && controller.synchronizedCatalog && controller.pendingTask == null && !controller.pendingManagement
        button(content, "修改名字") { renameDraft = contact.getString("display_name"); navigate(Page.RENAME) }.isEnabled = controller.canManageContacts && controller.synchronizedCatalog && controller.pendingTask == null && !controller.pendingManagement
        activeSample()?.let { sample -> button(content, "删除声纹") {
            android.app.AlertDialog.Builder(this).setTitle("删除声纹？").setMessage("将无法通过此声纹识别人。联系人、好友绑定和历史对话会保留。").setNegativeButton("保留", null).setPositiveButton("删除声纹") { _, _ -> controller.deleteVoiceSample(sample.getString("registration_id")) }.show()
        }.isEnabled = controller.synchronizedCatalog && !controller.pendingManagement }
        text(content, controller.status, 14f, true)
        button(content, "返回列表") { navigate(Page.CONTACTS) }
    }
    private fun enrollment() {
        if (controller.guidedEnrollmentActive) {
            text(content, "正在记住 ${controller.enrollmentName ?: "对方"} 的声音", 25f)
            text(content, controller.status, 18f); text(content, controller.guidedPromptText, 18f)
            button(content, "取消本次录入") { controller.cancelEnrollment() }
            return
        }
        if (controller.enrollmentCompleted && controller.pendingTask == null) {
            text(content, "录入已完成", 26f); text(content, "声音记录已同步，可以返回对话或查看联系人。", 18f)
            button(content, "查看联系人", true) { navigate(Page.CONTACTS) }; button(content, "返回对话") { navigate(Page.HOME) }; return
        }
        text(content, "录入 ${controller.enrollmentName ?: "声纹"}", 25f)
        val processing = controller.waitingConfirmation || controller.mode == VoiceController.Mode.PROCESSING
        text(content, if (controller.waitingConfirmation) "本机已保存，正在等待后台确认" else if (controller.mode == VoiceController.Mode.ENROLLING) "正在收音" else if (processing) "正在提取声纹" else if (controller.pendingTask == null) "等待登记状态确认" else "第 ${controller.enrollmentStep} 段 / 共 3 段", 22f)
        text(content, "每段说 3–5 秒，只让一个人清楚说话；使用不同短句。", 15f, true)
        if (!processing && controller.pendingTask != null) {
            val prompts = listOf("今天我想和 Crabot 聊聊接下来的安排。", "请帮我记住这个星期需要完成的事情。", "以后听到我的声音，请认出我是谁。")
            text(content, "“${prompts[(controller.enrollmentStep - 1).coerceIn(0, 2)]}”", 22f)
            button(content, if (controller.mode == VoiceController.Mode.ENROLLING) "结束这一段" else "录这一段", true) { withMicrophone { if (controller.mode == VoiceController.Mode.ENROLLING) controller.recordButton() else controller.confirmEnrollment() } }.isEnabled = controller.connected && controller.modelReady
        }
        text(content, controller.status, 14f, true)
        if (controller.pendingTask != null && !controller.waitingConfirmation) button(content, "取消录入") { controller.cancelEnrollment(); navigate(Page.CONTACTS) }
        if (controller.waitingConfirmation || controller.pendingManagement) button(content, "重试同步") { controller.retrySynchronization() }
        button(content, "返回联系人") { navigate(Page.CONTACTS) }
    }
    override fun onResume() { super.onResume(); if (::controller.isInitialized) controller.resume() }
    override fun onPause() { pendingMicAction = null; if (::controller.isInitialized) controller.pause(); super.onPause() }
    override fun onDestroy() { if (::controller.isInitialized) controller.destroy(); super.onDestroy() }
}
