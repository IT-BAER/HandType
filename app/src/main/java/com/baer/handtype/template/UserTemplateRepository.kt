package com.baer.handtype.template

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists user-captured handwriting samples as renderable templates.
 *
 * Layout on disk (under [Context.getFilesDir]):
 * ```
 * user_templates/
 *   <templateId>/
 *     meta.txt           # key/value metadata (legacy: one line display name)
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
            .map { folder -> folder to readMeta(folder) }
            .sortedByDescending { (_, meta) -> meta.createdAt }
            .map { (folder, meta) ->
                meta.toDescriptor(folder.name)
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
        isNew: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        require(glyphMap.isNotEmpty()) { "Cannot save an empty user template." }

        val createdAt = System.currentTimeMillis()
        val folderName = "u$createdAt"
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

        writeMeta(
            templateDir = templateDir,
            meta = UserTemplateMeta(
                displayName = displayName.trim().ifBlank { folderName },
                isNew = isNew,
                createdAt = createdAt,
                glyphCount = glyphMap.size,
            ),
        )
        Log.i(TAG, "Saved user template '$folderName' with ${glyphMap.size} glyphs at $templateDir")

        USER_ID_PREFIX + folderName
    }

    fun renameTemplate(templateId: String, newDisplayName: String) {
        val templateDir = requireTemplateDir(templateId)
        val currentMeta = readMeta(templateDir)
        val trimmedName = newDisplayName.trim()
        require(trimmedName.isNotEmpty()) { "Template name cannot be blank." }
        writeMeta(templateDir, currentMeta.copy(displayName = trimmedName))
    }

    fun markTemplateSeen(templateId: String) {
        val templateDir = requireTemplateDir(templateId)
        val currentMeta = readMeta(templateDir)
        if (!currentMeta.isNew) return
        writeMeta(templateDir, currentMeta.copy(isNew = false))
    }

    fun getTemplateDescriptor(templateId: String): TemplateDescriptor? {
        val templateDir = templateDirectoryOrNull(templateId) ?: return null
        if (!templateDir.isDirectory) return null
        return readMeta(templateDir).toDescriptor(templateDir.name)
    }

    /** Permanently deletes a previously saved user template. Returns true if the directory was removed. */
    fun deleteTemplate(templateId: String): Boolean {
        val templateDir = templateDirectoryOrNull(templateId)
            ?: throw IllegalArgumentException("Not a user template id: $templateId")
        if (!templateDir.exists()) return false
        return templateDir.deleteRecursively()
    }

    /** Loads a previously saved user template into a renderable [HandwritingTemplate]. */
    suspend fun loadTemplate(templateId: String): HandwritingTemplate = withContext(Dispatchers.IO) {
        val templateDir = requireTemplateDir(templateId)
        val folderName = templateDir.name
        check(templateDir.isDirectory) { "User template directory missing: $templateDir" }

        val glyphDir = File(templateDir, GLYPHS_DIR)
        val meta = readMeta(templateDir)

        val glyphs = linkedMapOf<Char, List<Bitmap>>()
        glyphDir.listFiles()?.forEach { file ->
            val character = file.name.charFromVariantFileName() ?: return@forEach
            val bitmap = file.inputStream().use(BitmapFactory::decodeStream) ?: return@forEach
            glyphs[character] = listOf(bitmap)
        }

        check(glyphs.isNotEmpty()) { "User template '$templateId' has no glyph assets." }

        HandwritingTemplate(
            descriptor = meta.toDescriptor(folderName),
            glyphs = glyphs,
        )
    }

    private fun requireTemplateDir(templateId: String): File {
        val templateDir = templateDirectoryOrNull(templateId)
        require(templateDir != null) { "Not a user template id: $templateId" }
        return templateDir
    }

    private fun templateDirectoryOrNull(templateId: String): File? {
        if (!isUserTemplateId(templateId)) return null
        val folderName = templateId.removePrefix(USER_ID_PREFIX)
        return File(rootDir, folderName)
    }

    private fun readMeta(templateDir: File): UserTemplateMeta {
        val fallbackName = templateDir.name
        val fallbackCreatedAt = templateDir.lastModified().takeIf { it > 0L } ?: 0L
        val metaFile = File(templateDir, META_FILE)
        if (!metaFile.exists()) {
            return UserTemplateMeta(
                displayName = fallbackName,
                isNew = false,
                createdAt = fallbackCreatedAt,
            )
        }

        val rawText = metaFile.readText().trim()
        if (rawText.isBlank()) {
            return UserTemplateMeta(
                displayName = fallbackName,
                isNew = false,
                createdAt = fallbackCreatedAt,
            )
        }
        if (!rawText.contains('=')) {
            return UserTemplateMeta(
                displayName = rawText,
                isNew = false,
                createdAt = fallbackCreatedAt,
            )
        }

        var displayName = fallbackName
        var isNew = false
        var createdAt = fallbackCreatedAt
        var glyphCount = 0

        rawText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@forEach
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                when (key) {
                    META_KEY_DISPLAY_NAME -> {
                        if (value.isNotEmpty()) displayName = value
                    }
                    META_KEY_IS_NEW -> {
                        isNew = value.equals("true", ignoreCase = true)
                    }
                    META_KEY_CREATED_AT -> {
                        createdAt = value.toLongOrNull()?.takeIf { it > 0L } ?: createdAt
                    }
                    META_KEY_GLYPH_COUNT -> {
                        glyphCount = value.toIntOrNull()?.takeIf { it >= 0 } ?: glyphCount
                    }
                }
            }

        return UserTemplateMeta(
            displayName = displayName,
            isNew = isNew,
            createdAt = createdAt,
            glyphCount = glyphCount,
        )
    }

    private fun writeMeta(templateDir: File, meta: UserTemplateMeta) {
        File(templateDir, META_FILE).writeText(
            buildString {
                append(META_KEY_DISPLAY_NAME)
                append('=')
                append(meta.displayName)
                append('\n')
                append(META_KEY_IS_NEW)
                append('=')
                append(meta.isNew.toString())
                append('\n')
                append(META_KEY_CREATED_AT)
                append('=')
                append(meta.createdAt)
                append('\n')
                append(META_KEY_GLYPH_COUNT)
                append('=')
                append(meta.glyphCount)
                append('\n')
            },
        )
    }

    companion object {
        private const val TAG = "UserTemplateRepo"
        const val USER_ID_PREFIX = "user_"
        private const val ROOT_DIR_NAME = "user_templates"
        private const val GLYPHS_DIR = "glyphs"
        private const val META_FILE = "meta.txt"
        private const val META_KEY_DISPLAY_NAME = "displayName"
        private const val META_KEY_IS_NEW = "isNew"
        private const val META_KEY_CREATED_AT = "createdAt"
        private const val META_KEY_GLYPH_COUNT = "glyphCount"
    }
}

private data class UserTemplateMeta(
    val displayName: String,
    val isNew: Boolean,
    val createdAt: Long,
    val glyphCount: Int = 0,
) {
    fun toDescriptor(folderName: String): TemplateDescriptor {
        val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            .format(Date(createdAt))
        val description = if (glyphCount > 0) {
            "Captured $dateStr · $glyphCount characters"
        } else {
            "Captured $dateStr"
        }
        return TemplateDescriptor(
            id = UserTemplateRepository.USER_ID_PREFIX + folderName,
            displayName = displayName.ifBlank { folderName },
            sampleText = "Your handwriting.",
            description = description,
            premium = true,
            isNew = isNew,
        )
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
