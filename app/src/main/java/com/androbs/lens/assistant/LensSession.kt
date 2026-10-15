package com.androbs.lens.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import com.androbs.lens.Prefs
import com.androbs.lens.R
import com.androbs.lens.a11y.LensAccessibilityService
import com.androbs.lens.lens.GoogleLensLauncher
import com.androbs.lens.ocr.OcrTranslator
import com.androbs.lens.ui.ColorSampler
import com.androbs.lens.ui.FitTransform
import com.androbs.lens.ui.RegionSelectView
import com.androbs.lens.ui.ScreenshotView
import com.androbs.lens.ui.TranslationLayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class LensSession(context: Context) : VoiceInteractionSession(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ocr = OcrTranslator()
    private var view: View? = null
    private var pendingBitmap: Bitmap? = null
    private var ocrJob: Job? = null
    private var allClusters: List<OcrTranslator.BalloonCluster> = emptyList()
    private var selectedClusters: List<OcrTranslator.BalloonCluster> = emptyList()
    private var scriptHint = "latin"
    private var ocrDone = false
    private var showingTranslations = false

    private val shotView get() = view?.findViewById<ScreenshotView>(R.id.screenshot)
    private val translations get() = view?.findViewById<TranslationLayerView>(R.id.translations)
    private val selector get() = view?.findViewById<RegionSelectView>(R.id.selector)
    private val status get() = view?.findViewById<TextView>(R.id.status)
    private val hint get() = view?.findViewById<TextView>(R.id.hint)
    private val resultCard get() = view?.findViewById<LinearLayout>(R.id.resultCard)
    private val modeButton get() = view?.findViewById<Button>(R.id.btnMode)

    override fun onCreate() {
        super.onCreate()
        setTheme(android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val content = layoutInflater.inflate(R.layout.session_overlay, null)
        view = content
        setContentView(content)
        getWindow().window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        wire(content)
        pendingBitmap?.let { bitmap ->
            pendingBitmap = null
            val image = argb(bitmap)
            shotView?.setBitmap(image)
            analyzeScreen(image)
        }
    }

    override fun onHandleScreenshot(bitmap: Bitmap?) {
        super.onHandleScreenshot(bitmap)
        if (bitmap == null) {
            pendingBitmap = null
            view?.findViewById<TextView>(R.id.status)?.apply {
                text = context.getString(R.string.status_no_screenshot)
                visibility = View.VISIBLE
            }
            return
        }
        val image = argb(bitmap)
        val screenshot = shotView
        if (screenshot == null) {
            pendingBitmap = image
        } else {
            screenshot.setBitmap(image)
            analyzeScreen(image)
        }
    }

    override fun onComputeInsets(insets: Insets) {
        super.onComputeInsets(insets)
        insets.contentInsets.set(0, 0, 0, 0)
        insets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
    }

    override fun onBackPressed() = finish()

    override fun onHide() {
        super.onHide()
        ocrJob?.cancel()
        view = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun argb(bitmap: Bitmap): Bitmap =
        if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } else bitmap

    private fun wire(content: View) {
        content.findViewById<Button>(R.id.btnSearch).setOnClickListener { onSearchClicked() }
        content.findViewById<Button>(R.id.btnTranslateSelection)
            .setOnClickListener { onTranslateClicked() }
        content.findViewById<Button>(R.id.btnMode).setOnClickListener { toggleDisplayMode() }
        content.findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
        content.findViewById<Button>(R.id.btnLock).setOnClickListener { onLockClicked() }
        selector?.onSelected = { region -> onRegionSelected(region) }
        selector?.onTap = { x, y -> onTap(x, y) }
        content.findViewById<Button>(R.id.btnGoogle).setOnClickListener { searchTextOnGoogle() }
        content.findViewById<Button>(R.id.btnCopy).setOnClickListener { copyResultText() }
        content.findViewById<Button>(R.id.btnLens).setOnClickListener { openLensForSelection() }
    }

    private fun getStringCtx(id: Int) = context.getString(id)

    private fun showStatus(message: String?, sticky: Boolean = false) {
        status?.apply {
            text = message
            visibility = if (message == null) View.GONE else View.VISIBLE
        }
        if (!sticky) {
            view?.postDelayed({ if (status?.text == message) status?.visibility = View.GONE }, 4000)
        }
    }

    private fun analyzeScreen(bitmap: Bitmap) {
        ocrJob?.cancel()
        ocrDone = false
        showingTranslations = false
        allClusters = emptyList()
        selectedClusters = emptyList()
        selector?.selectionEnabled = true
        translations?.clearAll()
        translations?.setProcessing(true)
        modeButton?.setText(R.string.banner_translate_all)
        hint?.apply {
            setText(R.string.hint_scanning)
            visibility = View.VISIBLE
        }
        ocrJob = scope.launch(Dispatchers.Default) {
            try {
                val (blocks, script) = ocr.ocr(bitmap, Prefs.sourceLang(context))
                scriptHint = script
                val detected = ocr.clusterBlocks(blocks)
                val source = ocr.detectSourceTexts(
                    detected.map { it.sourceText },
                    script,
                    Prefs.sourceLang(context)
                )
                allClusters = detected
                ocrDone = true
                if (Prefs.autoTranslateOnOpen(context) && detected.isNotEmpty()) {
                    val translated = translateAndColor(detected, bitmap, source)
                    allClusters = mergeClusters(allClusters, translated)
                    withContext(Dispatchers.Main) {
                        showingTranslations = true
                        translations?.setClusters(
                            bitmap.width,
                            bitmap.height,
                            allClusters.filter { it.translatedText.isNotBlank() }
                        )
                        translations?.setHighlights(bitmap.width, bitmap.height, allClusters.map { RectF(it.outerBounds) })
                        translations?.setProcessing(false)
                        modeButton?.setText(R.string.banner_show_original)
                        hint?.setText(R.string.hint_tap_or_drag)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        translations?.setHighlights(bitmap.width, bitmap.height, detected.map { RectF(it.outerBounds) })
                        translations?.setProcessing(false)
                        modeButton?.setText(R.string.banner_translate_all)
                        hint?.setText(if (detected.isEmpty()) R.string.hint_drag else R.string.hint_tap_or_drag)
                    }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    translations?.setProcessing(false)
                    hint?.setText(R.string.hint_drag)
                    showStatus(context.getString(R.string.status_error, error.message ?: "?"))
                }
            }
        }
    }

    private suspend fun translateAndColor(
        clusters: List<OcrTranslator.BalloonCluster>,
        bitmap: Bitmap,
        source: String
    ): List<OcrTranslator.BalloonCluster> = withContext(Dispatchers.Default) {
        val translated = ocr.translateClusters(clusters, source, Prefs.targetLang(context))
        translated.forEach {
            ColorSampler.apply(bitmap, it, context.resources.displayMetrics.density)
        }
        translated
    }

    private fun mergeClusters(
        original: List<OcrTranslator.BalloonCluster>,
        updates: List<OcrTranslator.BalloonCluster>
    ): List<OcrTranslator.BalloonCluster> {
        val byId = updates.associateBy { it.id }
        return original.map { byId[it.id] ?: it }
    }

    private fun toggleDisplayMode() {
        val bitmap = shotView?.bitmap() ?: return
        if (showingTranslations) {
            showingTranslations = false
            translations?.setContent(bitmap.width, bitmap.height, emptyList())
            translations?.setHighlights(bitmap.width, bitmap.height, allClusters.map { RectF(it.outerBounds) })
            modeButton?.setText(R.string.banner_translate_all)
        } else {
            selectedClusters = emptyList()
            translations?.setSelection(emptyList())
            onTranslateClicked()
        }
    }

    private fun onLockClicked() {
        val service = LensAccessibilityService.instance
        if (service == null) {
            showStatus(getStringCtx(R.string.lock_enable_first), sticky = true)
            try {
                startAssistantActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                Unit
            }
            return
        }
        service.setLockEnabled(true)
        Toast.makeText(context, R.string.lock_active, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun viewToBitmap(): android.graphics.Matrix? {
        val bitmap = shotView?.bitmap() ?: return null
        val screenshot = shotView ?: return null
        val matrix = FitTransform.matrix(screenshot.width, screenshot.height, bitmap.width, bitmap.height)
        val inverse = android.graphics.Matrix()
        matrix.invert(inverse)
        return inverse
    }

    private fun onTap(x: Float, y: Float) {
        val bitmap = shotView?.bitmap() ?: return
        val screenshot = shotView ?: return
        val matrix = FitTransform.matrix(screenshot.width, screenshot.height, bitmap.width, bitmap.height)
        val mappedBounds = allClusters.map { cluster ->
            cluster to RectF().also { matrix.mapRect(it, cluster.outerBounds) }
        }
        val hit = mappedBounds
            .filter { (_, bounds) -> bounds.contains(x, y) }
            .minByOrNull { (_, bounds) -> bounds.width() * bounds.height() }
            ?.first
            ?: mappedBounds
                .filter { (_, bounds) -> expanded(bounds, 24f).contains(x, y) }
                .minByOrNull { (_, bounds) -> bounds.width() * bounds.height() }
                ?.first

        if (hit == null) {
            selectedClusters = emptyList()
            translations?.setSelection(emptyList())
            resultCard?.visibility = View.GONE
        } else {
            selectClusters(listOf(hit))
        }
    }

    private fun expanded(rect: RectF, distance: Float) =
        RectF(rect.left - distance, rect.top - distance, rect.right + distance, rect.bottom + distance)

    private fun onRegionSelected(viewRect: RectF) {
        val bitmap = shotView?.bitmap() ?: return
        val inverse = viewToBitmap() ?: return
        val bitmapRect = RectF()
        inverse.mapRect(bitmapRect, viewRect)
        val cropRect = Rect(
            maxOf(0, bitmapRect.left.toInt()),
            maxOf(0, bitmapRect.top.toInt()),
            minOf(bitmap.width, bitmapRect.right.toInt() + 1),
            minOf(bitmap.height, bitmapRect.bottom.toInt() + 1)
        )
        if (cropRect.width() < 8 || cropRect.height() < 8) return
        val selected = allClusters.filter {
            RectF.intersects(RectF(it.outerBounds), bitmapRect)
        }
        if (selected.isNotEmpty()) {
            selectClusters(selected)
        } else {
            runOcrFallback(
                Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height()),
                cropRect
            )
        }
    }

    private fun runOcrFallback(crop: Bitmap, offset: Rect) {
        showStatus(getStringCtx(R.string.status_working), sticky = true)
        translations?.setProcessing(true)
        scope.launch(Dispatchers.Default) {
            try {
                val (blocks, script) = ocr.ocr(crop, Prefs.sourceLang(context))
                if (script == "ja") scriptHint = "ja"
                val detected = ocr.clusterBlocks(blocks)
                val source = ocr.detectSourceTexts(
                    detected.map { it.sourceText },
                    script,
                    Prefs.sourceLang(context)
                )
                val clusters = translateAndColor(detected, crop, source).map { cluster ->
                    cluster.outerBounds.offset(offset.left.toFloat(), offset.top.toFloat())
                    cluster.lineRects.forEach { it.offset(offset.left.toFloat(), offset.top.toFloat()) }
                    cluster.eraseRects.forEach { it.offset(offset.left.toFloat(), offset.top.toFloat()) }
                    cluster.writingRect?.offset(offset.left.toFloat(), offset.top.toFloat())
                    cluster.copy(id = "crop-${offset.left}-${offset.top}-${cluster.id}")
                }
                withContext(Dispatchers.Main) {
                    translations?.setProcessing(false)
                    showStatus(null)
                    if (clusters.isEmpty()) {
                        showStatus(getStringCtx(R.string.status_no_text))
                    } else {
                        allClusters = allClusters + clusters
                        shotView?.bitmap()?.let { bitmap ->
                            translations?.setClusters(
                                bitmap.width,
                                bitmap.height,
                                allClusters.filter { it.translatedText.isNotBlank() }
                            )
                            translations?.setHighlights(
                                bitmap.width,
                                bitmap.height,
                                allClusters.map { RectF(it.outerBounds) }
                            )
                        }
                        showingTranslations = true
                        modeButton?.setText(R.string.banner_show_original)
                        selectClusters(clusters)
                    }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    translations?.setProcessing(false)
                    showStatus(context.getString(R.string.status_error, error.message ?: "?"))
                }
            } finally {
                crop.recycle()
            }
        }
    }

    private fun selectClusters(clusters: List<OcrTranslator.BalloonCluster>) {
        selectedClusters = clusters
        translations?.setSelection(clusters.map { RectF(it.outerBounds) })
        showResultCard(clusters)
    }

    private fun joinedOriginal(clusters: List<OcrTranslator.BalloonCluster>) =
        clusters.joinToString("\n") { it.sourceText }.trim()

    private fun joinedTranslation(clusters: List<OcrTranslator.BalloonCluster>) =
        clusters.map { it.translatedText }.filter { it.isNotBlank() }.joinToString("\n").trim()

    private fun onSearchClicked() {
        val targets = selectedClusters.ifEmpty { allClusters }
        if (targets.isEmpty()) {
            showStatus(
                getStringCtx(
                    when {
                        shotView?.bitmap() == null -> R.string.status_no_screenshot
                        ocrDone -> R.string.status_no_text
                        else -> R.string.status_scanning
                    }
                )
            )
            return
        }
        showResultCard(targets)
        openGoogleSearch(joinedOriginal(targets))
    }

    private fun onTranslateClicked() {
        val bitmap = shotView?.bitmap()
        if (bitmap == null) {
            showStatus(getStringCtx(R.string.status_no_screenshot), sticky = true)
            return
        }
        val targets = selectedClusters.ifEmpty { allClusters }
        if (targets.isEmpty()) {
            showStatus(getStringCtx(if (ocrDone) R.string.status_no_text else R.string.status_scanning))
            return
        }
        resultCard?.visibility = View.GONE
        translations?.setProcessing(true)
        showStatus(getStringCtx(R.string.status_working), sticky = true)
        scope.launch(Dispatchers.Default) {
            try {
                val source = ocr.detectSourceTexts(
                    targets.map { it.sourceText },
                    scriptHint,
                    Prefs.sourceLang(context)
                )
                val translated = translateAndColor(targets, bitmap, source)
                allClusters = mergeClusters(allClusters, translated)
                withContext(Dispatchers.Main) {
                    showingTranslations = true
                    translations?.setClusters(
                        bitmap.width,
                        bitmap.height,
                        allClusters.filter { it.translatedText.isNotBlank() }
                    )
                    translations?.setHighlights(bitmap.width, bitmap.height, allClusters.map { RectF(it.outerBounds) })
                    translations?.setSelection(selectedClusters.map { RectF(it.outerBounds) })
                    translations?.setProcessing(false)
                    modeButton?.setText(R.string.banner_show_original)
                    showStatus(null)
                    if (selectedClusters.isNotEmpty()) {
                        selectedClusters = mergeClusters(selectedClusters, translated)
                        showResultCard(selectedClusters)
                    }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    translations?.setProcessing(false)
                    showStatus(context.getString(R.string.status_error, error.message ?: "?"))
                }
            }
        }
    }

    private fun showResultCard(clusters: List<OcrTranslator.BalloonCluster>) {
        showStatus(null)
        val card = resultCard ?: return
        card.visibility = View.VISIBLE
        card.findViewById<TextView>(R.id.resultText).text = joinedOriginal(clusters)
        card.findViewById<TextView>(R.id.resultTranslation).text =
            joinedTranslation(clusters).ifBlank { getStringCtx(R.string.result_translation_unavailable) }
    }

    private fun searchTextOnGoogle() {
        val text = resultCard?.findViewById<TextView>(R.id.resultText)?.text?.toString() ?: return
        openGoogleSearch(text)
    }

    private fun openGoogleSearch(text: String) {
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8")
        try {
            startAssistantActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (error: Exception) {
            showStatus(context.getString(R.string.status_error, error.message ?: "?"))
        }
    }

    private fun copyResultText() {
        val text = resultCard?.findViewById<TextView>(R.id.resultText)?.text?.toString() ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("lente", text))
        showStatus(getStringCtx(R.string.result_copied))
    }

    private fun openLensForSelection() {
        val bitmap = shotView?.bitmap() ?: return
        val selected = selectedClusters.ifEmpty { allClusters }
        if (selected.isEmpty()) return
        val bounds = RectF(selected.first().outerBounds)
        selected.drop(1).forEach { bounds.union(it.outerBounds) }
        val left = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, bitmap.height)
        scope.launch { GoogleLensLauncher.launch(context, Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)) }
    }
}
