package com.androbs.lens.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * High-accuracy OCR & Neural Translation pipeline.
 * Uses Google Neural Translate endpoint when online (accurate, natural Italian, sentence-level),
 * with automatic fallback to ML Kit offline models.
 */
class OcrTranslator {

    data class TextBox(val rect: Rect, val original: String, val translated: String?)

    private val latin by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val japanese by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val langId by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build()
        )
    }
    private val offlineTranslators = HashMap<String, Translator>()

    /** Returns OCR blocks and script hint. */
    suspend fun ocr(bitmap: Bitmap, sourcePref: String): Pair<List<Text.TextBlock>, String> {
        val image = InputImage.fromBitmap(bitmap, 0)
        return when (sourcePref) {
            "ja" -> japanese.process(image).await().textBlocks to "ja"
            "auto" -> {
                val ja = japanese.process(image).await()
                val la = latin.process(image).await()
                if (ja.text.trim().length >= la.text.trim().length) {
                    ja.textBlocks to "ja"
                } else {
                    la.textBlocks to "latin"
                }
            }
            else -> latin.process(image).await().textBlocks to "latin"
        }.also {
            Log.i("Lente", "OCR detected blocks=" + it.first.size + " script=" + it.second)
        }
    }

    suspend fun detectSource(
        blocks: List<Text.TextBlock>,
        scriptHint: String,
        sourcePref: String
    ): String = detectSourceTexts(blocks.map { it.text }, scriptHint, sourcePref)

    suspend fun detectSourceTexts(
        texts: List<String>,
        scriptHint: String,
        sourcePref: String
    ): String {
        if (sourcePref != "auto") return sourcePref
        val sample = texts.joinToString(" ").replace('\n', ' ').take(250)
        val detected = if (sample.isBlank()) null else try {
            langId.identifyLanguage(sample).await()
        } catch (e: Exception) {
            null
        }
        return when {
            detected.isNullOrBlank() || detected == "und" ->
                if (scriptHint == "ja") "ja" else "en"
            TranslateLanguage.fromLanguageTag(detected) != null -> detected
            else -> if (scriptHint == "ja") "ja" else "en"
        }.also { Log.i("Lente", "detectSource=" + it) }
    }

    /**
     * Translates a single text using Google Neural translation (fast, fluent, natural context).
     */
    private suspend fun translateGoogleOnline(text: String, source: String, target: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val sl = if (source == "auto" || source.isBlank()) "auto" else source
                val tl = if (target.isBlank()) "it" else target
                val encoded = URLEncoder.encode(text, "UTF-8")
                val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=$encoded")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                val res = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONArray(res)
                val sentences = root.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    sb.append(sentences.getJSONArray(i).getString(0))
                }
                val out = sb.toString().trim()
                if (out.isNotEmpty()) out else null
            } catch (e: Exception) {
                Log.w("Lente", "Google online translate error: ${e.message}")
                null
            }
        }
    }

    private suspend fun translateOfflineMlKit(text: String, source: String, target: String): String? {
        return try {
            val key = "$source>$target"
            var t = offlineTranslators[key]
            if (t == null) {
                val src = TranslateLanguage.fromLanguageTag(source) ?: return null
                val dst = TranslateLanguage.fromLanguageTag(target) ?: return null
                t = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
                t.downloadModelIfNeeded().await()
                offlineTranslators[key] = t
            }
            t.translate(text).await()
        } catch (e: Exception) {
            Log.w("Lente", "MLKit offline translate error: ${e.message}")
            null
        }
    }

    suspend fun translateBlocks(
        blocks: List<Text.TextBlock>,
        sourceLang: String,
        targetLang: String
    ): List<TextBox> {
        val usable = blocks.filter { it.boundingBox != null && it.text.isNotBlank() }
        if (usable.isEmpty()) return emptyList()

        val boxes = usable.map { block ->
            // Clean text: join wrapped lines into clean sentence
            val cleanText = block.text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(if (sourceLang == "ja") "" else " ")
            TextBox(block.boundingBox!!, cleanText, null)
        }
        return translateBoxes(boxes, sourceLang, targetLang)
    }

    /** Translates the `original` text of each box, preserving rects. */
    suspend fun translateBoxes(
        boxes: List<TextBox>,
        sourceLang: String,
        targetLang: String
    ): List<TextBox> {
        return boxes.map { box ->
            val translated = if (sourceLang == targetLang) {
                box.original
            } else {
                // Try Google Online first (natural sentence translate), fallback to offline
                translateGoogleOnline(box.original, sourceLang, targetLang)
                    ?: translateOfflineMlKit(box.original, sourceLang, targetLang)
                    ?: box.original
            }
            box.copy(translated = translated)
        }
    }
}
