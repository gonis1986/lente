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
import com.androbs.lens.ui.RegionSelectView
import com.androbs.lens.ui.ScreenshotView
import com.androbs.lens.ui.TranslationLayerView
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

/**
 * The overlay shown when the user invokes the assistant (long-press Home or
 * corner swipe): captured screen + banner with search / translate / lock.
 */
class LensSession(context: Context) : VoiceInteractionSession(context) {

    private enum class Mode { IDLE, SELECT_SEARCH, SELECT_TRANSLATE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ocr = OcrTranslator()

    private var mode = Mode.IDLE
    private var screenshot: Bitmap? = null
    private var view: View? = null
    private var pendingBitmap: Bitmap? = null
    private var lastRegionBitmap: Bitmap? = null
    private var lastBlocks: List<Text.TextBlock> = emptyList()
    private var lastBlocksOffset = Rect(0, 0, 0, 0)

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
            shotView?.setBitmap(argb(it))
            pendingBitmap = null
        }
        if (shotView?.bitmap() == null && pendingBitmap == null) {
            // Screenshot may still arrive via onHandleScreenshot; wait for it.
            status?.visibility = View.GONE
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
        if (sv == null) pendingBitmap = b else sv.setBitmap(b)
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
        v.findViewById<Button>(R.id.btnSearch).setOnClickListener { enterMode(Mode.SELECT_SEARCH) }
        v.findViewById<Button>(R.id.btnTranslate).setOnClickListener { enterMode(Mode.SELECT_TRANSLATE) }
        v.findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
        v.findViewById<Button>(R.id.btnLock).setOnClickListener { onLockClicked() }
        selector?.onSelected = { rect -> onRegionSelected(rect) }
        v.findViewById<Button>(R.id.btnGoogle).setOnClickListener { searchTextOnGoogle() }
        v.findViewById<Button>(R.id.btnCopy).setOnClickListener { copyResultText() }
        v.findViewById<Button>(R.id.btnTranslateResult).setOnClickListener { translateLastOcr() }
    }

    private fun enterMode(m: Mode) {
        if (shotView?.bitmap() == null) {
            showStatus(getStringCtx(R.string.status_no_screenshot), sticky = true)
            return
        }
        mode = m
        selector?.selectionEnabled = true
        resultCard?.visibility = View.GONE
        hint?.visibility = View.VISIBLE
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

    private fun onRegionSelected(viewRect: RectF) {
        val bmp = shotView?.bitmap() ?: return
        val sv = shotView ?: return
        selector?.selectionEnabled = false
        hint?.visibility = View.GONE
        val m = com.androbs.lens.ui.FitTransform.matrix(sv.width, sv.height, bmp.width, bmp.height)
        val inv = android.graphics.Matrix()
        m.invert(inv)
        val bmpRectF = RectF()
        inv.mapRect(bmpRectF, viewRect)
        val r = Rect(
            max(0, bmpRectF.left.toInt()),
            max(0, bmpRectF.top.toInt()),
            min(bmp.width, bmpRectF.right.toInt() + 1),
            min(bmp.height, bmpRectF.bottom.toInt() + 1)
        )
        if (r.width() < 8 || r.height() < 8) return
        val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
        when (mode) {
            Mode.SELECT_SEARCH -> runOcr(crop, r, forSearch = true)
            Mode.SELECT_TRANSLATE -> runOcr(crop, r, forSearch = false)
            Mode.IDLE -> Unit
        }
        mode = Mode.IDLE
    }

    private fun runOcr(crop: Bitmap, offset: Rect, forSearch: Boolean) {
        showStatus(getStringCtx(R.string.status_working), sticky = true)
        scope.launch(Dispatchers.Default) {
            try {
                val (blocks, script) = ocr.ocr(crop, Prefs.sourceLang(context))
                android.util.Log.i("Lente", "runOcr blocks=" + blocks.size)
                if (blocks.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showStatus(getStringCtx(R.string.status_no_text))
                    }
                    return@launch
                }
                lastRegionBitmap = crop
                lastBlocks = blocks
                lastBlocksOffset = offset
                if (forSearch) {
                    val text = blocks.joinToString(" ") { it.text }.replace('\n', ' ').trim()
                    withContext(Dispatchers.Main) { showResultCard(text) }
                } else {
                    withContext(Dispatchers.Main) {
                        showStatus(getStringCtx(R.string.status_working), sticky = true)
                    }
                    translateAndRender(blocks, offset, crop)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showStatus(context.getString(R.string.status_error, e.message ?: "?"))
                }
            }
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

    private fun translateLastOcr() {
        val crop = lastRegionBitmap ?: return
        if (lastBlocks.isEmpty()) return
        resultCard?.visibility = View.GONE
        showStatus(getStringCtx(R.string.status_working), sticky = true)
        scope.launch(Dispatchers.Default) {
            translateAndRender(lastBlocks, lastBlocksOffset, crop)
        }
    }

    private suspend fun translateAndRender(
        blocks: List<Text.TextBlock>,
        offset: Rect,
        crop: Bitmap
    ) {
        try {
            val src = ocr.detectSource(blocks, "ja", Prefs.sourceLang(context))
            val dst = Prefs.targetLang(context)
            val boxes = ocr.translateBlocks(blocks, src, dst)
            val full = shotView?.bitmap()
            val rendered = boxes.mapNotNull { box ->
                val translated = box.translated ?: return@mapNotNull null
                val shifted = Rect(box.rect).apply { offset(offset.left, offset.top) }
                val (bg, fg) = if (full != null) {
                    TranslationLayerView.estimateColors(full, shifted)
                } else {
                    android.graphics.Color.WHITE to android.graphics.Color.BLACK
                }
                TranslationLayerView.Box(RectF(shifted), translated, bg, fg)
            }
            android.util.Log.i("Lente", "rendered boxes=" + rendered.size)
            withContext(Dispatchers.Main) {
                showStatus(null)
                full?.let { translations?.setContent(it.width, it.height, rendered) }
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
