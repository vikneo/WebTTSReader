package com.example.webttsreader

import android.content.Context
import android.content.SharedPreferences

class ProgressManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SAVED_URL = "saved_url"
        private const val KEY_SCROLL_POSITION = "scroll_position"
        private const val KEY_SAVED_TEXT = "saved_text"
        private const val KEY_TIMESTAMP = "timestamp"
    }

    // Сохраняем прогресс
    fun saveProgress(url: String, scrollPosition: Int, text: String = "") {
        prefs.edit().apply {
            putString(KEY_SAVED_URL, url)
            putInt(KEY_SCROLL_POSITION, scrollPosition)
            putString(KEY_SAVED_TEXT, text)
            putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            apply()
        }
        android.util.Log.d("ProgressManager", "✅ Прогресс сохранен: $url, позиция: $scrollPosition")
    }

    // Получаем сохраненный прогресс
    fun getProgress(): ProgressData? {
        val url = prefs.getString(KEY_SAVED_URL, null)
        val position = prefs.getInt(KEY_SCROLL_POSITION, -1)
        val text = prefs.getString(KEY_SAVED_TEXT, "")
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0)

        return if (url != null && position >= 0) {
            ProgressData(url, position, text ?: "", timestamp)
        } else {
            null
        }
    }

    // Очищаем сохраненный прогресс (после завершения чтения)
    fun clearProgress() {
        prefs.edit().clear().apply()
        android.util.Log.d("ProgressManager", "🗑 Прогресс очищен")
    }

    // Проверяем, есть ли сохраненный прогресс
    fun hasProgress(): Boolean {
        return prefs.getString(KEY_SAVED_URL, null) != null
    }

    // Форматируем время для отображения
    fun getFormattedTime(timestamp: Long): String {
        val date = java.util.Date(timestamp)
        val format = java.text.SimpleDateFormat("HH:mm dd.MM.yyyy", java.util.Locale.getDefault())
        return format.format(date)
    }

    data class ProgressData(
        val url: String,
        val scrollPosition: Int,
        val text: String,
        val timestamp: Long
    )
}