package com.androbs.lens.a11y

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.androbs.lens.Prefs
import com.androbs.lens.R
import com.androbs.lens.ocr.OcrTranslator
import com.androbs.lens.ui.TranslationLayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * High-power Screen Translator & Google Lens bridge.
 * Features:
 * 1. [ 🔍 Google Lens ] -> 1-Tap native Google Lens launch with the captured screenshot.
 * 2. [ 🔄 Traduci ] -> In-place large, high-contrast overlay with Google Neural Translation.
 * 3. [ 📖 Leggi ] -> Full-screen modal reader showing all translated text in large 20sp font.
 */
class LensAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: LensAccessibilityService? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ocr = OcrTranslator()

    private var wm: WindowManager? = null
    private var overlay: TranslationLayerView? = null
    private var controller: View? = null
    private var readerModal: View? = null

    @Volatile
    private var lockOn = false
    @Volatile
    private var paused = false
    @Volatile
    private var capturing = false
    private var lastCaptureMs = 0L
    private var scrollJob: Job? = null

    // Cached translated blocks for "Leggi" reader
    private val currentTranslatedTexts = ArrayList<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.i("Lente", "LensAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!lockOn || paused) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        scrollJob?.cancel()
        scrollJob = scope.launch {
            delay(500)
            captureAndTranslate()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disableLockInternal()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disableLockInternal()
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    fun isLockEnabled(): Boolean = lockOn

    fun setLockEnabled(on: Boolean) {
        if (on) {
            lockOn = true
            paused = false
            addOverlay()
            scope.launch { captureAndTranslate() }
        } else {
            disableLockInternal()
        }
    }

    private fun disableLockInternal() {
        lockOn = false
        capturing = false
        scrollJob?.cancel()
        scope.launch(Dispatchers.Main) {
            overlay?.let { wm?.removeViewImmediate(it) }
            controller?.let { wm?.removeViewImmediate(it) }
            readerModal?.let { wm?.removeViewImmediate(it) }
            overlay = null
            controller = null
            readerModal = null
        }
    }

    private fun addOverlay() {
        val manager = wm ?: return
        if (overlay == null) {
            val layer = TranslationLayerView(this)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            manager.addView(layer, lp)
            overlay = layer
        }

        if (controller == null) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0xF0111827.toInt())
                setPadding(12, 10, 12, 10)
            }

            // 1. Google Lens official 1-tap launcher
            val lensBtn = Button(this).apply {
                text = "🔍 Google Lens"
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF4F46E5.toInt())
                textSize = 12f
                setPadding(16, 6, 16, 6)
                setOnClickListener {
                    scope.launch { launchGoogleLensWithScreenshot() }
                }
            }

            // 2. In-place neural translation
            val translateBtn = Button(this).apply {
                text = "🔄 Traduci"
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF2563EB.toInt())
                textSize = 12f
                setPadding(16, 6, 16, 6)
                setOnClickListener {
                    scope.launch { captureAndTranslate() }
                }
            }

            // 3. Reader Sheet (Big font card)
            val readBtn = Button(this).apply {
                text = "📖 Leggi"
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF0D9488.toInt())
                textSize = 12f
                setPadding(16, 6, 16, 6)
                setOnClickListener {
                    showReaderModal()
                }
            }

            // 4. Close
            val stopBtn = Button(this).apply {
                text = "⏹ Stop"
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFFDC2626.toInt())
                textSize = 12f
                setPadding(16, 6, 16, 6)
                setOnClickListener { setLockEnabled(false) }
            }

            val marginLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(6, 0, 6, 0) }

            bar.addView(lensBtn, marginLp)
            bar.addView(translateBtn, marginLp)
            bar.addView(readBtn, marginLp)
            bar.addView(stopBtn, marginLp)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 12
            }
            manager.addView(bar, lp)
            controller = bar
        }
    }

    /**
     * Captures the screen, saves to temporary cache, and immediately launches official Google Lens!
     */
    private suspend fun launchGoogleLensWithScreenshot() {
        val bmp = takeScreenshotAsync()
        if (bmp == null) {
            Toast.makeText(this, "Impossibile catturare lo schermo", Toast.LENGTH_SHORT).show()
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val cacheDir = File(cacheDir, "images").apply { mkdirs() }
                val file = File(cacheDir, "lens_capture.png")
                FileOutputStream(file).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                val uri: Uri = FileProvider.getUriForFile(
                    this@LensAccessibilityService,
                    "$packageName.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    setPackage("com.google.ar.lens")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (packageManager.resolveActivity(intent, 0) != null) {
                    startActivity(intent)
                } else {
                    // Fallback to Google Search Lens / general chooser
                    val chooser = Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Apri con Google Lens"
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    startActivity(chooser)
                }
            } catch (e: Exception) {
                Log.e("Lente", "Error launching Google Lens: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LensAccessibilityService, "Errore avvio Google Lens: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun captureAndTranslate() {
        if (capturing) return
        val now = System.currentTimeMillis()
        if (now - lastCaptureMs < 1000) {
            delay(1000 - (now - lastCaptureMs))
        }
        capturing = true
        lastCaptureMs = System.currentTimeMillis()

        try {
            overlay?.clear() // Clear previous boxes immediately
            val bmp = takeScreenshotAsync() ?: return
            val sourcePref = Prefs.sourceLang(this)
            val target = Prefs.targetLang(this)

            val boxes = withContext(Dispatchers.Default) {
                val soft = if (bmp.config == Bitmap.Config.HARDWARE) {
                    bmp.copy(Bitmap.Config.ARGB_8888, false)
                } else bmp
                if (soft == null) return@withContext emptyList<TranslationLayerView.Box>()

                val (blocks, script) = ocr.ocr(soft, sourcePref)
                if (blocks.isEmpty()) return@withContext emptyList<TranslationLayerView.Box>()

                val src = ocr.detectSource(blocks, script, sourcePref)
                val translated = ocr.translateBlocks(blocks, src, target)

                // Update text cache for modal reader
                synchronized(currentTranslatedTexts) {
                    currentTranslatedTexts.clear()
                    for (t in translated) {
                        val tr = t.translated?.trim()
                        if (!tr.isNullOrEmpty() && tr.length >= 2) {
                            currentTranslatedTexts.add(tr)
                        }
                    }
                }

                translated.mapNotNull { box ->
                    val t = box.translated?.trim() ?: return@mapNotNull null
                    if (t.isEmpty()) return@mapNotNull null
                    TranslationLayerView.Box(
                        RectF(box.rect),
                        t,
                        Color.parseColor("#EE111827"),
                        Color.WHITE
                    )
                }
            }

            overlay?.setContent(bmp.width, bmp.height, boxes)
        } finally {
            capturing = false
        }
    }

    private fun showReaderModal() {
        val manager = wm ?: return
        if (readerModal != null) {
            manager.removeViewImmediate(readerModal)
            readerModal = null
            return
        }

        val texts = synchronized(currentTranslatedTexts) { ArrayList(currentTranslatedTexts) }
        val dialog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF50F172A.toInt())
            setPadding(24, 20, 24, 20)
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        val title = TextView(this).apply {
            text = "📖 Traduzione Completa a Schermo Intero"
            setTextColor(Color.WHITE)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = Button(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 18f
            setOnClickListener {
                readerModal?.let { manager.removeViewImmediate(it) }
                readerModal = null
            }
        }
        titleBar.addView(title)
        titleBar.addView(closeBtn)
        dialog.addView(titleBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.45f).toInt()
            )
        }
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (texts.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Nessun testo tradotto rilevato. Tocca prima '🔄 Traduci' o '🔍 Google Lens'."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 16f
                setPadding(0, 24, 0, 24)
            }
            contentLayout.addView(emptyTv)
        } else {
            for (line in texts) {
                val tv = TextView(this).apply {
                    text = "• $line"
                    setTextColor(Color.parseColor("#F8FAFC"))
                    textSize = 19f
                    setLineSpacing(6f, 1.2f)
                    setPadding(0, 8, 0, 12)
                }
                contentLayout.addView(tv)
            }
        }
        scroll.addView(contentLayout)
        dialog.addView(scroll)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        val copyBtn = Button(this).apply {
            text = "📋 Copia Tutto"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF2563EB.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener {
                val fullText = texts.joinToString("\n")
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Traduzione Lente", fullText))
                Toast.makeText(this@LensAccessibilityService, "Copiato negli appunti!", Toast.LENGTH_SHORT).show()
            }
        }
        val googleBtn = Button(this).apply {
            text = "🔍 Google Lens"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF4F46E5.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 0, 0)
            }
            setOnClickListener {
                readerModal?.let { manager.removeViewImmediate(it) }
                readerModal = null
                scope.launch { launchGoogleLensWithScreenshot() }
            }
        }
        bottomBar.addView(copyBtn)
        bottomBar.addView(googleBtn)
        dialog.addView(bottomBar)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 30
        }
        manager.addView(dialog, lp)
        readerModal = dialog
    }

    private suspend fun takeScreenshotAsync(): Bitmap? {
        // HIDE overlay and controller so the app NEVER screenshots itself or its previous translation boxes!
        withContext(Dispatchers.Main) {
            overlay?.visibility = View.INVISIBLE
            controller?.visibility = View.INVISIBLE
        }
        delay(120) // Allow WindowManager compositor to drop the overlay from the screen frame

        val bmp: Bitmap? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val hw = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer,
                                result.colorSpace
                            )
                            result.hardwareBuffer.close()
                            val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            cont.resume(soft)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w("Lente", "takeScreenshot failed: $errorCode")
                            cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.w("Lente", "takeScreenshot threw", e)
                cont.resume(null)
            }
        }

        withContext(Dispatchers.Main) {
            controller?.visibility = View.VISIBLE
            overlay?.visibility = View.VISIBLE
        }
        return bmp
    }
}
