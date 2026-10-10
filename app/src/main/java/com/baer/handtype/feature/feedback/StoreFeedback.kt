package com.baer.handtype.feature.feedback

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.baer.handtype.R
import com.google.android.play.core.review.ReviewManagerFactory

object StoreLinks {
    // Hardcoded so debug builds (".debug" suffix) still point at the real listing.
    const val RELEASE_PACKAGE = "com.baer.handtype"

    fun listingUrl(): String = "https://play.google.com/store/apps/details?id=$RELEASE_PACKAGE"

    fun marketUri(): String = "market://details?id=$RELEASE_PACKAGE"

    fun shareText(message: String): String = message + "\n" + listingUrl()
}

object ReviewPromptPolicy {
    const val THRESHOLD = 3

    fun shouldAsk(successfulOutputs: Int, alreadyShown: Boolean): Boolean =
        !alreadyShown && successfulOutputs >= THRESHOLD
}

class ReviewPromptStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("handtype_app_settings", Context.MODE_PRIVATE)

    fun recordSuccessAndCheck(): Boolean {
        val count = prefs.getInt(KEY_SUCCESSFUL_OUTPUTS, 0) + 1
        prefs.edit().putInt(KEY_SUCCESSFUL_OUTPUTS, count).apply()
        return ReviewPromptPolicy.shouldAsk(count, prefs.getBoolean(KEY_REVIEW_SHOWN, false))
    }

    fun markShown() {
        prefs.edit().putBoolean(KEY_REVIEW_SHOWN, true).apply()
    }

    private companion object {
        const val KEY_SUCCESSFUL_OUTPUTS = "successful_outputs"
        const val KEY_REVIEW_SHOWN = "review_prompt_shown"
    }
}

fun shareApp(context: Context) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, StoreLinks.shareText(context.getString(R.string.share_app_message)))
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.settings_share_app)))
}

fun openStoreListing(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(StoreLinks.marketUri())))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(StoreLinks.listingUrl())))
    }
}

/** Fails silently by design: the in-app review flow must never surface an error. */
fun requestInAppReview(activity: Activity) {
    runCatching {
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { task ->
            if (task.isSuccessful) runCatching { manager.launchReviewFlow(activity, task.result) }
        }
    }
}
