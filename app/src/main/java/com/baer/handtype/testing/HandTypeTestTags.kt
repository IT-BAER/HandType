package com.baer.handtype.testing

/**
 * Semantic test tags for UI elements. Used by Compose [testTag] modifiers and
 * instrumented tests to locate Views without relying on display text.
 */
object HandTypeTestTags {
    const val TEMPLATE_PAGER = "template_pager"
    const val CAMERA_SCAN_BUTTON = "camera_scan_button"
    const val CAMERA_WRITE_BUTTON = "camera_write_button"
    const val CAMERA_PRINT_BUTTON = "camera_print_button"

    const val STYLUS_CANVAS = "stylus_canvas"
    const val STYLUS_PROGRESS = "stylus_progress"
    const val STYLUS_HINT = "stylus_hint"
    const val STYLUS_UNDO = "stylus_undo"
    const val STYLUS_CLEAR = "stylus_clear"
    const val STYLUS_BACK = "stylus_back"
    const val STYLUS_NEXT = "stylus_next"
    const val RENDER_MESSAGE_INPUT = "render_message_input"
    const val RENDER_GENERATE_BUTTON = "render_generate_button"
    const val RENDER_RESULT_IMAGE = "render_result_image"
    const val RESULT_TEXT_SIZE_SLIDER = "result_text_size_slider"

    const val RENAME_HANDWRITING_INPUT = "rename_handwriting_input"

    const val PREMIUM_PAYWALL_SHEET = "premium_paywall_sheet"
    const val PREMIUM_BUY_BUTTON = "premium_buy_button"
    const val PREMIUM_RESTORE_BUTTON = "premium_restore_button"

    const val SETTINGS_SHARE_APP = "settings_share_app"
    const val SETTINGS_RATE_APP = "settings_rate_app"

    const val ONBOARDING_SHEET = "onboarding_sheet"
    const val ONBOARDING_NEXT = "onboarding_next"
    const val ONBOARDING_SKIP = "onboarding_skip"

    const val HELP_SHOW_INTRO = "help_show_intro"

    fun templateDeleteButton(templateId: String): String = "template_delete_$templateId"
    fun backgroundPresetCard(id: String): String = "bg_preset_$id"

    /** Writing cell for sheet character [index] (absolute position in the sheet, not on the page). */
    fun stylusCell(index: Int): String = "stylus_cell_$index"
}
