package com.example.webttsreader

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.*

class ReadingService : Service() {

    companion object {
        private const val CHANNEL_ID = "reading_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "ReadingService"

        const val ACTION_START_READING = "START_READING"
        const val ACTION_STOP_READING = "STOP_READING"
        const val ACTION_PAUSE_READING = "PAUSE_READING"
        const val ACTION_RESUME_READING = "RESUME_READING"
        const val ACTION_UPDATE_URL = "UPDATE_URL"
        const val ACTION_RESUME_FROM_PROGRESS = "RESUME_FROM_PROGRESS"  // ← НОВОЕ
        const val EXTRA_TEXT = "TEXT"
        const val EXTRA_URL = "URL"
        const val EXTRA_CHUNK_INDEX = "CHUNK_INDEX"  // ← НОВОЕ
    }

    private lateinit var tts: TextToSpeech
    private lateinit var progressManager: ProgressManager
    private var isReading = false
    private var isPaused = false
    private var isTtsReady = false
    private var pendingText = ""
    private var currentText = ""
    private var currentChunks = mutableListOf<String>()
    private var currentChunkIndex = 0
    private var currentUrl: String = ""

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🔄 Сервис создан")

        progressManager = ProgressManager(this)
        createNotificationChannel()
        initTTS()
        startForeground(NOTIFICATION_ID, createNotification("Готов к чтению"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📥 onStartCommand вызван")

        if (intent == null) {
            Log.e(TAG, "❌ Intent пустой")
            return START_STICKY
        }

        val action = intent.action
        Log.d(TAG, "📥 Action: $action")

        when (action) {
            ACTION_START_READING -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                Log.d(TAG, "📥 Получен текст, длина: ${text?.length ?: 0}")

                if (text.isNullOrEmpty()) {
                    Log.e(TAG, "❌ Текст пустой")
                    return START_STICKY
                }

                pendingText = text

                if (isTtsReady) {
                    startReading(text, 0)
                } else {
                    Log.d(TAG, "⏳ Ожидаем инициализацию TTS...")
                    Toast.makeText(this, "⏳ Подготовка голосового движка...", Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_RESUME_FROM_PROGRESS -> {  // ← НОВОЕ
                val chunkIndex = intent.getIntExtra(EXTRA_CHUNK_INDEX, 0)
                Log.d(TAG, "📥 Возобновление с части $chunkIndex")

                if (pendingText.isNotEmpty() && isTtsReady) {
                    startReading(pendingText, chunkIndex)
                    pendingText = ""
                }
            }
            ACTION_STOP_READING -> {
                Log.d(TAG, "⏹ Команда остановки")
                stopReading()
            }
            ACTION_PAUSE_READING -> {
                Log.d(TAG, "⏸ Команда паузы")
                pauseReading()
            }
            ACTION_RESUME_READING -> {
                Log.d(TAG, "▶️ Команда возобновления")
                resumeReading()
            }
            ACTION_UPDATE_URL -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrEmpty()) {
                    currentUrl = url
                    Log.d(TAG, "🌐 URL обновлен: $currentUrl")
                }
            }
            else -> {
                Log.e(TAG, "❌ Неизвестное действие: $action")
            }
        }

        return START_STICKY
    }

    private fun initTTS() {
        Log.d(TAG, "🔄 Инициализация TTS...")

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale("ru"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "❌ Русский язык не поддерживается")
                    stopSelf()
                } else {
                    Log.d(TAG, "✅ TTS инициализирован")
                    isTtsReady = true

                    tts.setSpeechRate(1.0f)
                    tts.setPitch(1.0f)

                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            updateNotification("Читаю...")
                            isReading = true
                            Log.d(TAG, "🎤 Начало: $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            Log.d(TAG, "✅ Завершено: $utteranceId")
                            currentChunkIndex++
                            if (currentChunkIndex < currentChunks.size) {
                                speakNextChunk()
                            } else {
                                isReading = false
                                updateNotification("✅ Чтение завершено")
                                Log.d(TAG, "🏁 Чтение завершено")

                                // Сохраняем финальный прогресс
                                if (currentUrl.isNotEmpty()) {
                                    progressManager.saveProgress(
                                        currentUrl,
                                        100,
                                        currentText,
                                        currentChunkIndex,
                                        currentChunks.size
                                    )
                                }

                                stopSelf()
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            Log.e(TAG, "❌ Ошибка: $utteranceId")
                            isReading = false
                            if (currentChunkIndex < currentChunks.size) {
                                speakNextChunk()
                            } else {
                                stopSelf()
                            }
                        }
                    })

                    // Если есть текст, ожидающий чтения, начинаем
                    if (pendingText.isNotEmpty()) {
                        Log.d(TAG, "🚀 Начинаем отложенное чтение, длина: ${pendingText.length}")
                        startReading(pendingText, 0)
                        pendingText = ""
                    }
                }
            } else {
                Log.e(TAG, "❌ Ошибка инициализации TTS: $status")
                stopSelf()
            }
        }
    }

    private fun startReading(text: String, startChunkIndex: Int = 0) {
        if (!isTtsReady) {
            Log.e(TAG, "❌ TTS не готов, сохраняем текст для отложенного чтения")
            pendingText = text
            return
        }

        if (text.isEmpty() || text.length < 50) {
            Log.e(TAG, "❌ Текст слишком короткий")
            return
        }

        currentText = text
        currentChunks = splitTextIntoChunks(text)

        // Устанавливаем начальный индекс
        currentChunkIndex = minOf(startChunkIndex, currentChunks.size - 1)
        if (currentChunkIndex < 0) currentChunkIndex = 0

        Log.d(TAG, "📊 Текст разбит на ${currentChunks.size} частей, начинаем с ${currentChunkIndex + 1}")
        updateNotification("Начинаю чтение...")
        isReading = true
        isPaused = false

        if (currentUrl.isNotEmpty()) {
            val progress = (currentChunkIndex.toFloat() / currentChunks.size * 100).toInt()
            progressManager.saveProgress(currentUrl, progress, text, currentChunkIndex, currentChunks.size)
        }

        speakNextChunk()
    }

    private fun speakNextChunk() {
        if (currentChunkIndex < currentChunks.size && !isPaused) {
            val chunk = currentChunks[currentChunkIndex]
            Log.d(TAG, "📢 Читаю часть ${currentChunkIndex + 1}/${currentChunks.size}")

            // Сохраняем прогресс каждые 3 части
            if (currentChunkIndex % 3 == 0 && currentUrl.isNotEmpty()) {
                val progress = (currentChunkIndex.toFloat() / currentChunks.size * 100).toInt()
                progressManager.saveProgress(currentUrl, progress, currentText, currentChunkIndex, currentChunks.size)
            }

            tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, "chunk_$currentChunkIndex")
            updateNotification("Читаю... ${currentChunkIndex + 1}/${currentChunks.size}")
        }
    }

    private fun splitTextIntoChunks(text: String): MutableList<String> {
        val chunks = mutableListOf<String>()
        val maxChunkSize = 300

        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxChunkSize, text.length)
            if (end < text.length) {
                val lastSpace = text.lastIndexOf(' ', end)
                if (lastSpace > start) {
                    end = lastSpace
                }
            }
            chunks.add(text.substring(start, end).trim())
            start = end
        }
        return chunks
    }

    private fun pauseReading() {
        if (isReading && !isPaused) {
            isPaused = true
            tts.stop()
            updateNotification("⏸ На паузе")
            Log.d(TAG, "⏸ Чтение приостановлено на части ${currentChunkIndex + 1}/${currentChunks.size}")

            // Сохраняем прогресс при паузе
            if (currentUrl.isNotEmpty() && currentChunks.isNotEmpty()) {
                val progress = (currentChunkIndex.toFloat() / currentChunks.size * 100).toInt()
                progressManager.saveProgress(currentUrl, progress, currentText, currentChunkIndex, currentChunks.size)
            }
        }
    }

    private fun resumeReading() {
        if (isReading && isPaused) {
            isPaused = false
            updateNotification("Читаю...")
            speakNextChunk()
            Log.d(TAG, "▶️ Чтение возобновлено с части ${currentChunkIndex + 1}")
        }
    }

    private fun stopReading() {
        isReading = false
        isPaused = false
        tts.stop()

        if (currentUrl.isNotEmpty() && currentChunks.isNotEmpty()) {
            val progress = (currentChunkIndex.toFloat() / currentChunks.size * 100).toInt()
            progressManager.saveProgress(currentUrl, progress, currentText, currentChunkIndex, currentChunks.size)
            Log.d(TAG, "💾 Прогресс сохранен: $progress%, часть ${currentChunkIndex + 1}/${currentChunks.size}")
        }

        updateNotification("⏹ Чтение остановлено")
        Log.d(TAG, "⏹ Чтение остановлено")

        android.os.Handler(mainLooper).postDelayed({
            stopSelf()
        }, 2000)
    }

    override fun onDestroy() {
        Log.d(TAG, "🔄 Сервис уничтожен")
        try {
            tts.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при завершении TTS", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Уведомления (без изменений) ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Чтение книг",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления о процессе чтения"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ReadingService::class.java).apply {
            action = ACTION_STOP_READING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, ReadingService::class.java).apply {
            action = if (isPaused) ACTION_RESUME_READING else ACTION_PAUSE_READING
        }
        val pausePendingIntent = PendingIntent.getService(
            this, 2, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📖 Чтец книг")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, if (isPaused) "▶" else "⏸", pausePendingIntent)
            .addAction(android.R.drawable.ic_media_rew, "⏹", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}