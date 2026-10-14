package com.crabot.voice

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private enum class Page { HOME, SETTINGS, CONTACTS, NEW_CONTACT, CONTACT, RENAME, ENROLL }
    private lateinit var controller: VoiceController
    private lateinit var content: LinearLayout
    private lateinit var connectionState: TextView
    private lateinit var mainAction: Button
    private var page = Page.HOME
    private var renderedKey = ""
    private var selectedId: String? = null
    private var nameDraft = ""
    private var renameDraft = ""
    private var shownTask: String? = null
    private var diagnostics = false
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
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(surface)
            setPadding(dp(22), dp(14), dp(22), dp(14))
            setOnApplyWindowInsetsListener { view, insets -> view.setPadding(dp(22), insets.systemWindowInsetTop + dp(14), dp(22), insets.systemWindowInsetBottom + dp(14)); insets }
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text(brand, "Crabot", 25f)
        connectionState = text(brand, "正在连接", 12f, true)
        header.addView(brand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        button(header, "⚙", false) { navigate(if (page == Page.SETTINGS) Page.HOME else Page.SETTINGS) }.apply {
            contentDescription = "设置"; layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)); textSize = 23f
        }
        root.addView(header)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(18), 0, dp(12)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        mainAction = button(root, "开启语音待机", true) {
            if (!controller.paired) navigate(Page.SETTINGS)
            else if (controller.mode != VoiceController.Mode.CLOSED || controller.enrollmentName != null || controller.pendingManagement) controller.mute()
            else withMicrophone { controller.toggleWake(); navigate(Page.HOME) }
        }
        setContentView(root)
        controller = VoiceController(this) { render() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page == Page.HOME) { isEnabled = false; onBackPressedDispatcher.onBackPressed(); return }
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
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, if (primary) Color.WHITE else ink)))
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), GradientDrawable().apply { setColor(line); cornerRadius = dp(16).toFloat() })
            addState(intArrayOf(), GradientDrawable().apply { setColor(if (primary) accent else Color.WHITE); cornerRadius = dp(16).toFloat(); if (!primary) setStroke(dp(1), line) })
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(4) }
        setOnClickListener { action() }; parent.addView(this)
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
    private fun navigate(next: Page) { page = next; renderedKey = ""; render() }
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
        connectionState.text = if (!controller.connected) "● 未连接" else if (!controller.synchronizedCatalog) "● 已连接 · 目录同步中" else "● 已连接 · ${if (controller.microphoneState == "closed") "麦克风关闭" else if (mode == VoiceController.Mode.WAKE) "本机唤醒" else "正在收音"}"
        val taskId = controller.pendingTask?.getJSONObject("registration")?.getString("registration_id")
        if (taskId != null && taskId != shownTask) { shownTask = taskId; page = Page.ENROLL; renderedKey = "" }
        val inactive = mode == VoiceController.Mode.CLOSED && controller.enrollmentName == null && !controller.pendingManagement
        mainAction.text = if (!controller.paired) "连接 Crabot" else if (inactive) "开启语音待机" else "静音"
        mainAction.isEnabled = !controller.paired || !inactive || (controller.modelReady && controller.readyForConversation)
        if (controller.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val key = when (page) {
            Page.NEW_CONTACT, Page.RENAME -> "$page|$selectedId|${controller.synchronizedCatalog}|${controller.modelReady}|${controller.pendingManagement}"
            else -> "$page|$selectedId|${controller.catalogRevision}|$mode|${controller.lastReply}|${controller.status}|${controller.enrollmentStep}|${controller.pendingManagement}|${controller.waitingConfirmation}|$diagnostics"
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
        }
    }
    private fun home() {
        val reply = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panel(); setPadding(dp(20), dp(18), dp(20), dp(18)) }
        text(reply, if (controller.mode == VoiceController.Mode.PLAYING) "Crabot 正在回答" else "最近回答", 13f, true)
        text(reply, controller.lastReply.ifEmpty { "开启语音待机后，喊唤醒词开始对话。" }, 22f)
        content.addView(reply)
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(25), 0, dp(20)) }
        val orb = button(hero, "▂ ▆ █ ▆ ▂") { withMicrophone { controller.recordButton() } }.apply {
            contentDescription = "手动开始说话"; textSize = 26f; setTextColor(accent); layoutParams = LinearLayout.LayoutParams(dp(110), dp(110))
            isEnabled = controller.readyForConversation && controller.mode in listOf(VoiceController.Mode.CLOSED, VoiceController.Mode.WAKE, VoiceController.Mode.FOLLOWUP)
        }
        orb.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        val (title, hint) = when (controller.mode) {
            VoiceController.Mode.WAKE -> "等你唤醒" to "喊「${controller.wakePhrase}」，看到我在听后说内容。"
            VoiceController.Mode.RECORDING -> "我在听" to "说完稍作停顿，会自动结束收音。"
            VoiceController.Mode.PROCESSING, VoiceController.Mode.WAITING -> "正在处理" to "麦克风已关闭，正在准备回答。"
            VoiceController.Mode.PLAYING -> "正在回答" to "需要插话，可以点下面的打断。"
            VoiceController.Mode.FOLLOWUP -> "可以接着说" to "直接说下一句，不用再喊唤醒词。"
            else -> "麦克风已关闭" to if (controller.connected) "想聊的时候，开启一次语音待机。" else "请先在设置中连接 Crabot。"
        }
        text(hero, title, 25f).gravity = Gravity.CENTER; text(hero, hint, 15f, true).gravity = Gravity.CENTER
        text(hero, when (controller.mode) { VoiceController.Mode.WAKE -> "本机唤醒检测"; VoiceController.Mode.FOLLOWUP -> "8 秒本地接话窗口"; else -> if (controller.microphoneState == "closed") "麦克风关闭" else "正在收音" }, 12f, true).gravity = Gravity.CENTER
        content.addView(hero)
        if (controller.speakerSummary.isNotEmpty()) text(content, "本轮识别：${controller.speakerSummary}", 13f, true)
        if (controller.mode == VoiceController.Mode.PLAYING) button(content, "打断并说话", true) { withMicrophone { controller.recordButton() } }
        if (controller.mode == VoiceController.Mode.RECORDING) button(content, "结束说话") { controller.recordButton() }
        if (!controller.readyForConversation && controller.connected) text(content, controller.status, 14f, true)
        if (listOf("失败", "拒绝", "未检测", "失效", "过期", "超时", "尚未确认", "无法", "错误", "不兼容", "中断").any { controller.status.contains(it) }) text(content, controller.status, 14f)
    }
    private fun settings() {
        text(content, "设置", 27f)
        button(content, "声纹与联系人") { navigate(Page.CONTACTS) }
        text(content, "唤醒词：${controller.wakePhrase}", 16f)
        text(content, "唤醒词由 Crabot 下发，检测在手机本地进行。", 14f, true)
        button(content, "扫描配对二维码") { scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setOrientationLocked(true).setBeepEnabled(false)) }
        button(content, "重新连接") { controller.reconnect() }
        button(content, "手动说话") { withMicrophone { controller.recordButton(); navigate(Page.HOME) } }.isEnabled = controller.readyForConversation
        button(content, "${if (diagnostics) "收起" else "查看"}连接与诊断") { diagnostics = !diagnostics; renderedKey = ""; render() }
        if (diagnostics) { text(content, controller.status, 14f, true); text(content, controller.destinations, 13f, true); text(content, controller.detail, 13f, true) }
        text(content, "退后台、锁屏或断开连接会关闭麦克风和播放。恢复后需明确重新开启语音。", 14f, true)
        button(content, "清除本机配对与声纹") {
            android.app.AlertDialog.Builder(this).setTitle("清除本机数据？").setMessage("本机配对和声纹将无法恢复。后台联系人与好友绑定保留；之后需重新配对和录入。").setNegativeButton("取消", null).setPositiveButton("确认清除") { _, _ -> controller.forget(); navigate(Page.HOME) }.show()
        }
        button(content, "返回对话") { navigate(Page.HOME) }
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
        button(content, "录入声纹", true) { nameDraft = ""; navigate(Page.NEW_CONTACT) }.isEnabled = controller.synchronizedCatalog && controller.modelReady && controller.pendingTask == null && !controller.pendingManagement
        if (controller.pendingTask != null) button(content, "继续当前录入") { navigate(Page.ENROLL) }
        if (controller.pendingManagement || controller.waitingConfirmation) { text(content, controller.status, 14f, true); button(content, "重试同步") { controller.retrySynchronization() } }
        button(content, "返回设置") { navigate(Page.SETTINGS) }
    }
    private fun contact() {
        val contact = selectedContact() ?: run { page = Page.CONTACTS; contacts(); return }
        text(content, contact.getString("display_name"), 27f)
        text(content, "重录和改名保持同一联系人。好友绑定在 Crabot 管理后台维护。", 15f, true)
        button(content, "重新录制", true) { withMicrophone { controller.reEnroll(selectedId!!); navigate(Page.ENROLL) } }.isEnabled = controller.synchronizedCatalog && controller.pendingTask == null && !controller.pendingManagement
        button(content, "修改名字") { renameDraft = contact.getString("display_name"); navigate(Page.RENAME) }.isEnabled = controller.synchronizedCatalog && controller.pendingTask == null && !controller.pendingManagement
        activeSample()?.let { sample -> button(content, "删除声纹") {
            android.app.AlertDialog.Builder(this).setTitle("删除声纹？").setMessage("将无法通过此声纹识别人。联系人、好友绑定和历史对话会保留。").setNegativeButton("保留", null).setPositiveButton("删除声纹") { _, _ -> controller.deleteVoiceSample(sample.getString("registration_id")) }.show()
        }.isEnabled = controller.synchronizedCatalog && !controller.pendingManagement }
        text(content, controller.status, 14f, true)
        button(content, "返回列表") { navigate(Page.CONTACTS) }
    }
    private fun enrollment() {
        if (controller.enrollmentCompleted && controller.pendingTask == null) {
            text(content, "录入已完成", 26f); text(content, "声纹已同步到 Crabot，可以在后台将联系人绑定到已有好友。", 18f)
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
