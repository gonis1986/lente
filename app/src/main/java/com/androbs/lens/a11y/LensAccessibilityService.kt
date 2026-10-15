package com.androbs.lens.a11y

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.androbs.lens.Prefs
import com.androbs.lens.R
import com.androbs.lens.lens.GoogleLensLauncher
import com.androbs.lens.ocr.OcrTranslator
import com.androbs.lens.ui.ColorSampler
import com.androbs.lens.ui.TranslationLayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
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
    private var captureAfterCurrent = false
    private var lastCaptureMs = 0L
    private var scrollJob: Job? = null
    private var previousGrayFrame: IntArray? = null

    private data class ScreenshotCapture(
        val bitmap: Bitmap,
        val offsetX: Int = 0,
        val offsetY: Int = 0
    )

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
        if (event.packageName?.toString() == packageName) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        scrollJob?.cancel()
        scrollJob = scope.launch {
            delay(280)
            val remaining = 950L - (System.currentTimeMillis() - lastCaptureMs)
            if (remaining > 0) delay(remaining)
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
            scope.launch {
                delay(350)
                captureAndTranslate()
            }
        } else {
            disableLockInternal()
        }
    }

    private fun disableLockInternal() {
        lockOn = false
        capturing = false
        captureAfterCurrent = false
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            manager.addView(layer, lp)
            overlay = layer
        }

        if (controller == null) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0xF0111827.toInt())
                setPadding(12, 10, 12, 10)
            }

            val lensBtn = Button(this).apply {
                text = "🔍"
                contentDescription = getString(R.string.controller_lens)
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF4F46E5.toInt())
                textSize = 16f
                setPadding(4, 2, 4, 2)
                setOnClickListener {
                    scope.launch { launchGoogleLensWithScreenshot() }
                }
            }

            val translateBtn = Button(this).apply {
                text = "↻"
                contentDescription = getString(R.string.controller_translate)
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF2563EB.toInt())
                textSize = 16f
                setPadding(4, 2, 4, 2)
                setOnClickListener {
                    scope.launch { captureAndTranslate() }
                }
            }

            val readBtn = Button(this).apply {
                text = "📖"
                contentDescription = getString(R.string.controller_reader)
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF0D9488.toInt())
                textSize = 16f
                setPadding(4, 2, 4, 2)
                setOnClickListener {
                    showReaderModal()
                }
            }

            val stopBtn = Button(this).apply {
                text = "×"
                contentDescription = getString(R.string.lock_stop)
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFFDC2626.toInt())
                textSize = 16f
                setPadding(4, 2, 4, 2)
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
                gravity = Gravity.TOP or Gravity.RIGHT
                x = 8
                y = 160
            }
            manager.addView(bar, lp)
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            bar.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        val current = bar.layoutParams as WindowManager.LayoutParams
                        startX = current.x
                        startY = current.y
                        moved = false
                        false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (!moved && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) moved = true
                        if (moved) {
                            val current = bar.layoutParams as WindowManager.LayoutParams
                            current.x = (startX - dx).coerceAtLeast(0)
                            current.y = (startY + dy).coerceAtLeast(0)
                            manager.updateViewLayout(bar, current)
                            true
                        } else false
                    }
                    android.view.MotionEvent.ACTION_UP -> moved
                    else -> false
                }
            }
            controller = bar
        }
    }

    private suspend fun launchGoogleLensWithScreenshot() {
        val capture = takeScreenshotAsync() ?: return
        try {
            GoogleLensLauncher.launch(this, capture.bitmap)
        } finally {
            capture.bitmap.recycle()
        }
    }

    private suspend fun captureAndTranslate() {
        if (!lockOn || paused) return
        if (capturing) {
            captureAfterCurrent = true
            return
        }
        capturing = true
        var capturedBitmap: Bitmap? = null
        try {
            val remaining = 950L - (System.currentTimeMillis() - lastCaptureMs)
            if (remaining > 0) delay(remaining)
            lastCaptureMs = System.currentTimeMillis()
            overlay?.setProcessing(true)
            val capture = takeScreenshotAsync() ?: return
            val bmp = capture.bitmap
            capturedBitmap = bmp
            if (!hasMeaningfulDiff(bmp)) {
                Log.i("Lente", "lock diff guard skipped unchanged frame")
                overlay?.setProcessing(false)
                return
            }
            val sourcePref = Prefs.sourceLang(this)
            val target = Prefs.targetLang(this)

            val clusters = withContext(Dispatchers.Default) {
                val (blocks, script) = ocr.ocr(bmp, sourcePref)
                val detected = ocr.clusterBlocks(blocks)
                if (detected.isEmpty()) return@withContext emptyList()
                val source = ocr.detectSourceTexts(
                    detected.map { it.sourceText },
                    script,
                    sourcePref
                )
                val translated = ocr.translateClusters(detected, source, target)
                translated.forEach { ColorSampler.apply(bmp, it) }
                synchronized(currentTranslatedTexts) {
                    currentTranslatedTexts.clear()
                    for (cluster in translated) {
                        val tr = cluster.translatedText.trim()
                        if (!tr.isNullOrEmpty() && tr.length >= 2) {
                            currentTranslatedTexts.add(tr)
                        }
                    }
                }
                translated
            }

            overlay?.setClusters(
                bmp.width,
                bmp.height,
                clusters,
                capture.offsetX.toFloat(),
                capture.offsetY.toFloat()
            )
            overlay?.setHighlights(
                bmp.width,
                bmp.height,
                clusters.map { RectF(it.outerBounds) },
                capture.offsetX.toFloat(),
                capture.offsetY.toFloat()
            )
            overlay?.setProcessing(false)
            Log.i(
                "Lente",
                "lock rendered clusters=${clusters.size} offset=${capture.offsetX},${capture.offsetY}"
            )
        } finally {
            overlay?.setProcessing(false)
            capturedBitmap?.recycle()
            capturing = false
            val recapture = captureAfterCurrent && lockOn && !paused
            captureAfterCurrent = false
            if (recapture) {
                scrollJob?.cancel()
                scrollJob = scope.launch {
                    val remaining = 950L - (System.currentTimeMillis() - lastCaptureMs)
                    if (remaining > 0) delay(remaining)
                    captureAndTranslate()
                }
            }
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

    private suspend fun takeScreenshotAsync(): ScreenshotCapture? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val activeWindow = activeApplicationWindow()
            if (activeWindow == null) {
                Log.w("Lente", "takeScreenshotOfWindow skipped: no active application window")
                return null
            }
            val bounds = Rect()
            activeWindow.getBoundsInScreen(bounds)
            Log.i("Lente", "lock capture path=takeScreenshotOfWindow window=${activeWindow.id}")
            return suspendCancellableCoroutine { continuation ->
                try {
                    takeScreenshotOfWindow(
                        activeWindow.id,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(result: ScreenshotResult) {
                                val capture = captureBitmap(result, bounds.left, bounds.top)
                                if (continuation.isActive) continuation.resume(capture)
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.w("Lente", "takeScreenshotOfWindow failed: $errorCode")
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                    )
                } catch (error: Exception) {
                    Log.w("Lente", "takeScreenshotOfWindow threw", error)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

        Log.i("Lente", "lock capture path=takeScreenshot frame-synchronized api=${Build.VERSION.SDK_INT}")
        withContext(Dispatchers.Main) {
            overlay?.alpha = 0f
            controller?.alpha = 0f
        }
        return try {
            repeat(2) { awaitNextFrame() }
            suspendCancellableCoroutine { continuation ->
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(result: ScreenshotResult) {
                                val capture = captureBitmap(result)
                                if (continuation.isActive) continuation.resume(capture)
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.w("Lente", "takeScreenshot failed: $errorCode")
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                    )
                } catch (error: Exception) {
                    Log.w("Lente", "takeScreenshot threw", error)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                overlay?.alpha = 1f
                controller?.alpha = 1f
            }
        }
    }

    private fun activeApplicationWindow(): AccessibilityWindowInfo? =
        try {
            windows
                .filter { window ->
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        window.root?.packageName?.toString() != packageName
                }
                .maxByOrNull { window -> if (window.isFocused) 2 else if (window.isActive) 1 else 0 }
                ?.takeIf { it.isFocused || it.isActive }
        } catch (error: Exception) {
            Log.w("Lente", "Unable to inspect application windows", error)
            null
        }

    private fun captureBitmap(
        result: ScreenshotResult,
        offsetX: Int = 0,
        offsetY: Int = 0
    ): ScreenshotCapture? {
        val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
        result.hardwareBuffer.close()
        val bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
        hardware?.recycle()
        return bitmap?.let { ScreenshotCapture(it, offsetX, offsetY) }
    }

    private suspend fun awaitNextFrame() {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val choreographer = Choreographer.getInstance()
                val callback = Choreographer.FrameCallback {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                choreographer.postFrameCallback(callback)
                continuation.invokeOnCancellation {
                    choreographer.removeFrameCallback(callback)
                }
            }
        }
    }

    private fun hasMeaningfulDiff(bitmap: Bitmap): Boolean {
        val sample = IntArray(16 * 16)
        var index = 0
        for (row in 0 until 16) {
            val y = ((row + 0.5f) * bitmap.height / 16f).toInt().coerceIn(0, bitmap.height - 1)
            for (column in 0 until 16) {
                val x = ((column + 0.5f) * bitmap.width / 16f).toInt().coerceIn(0, bitmap.width - 1)
                val pixel = bitmap.getPixel(x, y)
                sample[index++] = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            }
        }
        val previous = previousGrayFrame
        previousGrayFrame = sample
        if (previous == null) return true
        val meanDifference = sample.indices.sumOf { kotlin.math.abs(sample[it] - previous[it]) } / sample.size.toFloat()
        return meanDifference >= 5f
    }
}
