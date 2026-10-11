package com.crabot.voice

import android.Manifest
import android.content.pm.PackageManager
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
    private lateinit var record: Button
    private lateinit var wake: Button
    private lateinit var enrollment: Button
    private var pendingMicAction: (() -> Unit)? = null
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingMicAction; pendingMicAction = null
        if (granted && controller.foreground) action?.invoke() else controller.mute()
    }
    private val scan = registerForActivityResult(ScanContract()) { result -> result.contents?.let { controller.scan(it) } }
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
        fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setPadding(0, 8, 0, 8); root.addView(this) }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() }; root.addView(this) }
        text("Crabot 语音渠道", 26f)
        text("仅在前台工作。默认关闭麦克风，锁屏、退后台或断连立即停止。")
        state = text("正在准备…", 20f)
        destinations = text("尚未连接音频服务")
        button("扫描 Admin 配对二维码") { scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描 voice 实例的配对二维码").setBeepEnabled(false)) }
        button("重新连接（保持麦克风关闭）") { controller.reconnect() }
        record = button("开始一轮录音") { withMicrophone { controller.recordButton() } }
        wake = button("开启本地唤醒待机") { if (controller.wakeEnabled) controller.toggleWake() else withMicrophone { controller.toggleWake() } }
        enrollment = button("现场确认并开始登记") { withMicrophone { controller.confirmEnrollment() } }
        button("完全静音 / 停止播放与等待") { controller.mute() }
        text("播放中可点按录音键打断；AEC / 语音打断尚未验收，播放时不开麦。声纹只识别归属，不提升语音群权限。")
        text("模型已固定，但真人准确率、唤醒误触发、响应速度及功耗尚未验收。云音频留存取决于所选服务。")
        detail = text("")
        button("清除本机配对与声纹") {
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
        state.text = "麦克风：${when (controller.microphoneState) { "closed" -> "关闭"; "wake" -> "本地检测"; else -> "有限录音" }}\n${controller.status}"
        destinations.text = "${if (controller.connected) "已连接" else "未连接"}\n${controller.destinations}"
        detail.text = controller.detail
        record.isEnabled = controller.connected && controller.modelReady
        record.text = when (controller.mode) {
            VoiceController.Mode.RECORDING, VoiceController.Mode.ENROLLING -> "结束录音"
            VoiceController.Mode.PLAYING, VoiceController.Mode.WAITING -> "打断并开始新一轮"
            VoiceController.Mode.PROCESSING -> "取消处理并开始新一轮"
            else -> if (controller.enrollmentName != null) "录制下一段登记（3–5 秒）" else "开始一轮录音"
        }
        wake.isEnabled = controller.connected && controller.modelReady
        wake.text = if (controller.wakeEnabled) "关闭本地唤醒待机" else "开启本地唤醒待机"
        enrollment.visibility = if (controller.enrollmentName != null && controller.mode == VoiceController.Mode.CLOSED) View.VISIBLE else View.GONE
        enrollment.text = "现场确认：登记 ${controller.enrollmentName}"
        if (controller.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onResume() { super.onResume(); if (::controller.isInitialized) controller.resume() }
    override fun onPause() { pendingMicAction = null; if (::controller.isInitialized) controller.pause(); super.onPause() }
    override fun onDestroy() { if (::controller.isInitialized) controller.destroy(); super.onDestroy() }
}
