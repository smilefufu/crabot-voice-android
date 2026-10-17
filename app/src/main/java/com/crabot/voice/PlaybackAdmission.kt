package com.crabot.voice

/** Device facts only: this policy neither identifies speakers nor authorizes tasks. */
object PlaybackAdmission {
    fun allows(kind: String, mode: String, foreground: Boolean, connected: Boolean, speechEnabled: Boolean,
               ready: Boolean, sameTurn: Boolean, occupied: Boolean): Boolean {
        if (!foreground || !connected || !speechEnabled || !ready || occupied) return false
        return when (kind) {
            "reply" -> sameTurn && mode == "WAITING"
            "notification" -> mode in setOf("CLOSED", "WAKE", "FOLLOWUP")
            else -> false
        }
    }
}
