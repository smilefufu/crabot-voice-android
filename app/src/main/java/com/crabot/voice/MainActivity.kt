package com.crabot.voice

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private lateinit var controller: VoiceController
    private lateinit var state: TextView
    private lateinit var destinations: TextView
    private lateinit var detail: TextView
    private lateinit var instruction: TextView
    private lateinit var record: Button
    private lateinit var wake: Button
    private lateinit var stop: Button
    private var pendingMicAction: (() -> Unit)? = null
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingMicAction; pendingMicAction = null
        if (granted && controller.foreground) action?.invoke() else controller.mute()
    }
    private val scan = registerForActivityResult(ScanContract()) { result ->
        android.util.Log.i("CrabotPairing", if (result.contents == null) "scan_cancelled" else "scan_result_received")
        result.contents?.let { controller.scan(it) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = (20 * resources.displayMetrics.density).toInt()
            val vertical = (12 * resources.displayMetrics.density).toInt()
            setPadding(side, vertical, side, vertical)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(side, insets.systemWindowInsetTop + vertical, side, insets.systemWindowInsetBottom + vertical)
                insets
            }
        }
        fun text(value: String, size: Float = 16f, parent: LinearLayout = root) = TextView(this).apply { text = value; textSize = size; setPadding(0, 8, 0, 8); parent.addView(this) }
        fun button(label: String, parent: LinearLayout = root, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() }; parent.addView(this) }
        text("Crabot 语音", 26f)
        state = text("正在准备…")
        instruction = text("连接后，点下面的按钮开始说话。", 20f)
        record = button("开始说话") { withMicrophone {
            if (controller.enrollmentName != null && controller.mode == VoiceController.Mode.CLOSED) controller.confirmEnrollment()
            else controller.recordButton()
        } }.apply {
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            minHeight = (88 * resources.displayMetrics.density).toInt()
            backgroundTintList = ColorStateList.valueOf(Color.rgb(33, 90, 214))
            setTextColor(Color.WHITE)
        }
        wake = button("开启本地唤醒待机") { if (controller.wakeEnabled) controller.toggleWake() else withMicrophone { controller.toggleWake() } }
        stop = button("停止语音 / 关闭麦克风") { controller.mute() }
        detail = text("")
        text("默认关闭麦克风。开启本地唤醒后可直接喊唤醒词；切后台、锁屏或断连会停止收音和播放。", 14f)
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        button("连接与说明") { settings.visibility = if (settings.visibility == View.GONE) View.VISIBLE else View.GONE }
        root.addView(settings)
        destinations = text("尚未连接音频服务", parent = settings)
        button("扫描配对二维码", settings) { scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描 Crabot 后台的配对二维码").setBeepEnabled(false)) }
        button("重新连接", settings) { controller.reconnect() }
        text("声纹登记从 Crabot 后台发起，手机会显示登记提示；收到任务后点主按钮现场确认，录三段不同内容，每段 3–5 秒。", parent = settings)
        text("播放期间关闭麦克风，可点主按钮打断并说话。停止语音不会取消已经接纳的后台任务。", parent = settings)
        text("识别准确率、唤醒误触发、响应速度、回声处理及功耗尚未验收。声纹只识别归属，不提升语音群权限。云音频留存取决于所选服务。", 14f, settings)
        button("清除本机配对与声纹", settings) {
            android.app.AlertDialog.Builder(this).setMessage("将删除本机凭据和加密声纹。重新配对前还需在 Admin 撤销旧终端。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> controller.forget() }.show()
        }
        setContentView(ScrollView(this).apply { addView(root) })
        controller = VoiceController(this, ::render)
        render()
    }
    private fun withMicrophone(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingMicAction = action; microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    private fun render() {
        if (!::controller.isInitialized) return
        state.text = "${if (controller.connected) "已连接" else "未连接"} · 麦克风${when (controller.microphoneState) { "closed" -> "关闭"; "wake" -> "本地检测中"; else -> "录音中" }}\n${controller.status}"
        destinations.text = "${if (controller.connected) "已连接" else "未连接"}\n${controller.destinations}"
        detail.text = controller.detail
        detail.visibility = if (controller.detail.isBlank()) View.GONE else View.VISIBLE
        instruction.text = when {
            !controller.connected -> "打开“连接与说明”，扫码或重新连接。"
            !controller.modelReady -> "正在准备语音，请稍等。若准备失败，上方会显示原因。"
            controller.enrollmentName != null -> "声纹登记：${controller.enrollmentName}\n每段说 3–5 秒，共三段；说不同的内容。"
            else -> when (controller.mode) {
                VoiceController.Mode.RECORDING -> "现在说话。说完停顿会自动结束，也可点按钮提前结束。"
                VoiceController.Mode.PROCESSING -> "正在识别这段语音，请稍等。"
                VoiceController.Mode.WAITING -> "正在等待 Crabot 回答。"
                VoiceController.Mode.PLAYING -> "Crabot 正在回答。需要时可以打断并说话。"
                VoiceController.Mode.WAKE -> "本地唤醒已开启，可以说后台配置的唤醒词。"
                VoiceController.Mode.FOLLOWUP -> "可以直接接着说；接话窗口结束后自动待机或关麦。"
                else -> "点“开始说话”，说完停顿即可。无需再点一次。"
            }
        }
        record.isEnabled = controller.connected && controller.modelReady
        record.text = when {
            !controller.connected -> "尚未连接"
            !controller.modelReady -> "正在准备语音"
            else -> when (controller.mode) {
                VoiceController.Mode.RECORDING, VoiceController.Mode.ENROLLING -> "结束录音"
                VoiceController.Mode.PLAYING -> "打断并说话"
                VoiceController.Mode.WAITING -> "取消等待，重新说"
                VoiceController.Mode.PROCESSING -> "取消处理，重新说"
                else -> if (controller.enrollmentName != null) "现场确认并录音" else "开始说话"
            }
        }
        wake.isEnabled = controller.connected && controller.modelReady
        wake.visibility = if (controller.enrollmentName == null) View.VISIBLE else View.GONE
        wake.text = if (controller.wakeEnabled) "关闭唤醒待机" else "开启唤醒待机"
        stop.visibility = if (controller.mode != VoiceController.Mode.CLOSED || controller.enrollmentName != null) View.VISIBLE else View.GONE
        if (controller.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onResume() { super.onResume(); if (::controller.isInitialized) controller.resume() }
    override fun onPause() { pendingMicAction = null; if (::controller.isInitialized) controller.pause(); super.onPause() }
    override fun onDestroy() { if (::controller.isInitialized) controller.destroy(); super.onDestroy() }
}
