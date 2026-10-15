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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.androbs.lens.Prefs
import com.androbs.lens.R
import com.androbs.lens.a11y.LensAccessibilityService
import com.androbs.lens.ocr.OcrTranslator
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

/**
 * The overlay shown when the user invokes the assistant (long-press Home or
 * corner swipe). Works like Circle to Search: the captured screen is shown
 * dimmed, every detected text block is highlighted with a tappable pill, and
 * the user can tap a word or drag a rectangle to select text — then Search,
 * Copy or Translate it. The banner's Search/Translate act on the selection,
 * or on the whole screen when nothing is selected.
 */
class LensSession(context: Context) : VoiceInteractionSession(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ocr = OcrTranslator()

    private var view: View? = null
    private var pendingBitmap: Bitmap? = null
    private var ocrJob: Job? = null

    /** Every text block found on the captured screen, in bitmap coordinates. */
    private var allBoxes: List<OcrTranslator.TextBox> = emptyList()

    /** Blocks the user tapped/drag-selected, in bitmap coordinates. */
    private var selectedBoxes: List<OcrTranslator.TextBox> = emptyList()
    private var scriptHint = "latin"
    private var ocrDone = false

    private val shotView get() = view?.findViewById<ScreenshotView>(R.id.screenshot)
    private val translations get() = view?.findViewById<TranslationLayerView>(R.id.translations)
    private val selector get() = view?.findViewById<RegionSelectView>(R.id.selector)
    private val status get() = view?.findViewById<TextView>(R.id.status)
    private val hint get() = view?.findViewById<TextView>(R.id.hint)
    private val resultCard get() = view?.findViewById<LinearLayout>(R.id.resultCard)

    override fun onCreate() {
        super.onCreate()
        setTheme(android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val v = layoutInflater.inflate(R.layout.session_overlay, null)
        view = v
        setContentView(v)
        getWindow().window?.apply {
            setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        wire(v)
        pendingBitmap?.let {
            val b = argb(it)
            pendingBitmap = null
            shotView?.setBitmap(b)
            analyzeScreen(b)
        }
    }

    override fun onHandleScreenshot(bitmap: Bitmap?) {
        super.onHandleScreenshot(bitmap)
        if (bitmap == null) {
            val v = view ?: return
            pendingBitmap = null
            v.findViewById<TextView>(R.id.status)?.apply {
                text = context.getString(R.string.status_no_screenshot)
                visibility = View.VISIBLE
            }
            return
        }
        val b = argb(bitmap)
        val sv = shotView
        if (sv == null) {
            pendingBitmap = b
        } else {
            sv.setBitmap(b)
            analyzeScreen(b)
        }
    }

    override fun onComputeInsets(insets: Insets) {
        super.onComputeInsets(insets)
        insets.contentInsets.set(0, 0, 0, 0)
        insets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onHide() {
        super.onHide()
        view = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun argb(b: Bitmap): Bitmap =
        if (b.config == Bitmap.Config.HARDWARE) {
            b.copy(Bitmap.Config.ARGB_8888, false) ?: b
        } else b

    private fun wire(v: View) {
        v.findViewById<Button>(R.id.btnSearch).setOnClickListener { onSearchClicked() }
        v.findViewById<Button>(R.id.btnTranslate).setOnClickListener { onTranslateClicked() }
        v.findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
        v.findViewById<Button>(R.id.btnLock).setOnClickListener { onLockClicked() }
        selector?.onSelected = { rect -> onRegionSelected(rect) }
        selector?.onTap = { x, y -> onTap(x, y) }
        v.findViewById<Button>(R.id.btnGoogle).setOnClickListener { searchTextOnGoogle() }
        v.findViewById<Button>(R.id.btnCopy).setOnClickListener { copyResultText() }
        v.findViewById<Button>(R.id.btnTranslateResult).setOnClickListener { translateSelection() }
    }

    private fun getStringCtx(id: Int) = context.getString(id)

    private fun showStatus(msg: String?, sticky: Boolean = false) {
        status?.apply {
            text = msg
            visibility = if (msg == null) View.GONE else View.VISIBLE
        }
        if (!sticky) {
            view?.postDelayed({ if (status?.text == msg) status?.visibility = View.GONE }, 4000)
        }
    }

    /** OCRs the whole captured screen in the background and highlights every block. */
    private fun analyzeScreen(bmp: Bitmap) {
        ocrJob?.cancel()
        ocrDone = false
        allBoxes = emptyList()
        selectedBoxes = emptyList()
        selector?.selectionEnabled = true
        hint?.apply {
            setText(R.string.hint_scanning)
            visibility = View.VISIBLE
        }
        ocrJob = scope.launch(Dispatchers.Default) {
            try {
                val (blocks, script) = ocr.ocr(bmp, Prefs.sourceLang(context))
                scriptHint = script
                val boxes = blocks.mapNotNull { block ->
                    val r = block.boundingBox ?: return@mapNotNull null
                    if (block.text.isBlank()) return@mapNotNull null
                    OcrTranslator.TextBox(r, block.text.trim(), null)
                }
                allBoxes = boxes
                ocrDone = true
                withContext(Dispatchers.Main) {
                    translations?.setHighlights(
                        bmp.width, bmp.height, boxes.map { RectF(it.rect) }
                    )
                    hint?.apply {
                        setText(
                            if (boxes.isEmpty()) R.string.hint_drag
                            else R.string.hint_tap_or_drag
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hint?.setText(R.string.hint_drag)
                    showStatus(context.getString(R.string.status_error, e.message ?: "?"))
                }
            }
        }
    }

    private fun onLockClicked() {
        val service = LensAccessibilityService.instance
        if (service == null) {
            showStatus(getStringCtx(R.string.lock_enable_first), sticky = true)
            try {
                startAssistantActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                // fall through; user can enable it from the app
            }
            return
        }
        service.setLockEnabled(true)
        Toast.makeText(context, R.string.lock_active, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun viewToBitmap(): android.graphics.Matrix? {
        val bmp = shotView?.bitmap() ?: return null
        val sv = shotView ?: return null
        val m = FitTransform.matrix(sv.width, sv.height, bmp.width, bmp.height)
        val inv = android.graphics.Matrix()
        m.invert(inv)
        return inv
    }

    /** Tap on the frozen screen: pick the smallest detected block under the finger. */
    private fun onTap(x: Float, y: Float) {
        val bmp = shotView?.bitmap() ?: return
        val inv = viewToBitmap() ?: return
        val pt = floatArrayOf(x, y)
        inv.mapPoints(pt)
        val bx = pt[0]
        val by = pt[1]

        val hit = allBoxes
            .filter { it.rect.contains(bx.toInt(), by.toInt()) }
            .minByOrNull { it.rect.width() * it.rect.height() }
            ?: allBoxes
                .filter { inflated(it.rect, 24f).contains(bx, by) }
                .minByOrNull { it.rect.width() * it.rect.height() }

        if (hit == null) {
            // Tapped empty space: clear any selection.
            selectedBoxes = emptyList()
            translations?.setSelection(emptyList())
            resultCard?.visibility = View.GONE
            return
        }
        selectBoxes(listOf(hit))
    }

    private fun inflated(r: Rect, d: Float) =
        RectF(r.left - d, r.top - d, r.right + d, r.bottom + d)

    /** Drag-select: every detected block intersecting the rect; OCR the crop as fallback. */
    private fun onRegionSelected(viewRect: RectF) {
        val bmp = shotView?.bitmap() ?: return
        val inv = viewToBitmap() ?: return
        val bmpRectF = RectF()
        inv.mapRect(bmpRectF, viewRect)
        val r = Rect(
            maxOf(0, bmpRectF.left.toInt()),
            maxOf(0, bmpRectF.top.toInt()),
            minOf(bmp.width, bmpRectF.right.toInt() + 1),
            minOf(bmp.height, bmpRectF.bottom.toInt() + 1)
        )
        if (r.width() < 8 || r.height() < 8) return

        val inside = allBoxes.filter { RectF.intersects(RectF(it.rect), bmpRectF) }
        if (inside.isNotEmpty()) {
            selectBoxes(inside)
        } else {
            // Region missed by the full-screen OCR: run OCR on the crop.
            runOcrFallback(Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height()), r)
        }
    }

    private fun runOcrFallback(crop: Bitmap, offset: Rect) {
        showStatus(getStringCtx(R.string.status_working), sticky = true)
        scope.launch(Dispatchers.Default) {
            try {
                val (blocks, script) = ocr.ocr(crop, Prefs.sourceLang(context))
                if (script == "ja") scriptHint = "ja"
                val boxes = blocks.mapNotNull { block ->
                    val r = block.boundingBox ?: return@mapNotNull null
                    if (block.text.isBlank()) return@mapNotNull null
                    OcrTranslator.TextBox(
                        Rect(r).apply { offset(offset.left, offset.top) },
                        block.text.trim(), null
                    )
                }
                withContext(Dispatchers.Main) {
                    showStatus(null)
                    if (boxes.isEmpty()) {
                        showStatus(getStringCtx(R.string.status_no_text))
                    } else {
                        selectBoxes(boxes)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showStatus(context.getString(R.string.status_error, e.message ?: "?"))
                }
            }
        }
    }

    private fun selectBoxes(boxes: List<OcrTranslator.TextBox>) {
        selectedBoxes = boxes
        translations?.setSelection(boxes.map { RectF(it.rect) })
        showResultCard(joinedText(boxes))
    }

    private fun joinedText(boxes: List<OcrTranslator.TextBox>): String =
        boxes.joinToString(" ") { it.original }.replace('\n', ' ').trim()

    /** Banner "Cerca": search the selected text, or everything on screen. */
    private fun onSearchClicked() {
        val text = when {
            selectedBoxes.isNotEmpty() -> joinedText(selectedBoxes)
            allBoxes.isNotEmpty() -> joinedText(allBoxes)
            else -> {
                if (shotView?.bitmap() == null) {
                    showStatus(getStringCtx(R.string.status_no_screenshot), sticky = true)
                } else if (ocrDone) {
                    showStatus(getStringCtx(R.string.status_no_text))
                } else {
                    showStatus(getStringCtx(R.string.status_scanning))
                }
                return
            }
        }
        showResultCard(text)
        openGoogleSearch(text)
    }

    /** Banner "Traduci": translate the selection, or the whole screen. */
    private fun onTranslateClicked() {
        val bmp = shotView?.bitmap()
        if (bmp == null) {
            showStatus(getStringCtx(R.string.status_no_screenshot), sticky = true)
            return
        }
        val targets = selectedBoxes.ifEmpty { allBoxes }
        if (targets.isEmpty()) {
            showStatus(
                getStringCtx(
                    if (ocrDone) R.string.status_no_text else R.string.status_scanning
                )
            )
            return
        }
        resultCard?.visibility = View.GONE
        showStatus(getStringCtx(R.string.status_working), sticky = true)
        scope.launch(Dispatchers.Default) {
            translateAndRender(targets, bmp)
        }
    }

    private fun showResultCard(text: String) {
        showStatus(null)
        val card = resultCard ?: return
        card.visibility = View.VISIBLE
        card.findViewById<TextView>(R.id.resultText)?.text = text
    }

    private fun searchTextOnGoogle() {
        val text = resultCard?.findViewById<TextView>(R.id.resultText)?.text?.toString() ?: return
        openGoogleSearch(text)
    }

    private fun openGoogleSearch(text: String) {
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8")
        try {
            startAssistantActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            showStatus(context.getString(R.string.status_error, e.message ?: "?"))
        }
    }

    private fun copyResultText() {
        val text = resultCard?.findViewById<TextView>(R.id.resultText)?.text?.toString() ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("lente", text))
        showStatus(getStringCtx(R.string.result_copied))
    }

    /** "Translate this text" on the result card: translate the current selection. */
    private fun translateSelection() {
        val bmp = shotView?.bitmap() ?: return
        if (selectedBoxes.isEmpty()) return
        resultCard?.visibility = View.GONE
        showStatus(getStringCtx(R.string.status_working), sticky = true)
        scope.launch(Dispatchers.Default) {
            translateAndRender(selectedBoxes, bmp)
        }
    }

    private suspend fun translateAndRender(
        boxes: List<OcrTranslator.TextBox>,
        bmp: Bitmap
    ) {
        try {
            val src = ocr.detectSourceTexts(
                boxes.map { it.original }, scriptHint, Prefs.sourceLang(context)
            )
            val dst = Prefs.targetLang(context)
            val translated = ocr.translateBoxes(boxes, src, dst)
            val rendered = translated.mapNotNull { box ->
                val t = box.translated?.trim() ?: return@mapNotNull null
                if (t.isEmpty()) return@mapNotNull null
                TranslationLayerView.Box(
                    RectF(box.rect), t,
                    android.graphics.Color.parseColor("#EE111827"),
                    android.graphics.Color.WHITE
                )
            }
            android.util.Log.i("Lente", "rendered boxes=" + rendered.size)
            withContext(Dispatchers.Main) {
                showStatus(null)
                translations?.setContent(bmp.width, bmp.height, rendered)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                if (e.message?.contains("model", ignoreCase = true) == true) {
                    showStatus(getStringCtx(R.string.status_downloading), sticky = true)
                } else {
                    showStatus(context.getString(R.string.status_error, e.message ?: "?"))
                }
            }
        }
    }
}
