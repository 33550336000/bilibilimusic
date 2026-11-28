package com.tilixibiesi.data

import com.tilixibiesi.util.AtomicFileWriter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tilixibiesi.util.AppLog

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

object BiliProfileStore {

    private const val TAG = "BiliProfileStore"

    private const val COOKIE_DIR_REL = "system/axeron/long/Android/Appdata/cookie"
    private const val PROFILE_NAME = "bilibili_profile.json"
    private const val AVATAR_NAME = "bilibili_avatar.jpg"

    private const val KEY_MID = "mid"
    private const val KEY_UNAME = "uname"
    private const val KEY_FACE = "face"
    private const val KEY_LOGIN_AT = "login_at"

    data class Profile(
        val mid: Long,
        val uname: String,
        val face: String,
        val loginAt: Long
    ) {

        val isComplete: Boolean
            get() = mid > 0L && uname.isNotEmpty() && face.isNotEmpty()
    }

    private fun cookieDir(): File =
        BiliCookieStore.cookieFile().parentFile ?: StoragePaths.resolveWrite(COOKIE_DIR_REL)

    private fun profileFile(): File = File(cookieDir(), PROFILE_NAME)

    private fun avatarFile(): File = File(cookieDir(), AVATAR_NAME)

    fun load(): Profile? {
        val file = profileFile()
        if (!file.isFile || file.length() <= 0L) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        return Profile(
            json.optLong(KEY_MID, 0L),
            json.optString(KEY_UNAME, ""),
            json.optString(KEY_FACE, ""),
            json.optLong(KEY_LOGIN_AT, 0L)
        )
    }

    fun saveLoginTime(time: Long) {
        val old = load()
        write(
            Profile(
                old?.mid ?: 0L,
                old?.uname ?: "",
                old?.face ?: "",
                time
            )
        )
    }

    fun saveIdentity(mid: Long, uname: String, face: String) {
        val old = load()
        write(Profile(mid, uname, face, old?.loginAt ?: 0L))
    }

    private fun write(profile: Profile) {
        val json = JSONObject()
            .put(KEY_MID, profile.mid)
            .put(KEY_UNAME, profile.uname)
            .put(KEY_FACE, profile.face)
            .put(KEY_LOGIN_AT, profile.loginAt)
        if (!AtomicFileWriter.writeText(profileFile(), json.toString())) {
            AppLog.d(TAG, "账号信息写入失败: ${profileFile().absolutePath}")
        }
    }

    fun clear() {
        runCatching { profileFile().delete() }
        runCatching { avatarFile().delete() }
    }

    fun loginTime(): Long {
        val saved = load()?.loginAt ?: 0L
        if (saved > 0L) return saved
        val cookie = BiliCookieStore.cookieFile()
        return if (cookie.isFile && cookie.length() > 0L) cookie.lastModified() else 0L
    }

    fun saveAvatar(bitmap: Bitmap): Boolean {
        val bytes = runCatching {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                out.toByteArray()
            }
        }.getOrNull() ?: return false
        if (bytes.isEmpty()) return false
        return AtomicFileWriter.write(avatarFile()) { out -> out.write(bytes) }
    }

    fun loadAvatar(): Bitmap? {
        val file = avatarFile()
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }
}
