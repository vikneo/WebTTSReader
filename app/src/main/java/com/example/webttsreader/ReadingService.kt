package com.example.webttsreader

import android.R
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

        // Команды для сервиса
        const val ACTION_START_READING = "START_READING"
        const val ACTION_STOP_READING = "STOP_READING"
        const val ACTION_PAUSE_READING = "PAUSE_READING"
        const val ACTION_RESUME_READING = "RESUME_READING"
        const val ACTION_UPDATE_URL = "UPDATE_URL"
        const val EXTRA_TEXT = "TEXT"
        const val EXTRA_URL = "URL"
    }

    private lateinit var tts: TextToSpeech
    private var isReading = false
    private var isPaused = false
    private var isTtsReady = false  // ← НОВАЯ ПЕРЕМЕННАЯ
    private var pendingText = ""    // ← НОВАЯ ПЕРЕМЕННАЯ
    private var currentText = ""
    private var currentChunks = mutableListOf<String>()
    private var currentChunkIndex = 0
    // Добавляем менеджер прогресса
    private lateinit var progressManager: ProgressManager
    private var currentUrl: String = ""

    override fun onCreate() {
        super.onCreate()

        progressManager = ProgressManager(this)
        createNotificationChannel()
        initTTS()
        startForeground(NOTIFICATION_ID, createNotification("Готов к чтению"))
    }

    // Добавляем метод для обновления URL
    fun updateCurrentUrl(url: String) {
        currentUrl = url
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

                // Сохраняем текст для чтения после инициализации
                pendingText = text

                // Если TTS уже готов, начинаем чтение
                if (isTtsReady) {
                    startReading(text)
                } else {
                    Log.d(TAG, "⏳ Ожидаем инициализацию TTS...")
                    Toast.makeText(this, "⏳ Подготовка голосового движка...", Toast.LENGTH_SHORT).show()
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
            "UPDATE_URL" -> {
                val url = intent.getStringExtra("URL")
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
                        startReading(pendingText)
                        pendingText = ""
                    }
                }
            } else {
                Log.e(TAG, "❌ Ошибка инициализации TTS: $status")
                stopSelf()
            }
        }
    }

    private fun startReading(text: String) {
        // Проверяем, что TTS готов
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
        currentChunkIndex = 0
        currentChunks = splitTextIntoChunks(text)

        Log.d(TAG, "📊 Текст разбит на ${currentChunks.size} частей")
        updateNotification("Начинаю чтение...")
        isReading = true
        isPaused = false

        // Сохраняем прогресс (если есть URL)
        if (currentUrl.isNotEmpty()) {
            progressManager.saveProgress(currentUrl, 0, text)
        }

        speakNextChunk()
    }

    private fun speakNextChunk() {
        if (currentChunkIndex < currentChunks.size && !isPaused) {
            val chunk = currentChunks[currentChunkIndex]
            Log.d(TAG, "Читаю часть ${currentChunkIndex + 1}/${currentChunks.size}")

            // Сохраняем прогресс каждые 5 частей
            if (currentChunkIndex % 5 == 0 && currentUrl.isNotEmpty()) {
                val progress = (currentChunkIndex.toFloat() / currentChunks.size * 100).toInt()
                progressManager.saveProgress(currentUrl, progress, currentText)
            }

            tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, "chunk_$currentChunkIndex")
            updateNotification("Читаю... ${currentChunkIndex + 1}/${currentChunks.size}")
        }
    }

    private fun splitTextIntoChunks(text: String): MutableList<String> {
        val chunks = mutableListOf<String>()
        val maxChunkSize = 3000

        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxChunkSize, text.length)
            // Ищем последнюю точку или пробел
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
            Log.d(TAG, "Чтение приостановлено")
        }
    }

    private fun resumeReading() {
        if (isReading && isPaused) {
            isPaused = false
            updateNotification("Читаю...")
            speakNextChunk()
            Log.d(TAG, "Чтение возобновлено")
        }
    }

    private fun stopReading() {
        isReading = false
        isPaused = false
        tts.stop()

        // Сохраняем финальный прогресс
        if (currentUrl.isNotEmpty()) {
            val progress = (currentChunkIndex.toFloat() / currentChunks.size * 100).toInt()
            progressManager.saveProgress(currentUrl, progress, currentText)
        }

        updateNotification("Чтение остановлено")
        Log.d(TAG, "Чтение остановлено")

        // Останавливаем сервис через 2 секунды
        android.os.Handler(mainLooper).postDelayed({
            stopSelf()
        }, 2000)
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
        Log.d(TAG, "Сервис уничтожен")
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        // Intent для открытия приложения при нажатии на уведомление
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Кнопки управления
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
            .setSmallIcon(R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_media_pause, if (isPaused) "▶" else "⏸", pausePendingIntent)
            .addAction(R.drawable.ic_media_rew, "⏹", stopPendingIntent)
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