package com.baer.handtype.feature.intake

import android.content.Intent

/** Text another app sent via the share sheet (ACTION_SEND, text/plain), or null. */
object SharedTextIntake {
    private const val ACTION_SEND = "android.intent.action.SEND"

    fun textFrom(intent: Intent?): String? =
        intent?.let { textFrom(it.action, it.type, it.getCharSequenceExtra(Intent.EXTRA_TEXT)) }

    fun textFrom(action: String?, type: String?, text: CharSequence?): String? {
        if (action != ACTION_SEND || type != "text/plain") return null
        return text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
