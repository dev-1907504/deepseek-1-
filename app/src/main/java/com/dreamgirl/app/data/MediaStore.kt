package com.dreamgirl.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 附件 / 表情包 / 自定义头像的本地存储。
 *
 * 全部落在 App 私有目录里 —— 不写相册、不写外部存储、不需要任何存储权限。
 * 数据库里只存**相对文件名**，换设备/清缓存不会留下悬空绝对路径。
 */
object MediaStore {

    private const val DIR_ATTACH = "attachments"
    private const val DIR_STICKER = "stickers"
    private const val DIR_AVATAR = "avatar"

    private fun root(context: Context) = context.filesDir

    fun attachmentsDir(context: Context) = File(root(context), DIR_ATTACH).apply { mkdirs() }
    fun stickersDir(context: Context) = File(root(context), DIR_STICKER).apply { mkdirs() }
    fun avatarDir(context: Context) = File(root(context), DIR_AVATAR).apply { mkdirs() }

    /** 把外部 URI 复制进私有目录，返回相对文件名（失败返回 null）。 */
    suspend fun import(context: Context, uri: Uri, dir: File, fallbackExt: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = queryName(context, uri)
                val ext = name?.substringAfterLast('.', "")?.takeIf { it.length in 1..6 }
                    ?: fallbackExt
                val target = File(dir, "${UUID.randomUUID()}.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                target.name
            }.getOrNull()
        }

    suspend fun importAttachment(context: Context, uri: Uri): String? =
        import(context, uri, attachmentsDir(context), "bin")

    suspend fun importSticker(context: Context, uri: Uri): String? =
        import(context, uri, stickersDir(context), "png")

    suspend fun importAvatar(context: Context, uri: Uri): String? =
        import(context, uri, avatarDir(context), "png")

    /** 相对文件名 → 真实文件。 */
    fun file(context: Context, dir: File, name: String?): File? {
        if (name.isNullOrBlank()) return null
        val f = File(dir, File(name).name) // 防目录穿越
        return f.takeIf { it.exists() }
    }

    fun attachmentFile(context: Context, name: String?) = file(context, attachmentsDir(context), name)
    fun stickerFile(context: Context, name: String?) = file(context, stickersDir(context), name)
    fun avatarFile(context: Context, name: String?) = file(context, avatarDir(context), name)

    fun listStickers(context: Context): List<File> =
        stickersDir(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun deleteSticker(context: Context, name: String) {
        runCatching { File(stickersDir(context), File(name).name).delete() }
    }

    /** 展示用：附件的人类可读大小。 */
    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    /** 取外部 URI 的原始显示名（给"发来一个文件：xxx"用）。 */
    fun displayName(context: Context, uri: Uri): String =
        queryName(context, uri) ?: "未命名"

    /** 从 content URI 里问出原始文件名。 */
    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    /** 猜 mime：给"给她看的那句话"和气泡渲染用。 */
    fun guessKind(mime: String?): String = when {
        mime == null -> MessageEntity.KIND_FILE
        mime.startsWith("image/") -> MessageEntity.KIND_IMAGE
        mime.startsWith("video/") -> MessageEntity.KIND_VIDEO
        else -> MessageEntity.KIND_FILE
    }
}
