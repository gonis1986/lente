package com.androbs.lens.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Color
import android.util.LruCache
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * High-accuracy OCR & Neural Translation pipeline.
 * Uses Google Neural Translate endpoint when online (accurate, natural Italian, sentence-level),
 * with automatic fallback to ML Kit offline models.
 */
class OcrTranslator {

    data class TextBox(val rect: Rect, val original: String, val translated: String?)

    data class BalloonCluster(
        val id: String,
        val outerBounds: RectF,
        val lineRects: List<RectF>,
        val sourceText: String,
        val isVertical: Boolean,
        var translatedText: String = "",
        var bgColor: Int = Color.WHITE,
        var textColor: Int = Color.BLACK,
        var strokeHaloColor: Int = Color.WHITE,
        var widenVertically: Boolean = false
    )

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
    private val unavailableOfflineModels = HashSet<String>()
    private val translationCache = LruCache<String, String>(500)

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
        val key = "$source>$target"
        if (key in unavailableOfflineModels) return null
        val translated = withTimeoutOrNull(8_000L) {
            try {
                var t = offlineTranslators[key]
                if (t == null) {
                    val src = TranslateLanguage.fromLanguageTag(source) ?: return@withTimeoutOrNull null
                    val dst = TranslateLanguage.fromLanguageTag(target) ?: return@withTimeoutOrNull null
                    t = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
                    t.downloadModelIfNeeded().await()
                    offlineTranslators[key] = t
                }
                t.translate(text).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Lente", "MLKit offline translate error: ${e.message}")
                null
            }
        }
        if (translated == null) {
            unavailableOfflineModels += key
            Log.w("Lente", "MLKit offline translation unavailable for $key")
        }
        return translated
    }

    fun clusterBlocks(blocks: List<Text.TextBlock>): List<BalloonCluster> {
        val lines = blocks.flatMap { block ->
            val blockLines = block.lines
            if (blockLines.isEmpty()) {
                val bounds = block.boundingBox ?: return@flatMap emptyList()
                listOf(
                    OcrLine(
                        block.text,
                        Box(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
                    )
                )
            } else {
                blockLines.mapNotNull { line ->
                    val elements = line.elements.mapNotNull { element ->
                        val bounds = element.boundingBox ?: return@mapNotNull null
                        OcrElement(
                            element.text,
                            Box(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
                        )
                    }
                    val bounds = line.boundingBox ?: elements.bounds()
                        ?: return@mapNotNull null
                    OcrLine(
                        line.text,
                        Box(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()),
                        elements
                    )
                }
            }
        }
        return MangaBalloonClusterer.cluster(lines).map { cluster ->
            BalloonCluster(
                cluster.id,
                RectF(
                    cluster.outerBounds.l,
                    cluster.outerBounds.t,
                    cluster.outerBounds.r,
                    cluster.outerBounds.b
                ),
                cluster.lineRects.map { RectF(it.l, it.t, it.r, it.b) },
                cluster.sourceText,
                cluster.isVertical
            )
        }.also { clusters ->
            Log.i("Lente", "cluster count=${clusters.size}")
            Log.i("Lente", "column order texts=${clusters.joinToString(" | ") { it.sourceText }}")
        }
    }

    private fun List<OcrElement>.bounds(): Rect? {
        if (isEmpty()) return null
        return Rect(
            minOf { it.box.l }.toInt(),
            minOf { it.box.t }.toInt(),
            maxOf { it.box.r }.toInt(),
            maxOf { it.box.b }.toInt()
        )
    }

    suspend fun translateClusters(
        clusters: List<BalloonCluster>,
        sourceLang: String,
        targetLang: String
    ): List<BalloonCluster> {
        if (clusters.isEmpty()) return emptyList()
        if (sourceLang == targetLang) {
            return clusters.map { it.copy(translatedText = it.sourceText) }
        }

        val translated = arrayOfNulls<String>(clusters.size)
        val misses = mutableListOf<Int>()
        clusters.forEachIndexed { index, cluster ->
            val key = cacheKey(sourceLang, targetLang, cluster.sourceText)
            val cached = synchronized(translationCache) { translationCache.get(key) }
            if (cached != null) {
                Log.i("Lente", "translation cache hit id=${cluster.id}")
                translated[index] = cached
            } else {
                misses += index
            }
        }

        val chunks = mutableListOf<List<Int>>()
        var pending = mutableListOf<Int>()
        for (index in misses) {
            val candidate = pending + index
            if (pending.isNotEmpty() && formBodyLength(batchPayload(candidate.map { clusters[it].sourceText })) > MAX_BATCH_BODY_CHARS) {
                chunks += pending
                pending = mutableListOf(index)
            } else {
                pending += index
            }
        }
        if (pending.isNotEmpty()) chunks += pending

        var requestCount = 0
        for (chunk in chunks) {
            val texts = chunk.map { clusters[it].sourceText }
            var batch: List<String>? = null
            if (formBodyLength(batchPayload(texts)) <= MAX_BATCH_BODY_CHARS) {
                requestCount++
                batch = translateBatch(texts, sourceLang, targetLang)
            }
            if (batch != null && batch.size == chunk.size) {
                chunk.forEachIndexed { localIndex, clusterIndex ->
                    translated[clusterIndex] = batch[localIndex]
                    putCached(sourceLang, targetLang, clusters[clusterIndex].sourceText, batch[localIndex])
                }
            } else {
                chunk.forEach { clusterIndex ->
                    val original = clusters[clusterIndex].sourceText
                    val result = translateGoogleOnline(original, sourceLang, targetLang)
                        ?: translateOfflineMlKit(original, sourceLang, targetLang)
                        ?: original
                    translated[clusterIndex] = result
                    putCached(sourceLang, targetLang, original, result)
                }
            }
        }
        Log.i("Lente", "batch requests=$requestCount, cache hits=${clusters.size - misses.size}")
        return clusters.mapIndexed { index, cluster ->
            cluster.copy(translatedText = translated[index] ?: cluster.sourceText)
        }
    }

    suspend fun translateBlocks(
        blocks: List<Text.TextBlock>,
        sourceLang: String,
        targetLang: String
    ): List<TextBox> = translateClusters(clusterBlocks(blocks), sourceLang, targetLang).map {
        TextBox(
            Rect(
                it.outerBounds.left.toInt(),
                it.outerBounds.top.toInt(),
                it.outerBounds.right.toInt(),
                it.outerBounds.bottom.toInt()
            ),
            it.sourceText,
            it.translatedText
        )
    }

    /** Legacy adapter retained for crop-selection clients. */
    suspend fun translateBoxes(
        boxes: List<TextBox>,
        sourceLang: String,
        targetLang: String
    ): List<TextBox> {
        val clusters = boxes.mapIndexed { index, box ->
            BalloonCluster(
                "box-$index",
                RectF(box.rect),
                listOf(RectF(box.rect)),
                box.original,
                false
            )
        }
        return translateClusters(clusters, sourceLang, targetLang).mapIndexed { index, cluster ->
            boxes[index].copy(translated = cluster.translatedText)
        }
    }

    private suspend fun translateBatch(texts: List<String>, source: String, target: String): List<String>? {
        if (texts.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val sl = if (source == "auto" || source.isBlank()) "auto" else source
                val tl = if (target.isBlank()) "it" else target
                val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t")
                val body = "q=" + URLEncoder.encode(batchPayload(texts), "UTF-8")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                BatchMarkerParser.parse(translatedText(response), texts.size)
            } catch (e: Exception) {
                Log.w("Lente", "Google batch translate error: ${e.message}")
                null
            }
        }
    }

    private fun translatedText(response: String): String? {
        return try {
            val sentences = JSONArray(response).getJSONArray(0)
            buildString {
                for (i in 0 until sentences.length()) {
                    val sentence = sentences.optJSONArray(i) ?: continue
                    if (!sentence.isNull(0)) append(sentence.optString(0))
                }
            }
        } catch (e: Exception) {
            Log.w("Lente", "Google batch response parse error: ${e.message}")
            null
        }
    }

    private fun batchPayload(texts: List<String>): String =
        texts.mapIndexed { index, text -> "[[$index]] $text" }.joinToString("\n")

    private fun formBodyLength(payload: String): Int =
        2 + URLEncoder.encode(payload, "UTF-8").length

    private fun cacheKey(source: String, target: String, fullText: String): String =
        "$source:$target:$fullText"

    private fun putCached(source: String, target: String, text: String, translated: String) {
        synchronized(translationCache) {
            translationCache.put(cacheKey(source, target, text), translated)
        }
    }

    companion object {
        private const val MAX_BATCH_BODY_CHARS = 4500
    }
}

/** Pure parser so numbered batch association can be tested on the JVM. */
object BatchMarkerParser {
    private val marker = Pattern.compile("\\[\\[(\\d+)\\]\\]")

    fun parse(text: String?, expectedSegments: Int): List<String>? {
        if (text == null || expectedSegments < 0) return null
        if (expectedSegments == 0) return if (text.isBlank()) emptyList() else null
        val matcher = marker.matcher(text)
        val matches = mutableListOf<Pair<Int, IntRange>>()
        while (matcher.find()) {
            val index = matcher.group(1)?.toIntOrNull() ?: return null
            matches += index to (matcher.start() until matcher.end())
        }
        if (matches.size != expectedSegments) return null
        if (text.substring(0, matches.first().second.first).isNotBlank()) return null
        if (matches.indices.any { matches[it].first != it }) return null
        val out = matches.indices.map { index ->
            val contentStart = matches[index].second.last + 1
            val contentEnd = if (index + 1 < matches.size) matches[index + 1].second.first else text.length
            text.substring(contentStart, contentEnd).trim()
        }
        return out.takeIf { values -> values.none { it.isEmpty() } }
    }
}
