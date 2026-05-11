package com.baer.handtype.testing

/**
 * Semantic test tags for UI elements. Used by Compose [testTag] modifiers and
 * instrumented tests to locate Views without relying on display text.
 */
object HandTypeTestTags {
    const val TEMPLATE_PAGER = "template_pager"
    const val CAMERA_SCAN_BUTTON = "camera_scan_button"
    const val RENDER_MESSAGE_INPUT = "render_message_input"
    const val RENDER_GENERATE_BUTTON = "render_generate_button"
    const val RENDER_RESULT_IMAGE = "render_result_image"

    fun templateDeleteButton(templateId: String): String = "template_delete_$templateId"
}
