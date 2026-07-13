package com.example.webttsreader

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.SearchView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var progressManager: ProgressManager

    private lateinit var webView: WebView
    private lateinit var searchView: SearchView
    private var isReading = false

    inner class JavaScriptInterface {
        @JavascriptInterface
        @Suppress("unused")
        fun speak(text: String) {
            Log.d("WebTTS", "🎯 Текст получен, длина: ${text.length}")
            // Запускаем сервис для фонового чтения
            startReadingService(text)
        }

        @JavascriptInterface
        @Suppress("unused")
        fun log(message: String) {
            Log.d("WebTTS", "📋 JS Log: $message")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализируем менеджер прогресса
        progressManager = ProgressManager(this)

        // Запрашиваем разрешение на уведомления для Android 13+
        requestNotificationPermission()

        initViews()
        initWebView()
        initSearch()
        setupButtons()

        // Проверяем сохраненный прогресс
        checkSavedProgress()
    }

    private fun checkSavedProgress() {
        val progress = progressManager.getProgress()
        if (progress != null) {
            val time = progressManager.getFormattedTime(progress.timestamp)
            Log.d("MainActivity", "📚 Найден сохраненный прогресс: ${progress.url}")
            Log.d("MainActivity", "📊 Позиция: ${progress.scrollPosition}%")
            Log.d("MainActivity", "🕐 Сохранено: $time")

            // Показываем диалог с вопросом
            AlertDialog.Builder(this)
                .setTitle("📚 Продолжить чтение?")
                .setMessage("Вы читали: ${progress.url}\nПрогресс: ${progress.scrollPosition}%\nСохранено: $time")
                .setPositiveButton("Продолжить") { _, _ ->
                    // Загружаем сохраненную страницу
                    webView.loadUrl(progress.url)

                    // Восстанавливаем позицию прокрутки через JavaScript
                    webView.evaluateJavascript(
                        "window.scrollTo(0, document.body.scrollHeight * ${progress.scrollPosition} / 100);",
                        null
                    )

                    // Если есть сохраненный текст, читаем его
                    if (progress.text.isNotEmpty()) {
                        Toast.makeText(this, "📖 Загружен текст для чтения", Toast.LENGTH_LONG).show()
                        startReadingService(progress.text)
                    }
                }
                .setNegativeButton("Начать заново") { _, _ ->
                    progressManager.clearProgress()
                    webView.loadUrl("https://author.today")
                }
                .setNeutralButton("Позже") { _, _ ->
                    // Ничего не делаем
                }
                .show()
        } else {
            Log.d("MainActivity", "📚 Сохраненный прогресс не найден")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "✅ Разрешение на уведомления получено")
            } else {
                Log.d("MainActivity", "❌ Разрешение на уведомления отклонено")
                Toast.makeText(this, "Для фонового чтения нужно разрешение на уведомления", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        searchView = findViewById(R.id.searchView)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Web TTS Reader"
    }

    private fun initWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        webView.addJavascriptInterface(JavaScriptInterface(), "AndroidTTS")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectJavaScript()

                // Обновляем URL в сервисе при загрузке новой страницы
                url?.let {
                    val intent = Intent(this@MainActivity, ReadingService::class.java).apply {
                        action = ReadingService.ACTION_UPDATE_URL
                        putExtra(ReadingService.EXTRA_URL, it)
                    }
                    startService(intent)
                }
            }
        }

        webView.loadUrl("https://author.today")
    }

    private fun injectJavaScript() {
        val jsCode = """
            (function() {
                console.log('🔍 JavaScript запущен');
                
                function getPageText() {
                    console.log('📖 Извлечение текста...');
                    
                    try {
                        const textContainer = document.querySelector('#text-container');
                        
                        if (!textContainer) {
                            console.log('❌ #text-container не найден');
                            AndroidTTS.log('Контейнер не найден');
                            return false;
                        }
                        
                        console.log('✅ Найден #text-container');
                        
                        let text = textContainer.innerText || textContainer.textContent || '';
                        text = text.replace(/\s+/g, ' ').trim();
                        
                        console.log('📊 Найдено символов: ' + text.length);
                        
                        if (text.length > 100) {
                            console.log('✅ Отправляю в TTS');
                            AndroidTTS.speak(text);
                            return true;
                        } else {
                            console.log('❌ Текст слишком короткий: ' + text.length);
                            AndroidTTS.log('Текст короткий: ' + text.length);
                            return false;
                        }
                    } catch (e) {
                        console.log('❌ Ошибка: ' + e.message);
                        AndroidTTS.log('Ошибка: ' + e.message);
                        return false;
                    }
                }
                
                window.readPage = getPageText;
                console.log('✅ Функция readPage зарегистрирована');
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }

    private fun initSearch() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (!query.isNullOrBlank()) {
                    val searchUrl = "https://www.google.com/search?q=${query.replace(' ', '+')}"
                    webView.loadUrl(searchUrl)
                    searchView.clearFocus()
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean = false
        })
    }

    private fun setupButtons() {
        // Кнопка "Читать страницу" - теперь с проверкой прогресса
        findViewById<Button>(R.id.btnReadPage).setOnClickListener {
            // Проверяем сохраненный прогресс
            val progress = progressManager.getProgress()
            if (progress != null) {
                // Если есть прогресс, спрашиваем пользователя
                AlertDialog.Builder(this)
                    .setTitle("📚 Продолжить чтение?")
                    .setMessage("Вы читали: ${progress.url}\nПрогресс: ${progress.scrollPosition}%\nСохранено: ${progressManager.getFormattedTime(progress.timestamp)}")
                    .setPositiveButton("Продолжить") { _, _ ->
                        // Загружаем сохраненную страницу
                        webView.loadUrl(progress.url)
                        // Восстанавливаем позицию прокрутки
                        webView.evaluateJavascript(
                            "window.scrollTo(0, document.body.scrollHeight * ${progress.scrollPosition} / 100);",
                            null
                        )
                        // Если есть сохраненный текст, читаем его
                        if (progress.text.isNotEmpty()) {
                            startReadingService(progress.text)
                        }
                    }
                    .setNegativeButton("Новая страница") { _, _ ->
                        // Очищаем прогресс и читаем текущую страницу
                        progressManager.clearProgress()
                        readCurrentPage()
                    }
                    .setNeutralButton("Отмена", null)
                    .show()
            } else {
                // Если прогресса нет, просто читаем текущую страницу
                readCurrentPage()
            }
        }

        findViewById<Button>(R.id.btnStopReading).setOnClickListener {
            stopReading()
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        findViewById<Button>(R.id.btnForward).setOnClickListener {
            if (webView.canGoForward()) {
                webView.goForward()
            }
        }
    }

    private fun readCurrentPage() {
        if (isReading) {
            Toast.makeText(this, "Уже читаю", Toast.LENGTH_SHORT).show()
            return
        }

        // Получаем текущий URL для сохранения
        webView.evaluateJavascript(
            "if (typeof readPage === 'function') { readPage(); } else { AndroidTTS.log('readPage не найдена'); }",
            null
        )

        // Сохраняем URL
        webView.evaluateJavascript(
            "window.location.href;",
            { url ->
                val cleanUrl = url?.trim()?.removeSurrounding("\"") ?: ""
                if (cleanUrl.isNotEmpty()) {
                    // Передаем URL в сервис
                    val intent = Intent(this, ReadingService::class.java).apply {
                        action = "UPDATE_URL"
                        putExtra("URL", cleanUrl)
                    }
                    startService(intent)
                }
            }
        )
    }

    private fun startReadingService(text: String) {
//        val intent = Intent(this, ReadingService::class.java).apply {
//            action = ReadingService.ACTION_START_READING
//            putExtra(ReadingService.EXTRA_TEXT, text)
//        }
//        startService(intent)
//        isReading = true
//        Toast.makeText(this, "📖 Чтение начато в фоне", Toast.LENGTH_SHORT).show()

        try {
            val intent = Intent(this, ReadingService::class.java).apply {
                action = ReadingService.ACTION_START_READING
                putExtra(ReadingService.EXTRA_TEXT, text)
            }
            startService(intent)
            isReading = true

            // Сохраняем прогресс
            webView.evaluateJavascript(
                "window.location.href;",
                { url ->
                    val cleanUrl = url?.trim()?.removeSurrounding("\"") ?: ""
                    if (cleanUrl.isNotEmpty()) {
                        progressManager.saveProgress(cleanUrl, 0, text)
                    }
                }
            )

            Toast.makeText(this, "📖 Чтение начато в фоне (${text.length} символов)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("WebTTS", "❌ Ошибка запуска сервиса", e)
            Toast.makeText(this, "❌ Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopReading() {
        val intent = Intent(this, ReadingService::class.java).apply {
            action = ReadingService.ACTION_STOP_READING
        }
        startService(intent)
        isReading = false
        Toast.makeText(this, "⏹ Чтение остановлено", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Останавливаем сервис при закрытии приложения
        val intent = Intent(this, ReadingService::class.java)
        stopService(intent)
    }
}