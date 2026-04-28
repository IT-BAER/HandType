package com.baer.handtype.template

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Persists user-captured handwriting samples as renderable templates.
 *
 * Layout on disk (under [Context.getFilesDir]):
 * ```
 * user_templates/
 *   <templateId>/
 *     meta.txt           # one line: display name
 *     glyphs/u<hex>_v0.png
 * ```
 *
 * Glyph file naming matches [BundledTemplateRepository] so the same renderer works.
 */
class UserTemplateRepository(private val context: Context) {

    private val rootDir: File
        get() = File(context.filesDir, ROOT_DIR_NAME).apply { if (!exists()) mkdirs() }

    /** Lists previously persisted user templates, newest first. */
    fun listUserTemplates(): List<TemplateDescriptor> {
        val dir = rootDir
        if (!dir.isDirectory) return emptyList()
        val children = dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        return children
            .sortedByDescending { it.lastModified() }
            .mapNotNull { folder ->
                val meta = File(folder, META_FILE)
                val displayName = if (meta.exists()) meta.readText().trim().ifBlank { folder.name } else folder.name
                TemplateDescriptor(
                    id = USER_ID_PREFIX + folder.name,
                    displayName = displayName,
                    sampleText = "Your handwriting.",
                    description = "Captured from your handwriting sheet.",
                    premium = true,
                )
            }
    }

    /** True when [templateId] is a user template id (always namespaced via [USER_ID_PREFIX]). */
    fun isUserTemplateId(templateId: String): Boolean = templateId.startsWith(USER_ID_PREFIX)

    /**
     * Saves [glyphMap] to disk and returns the namespaced template id (e.g. `user_1714233600000`).
     *
     * @param displayName Human-readable label shown in the chooser.
     * @param glyphMap Char → upright glyph crop. One variant per character is persisted.
     */
    suspend fun saveTemplate(
        displayName: String,
        glyphMap: Map<Char, Bitmap>,
    ): String = withContext(Dispatchers.IO) {
        require(glyphMap.isNotEmpty()) { "Cannot save an empty user template." }

        val folderName = "u${System.currentTimeMillis()}"
        val templateDir = File(rootDir, folderName).apply { mkdirs() }
        val glyphDir = File(templateDir, GLYPHS_DIR).apply { mkdirs() }

        glyphMap.forEach { (character, bitmap) ->
            val file = File(glyphDir, character.toVariantFileName(0))
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    Log.w(TAG, "Failed to compress glyph for character '$character'")
                }
            }
        }

        File(templateDir, META_FILE).writeText(displayName)
        Log.i(TAG, "Saved user template '$folderName' with ${glyphMap.size} glyphs at $templateDir")

        USER_ID_PREFIX + folderName
    }

    /** Permanently deletes a previously saved user template. Returns true if the directory was removed. */
    fun deleteTemplate(templateId: String): Boolean {
        require(isUserTemplateId(templateId)) { "Not a user template id: $templateId" }
        val folderName = templateId.removePrefix(USER_ID_PREFIX)
        val templateDir = File(rootDir, folderName)
        if (!templateDir.exists()) return false
        return templateDir.deleteRecursively()
    }

    /** Loads a previously saved user template into a renderable [HandwritingTemplate]. */
    suspend fun loadTemplate(templateId: String): HandwritingTemplate = withContext(Dispatchers.IO) {
        require(isUserTemplateId(templateId)) { "Not a user template id: $templateId" }
        val folderName = templateId.removePrefix(USER_ID_PREFIX)
        val templateDir = File(rootDir, folderName)
        check(templateDir.isDirectory) { "User template directory missing: $templateDir" }

        val glyphDir = File(templateDir, GLYPHS_DIR)
        val metaFile = File(templateDir, META_FILE)
        val displayName = if (metaFile.exists()) metaFile.readText().trim().ifBlank { folderName } else folderName

        val glyphs = linkedMapOf<Char, List<Bitmap>>()
        glyphDir.listFiles()?.forEach { file ->
            val character = file.name.charFromVariantFileName() ?: return@forEach
            val bitmap = file.inputStream().use(BitmapFactory::decodeStream) ?: return@forEach
            glyphs[character] = listOf(bitmap)
        }

        check(glyphs.isNotEmpty()) { "User template '$templateId' has no glyph assets." }

        HandwritingTemplate(
            descriptor = TemplateDescriptor(
                id = templateId,
                displayName = displayName,
                sampleText = "Your handwriting.",
                description = "Captured from your handwriting sheet.",
                premium = true,
            ),
            glyphs = glyphs,
        )
    }

    companion object {
        private const val TAG = "UserTemplateRepo"
        const val USER_ID_PREFIX = "user_"
        private const val ROOT_DIR_NAME = "user_templates"
        private const val GLYPHS_DIR = "glyphs"
        private const val META_FILE = "meta.txt"
    }
}

private fun Char.toVariantFileName(variant: Int): String = "u%04X_v%d.png".format(code, variant)

private fun String.charFromVariantFileName(): Char? {
    // Expected format: u<HEX>_v<int>.png
    val match = USER_VARIANT_REGEX.matchEntire(this) ?: return null
    val codePoint = match.groupValues[1].toIntOrNull(16) ?: return null
    return codePoint.toChar()
}

private val USER_VARIANT_REGEX = Regex("u([0-9A-Fa-f]+)_v\\d+\\.png")
