package com.emotionalleaves.cartwise.taste

import android.content.Context
import com.emotionalleaves.cartwise.rank.Words
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder

/**
 * On-device text embeddings with MediaPipe Text Embedder (Universal Sentence Encoder).
 * If the model file is missing or fails to load, falls back to a hashed bag-of-words
 * vector so the taste model still works, just less cleverly.
 */
class Embedder(private val context: Context) {
    companion object {
        const val MODEL = "universal_sentence_encoder.tflite"
        const val HASH_DIM = 512
    }

    private var mp: TextEmbedder? = null
    private var tried = false

    @Volatile var usingModel = false
        private set

    /** Why the on-device model didn't load, shown in Settings so it can be fixed. */
    @Volatile var loadError: String? = null
        private set

    @Synchronized
    private fun init() {
        if (tried) return
        tried = true
        try {
            val has = context.assets.list("")?.contains(MODEL) == true
            if (!has) loadError = null
            if (has) {
                val base = BaseOptions.builder().setModelAssetPath(MODEL).build()
                val opts = TextEmbedder.TextEmbedderOptions.builder()
                    .setBaseOptions(base)
                    .setL2Normalize(true)
                    .build()
                mp = TextEmbedder.createFromOptions(context, opts)
                usingModel = true
            }
        } catch (t: Throwable) {
            mp = null
            usingModel = false
            loadError = (t.javaClass.simpleName + ": " + (t.message ?: "")).take(200)
        }
    }

    @Synchronized
    fun embed(text: String): FloatArray {
        init()
        val m = mp
        if (m != null) {
            try {
                val e = m.embed(text.take(500)).embeddingResult().embeddings()[0].floatEmbedding()
                if (e.isNotEmpty()) return Vec.normalize(e)
            } catch (_: Throwable) {
            }
        }
        return hash(text)
    }

    val name: String
        get() {
            init()
            return when {
                usingModel -> "MediaPipe Universal Sentence Encoder"
                loadError != null -> "Built-in word hashing (model failed: $loadError)"
                else -> "Built-in word hashing (model not bundled)"
            }
        }

    private fun hash(text: String): FloatArray {
        val v = FloatArray(HASH_DIM)
        for (t in Words.contentTokens(text)) {
            val s = Words.stem(t)
            add(v, s, 1f)
            val p = "#$s#"
            for (i in 0..p.length - 3) add(v, p.substring(i, i + 3), 0.35f)
        }
        return Vec.normalize(v)
    }

    private fun add(v: FloatArray, s: String, w: Float) {
        val h = s.hashCode()
        val idx = Math.floorMod(h, HASH_DIM)
        val sign = if ((h ushr 31) == 0) 1f else -1f
        v[idx] += sign * w
    }
}
