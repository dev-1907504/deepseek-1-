package com.dreamgirl.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API Key 只存在本机、只存在加密容器里，绝不打进 APK、绝不写日志（方案 §5）。
 *
 * 用 EncryptedSharedPreferences（AES256-GCM，主密钥在 Android Keystore 里）。
 * 万一设备上 Keystore 异常导致解密失败，会退回一个「只存内存」的临时容器：
 * 宁可让用户重新输一次 Key，也不把明文落到磁盘上。
 */
class ApiKeyStore(context: Context) : ApiKeyProvider {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            android.util.Log.w("ApiKeyStore", "加密存储不可用，本次运行只保留在内存里", t)
            null
        }
    }

    /** 加密存储不可用时的降级容器（进程内）。 */
    private val memoryFallback = MemoryPrefs()

    private fun store(): SharedPreferences = prefs ?: memoryFallback

    override var apiKey: String?
        get() = store().getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
        set(value) {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) store().edit().remove(KEY_API_KEY).apply()
            else store().edit().putString(KEY_API_KEY, v).apply()
        }

    override val hasKey: Boolean get() = !apiKey.isNullOrEmpty()

    fun clear() {
        apiKey = null
    }

    /** 只用于 UI 展示：sk-abc…wxyz。 */
    fun masked(): String? = apiKey?.let { k ->
        if (k.length <= 10) "••••••" else "${k.take(6)}…${k.takeLast(4)}"
    }

    private class MemoryPrefs : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?) = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String?) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            override fun putString(k: String, v: String?) = apply { staged[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { staged[k] = v }
            override fun putInt(k: String, v: Int) = apply { staged[k] = v }
            override fun putLong(k: String, v: Long) = apply { staged[k] = v }
            override fun putFloat(k: String, v: Float) = apply { staged[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { staged[k] = v }
            override fun remove(k: String) = apply { removed += k }
            override fun clear() = apply { removed += map.keys }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                removed.forEach { map.remove(it) }
                map.putAll(staged)
            }
        }
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    companion object {
        private const val FILE_NAME = "dreamgirl_secrets"
        private const val KEY_API_KEY = "deepseek_api_key"
    }
}
