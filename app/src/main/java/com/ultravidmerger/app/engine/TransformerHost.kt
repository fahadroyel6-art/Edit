package com.ultravidmerger.app.engine

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import com.ultravidmerger.app.diag.DiagnosticLogger

/**
 * MODULE 4 — the fix for "Transformer is accessed on the wrong thread."
 *
 * Media3's Transformer is single-threaded: the instance, every start/cancel call
 * and every listener callback must live on one and the same Looper. This object
 * owns that Looper. No other part of the app is allowed to touch a Transformer
 * directly — everything goes through [post] / [sync].
 */
object TransformerHost {

    private const val TAG = "TransformerHost"

    private val thread = HandlerThread(
        "uvm-transformer",
        Process.THREAD_PRIORITY_BACKGROUND
    ).apply { start() }

    private val handler = Handler(thread.looper)
    private val ownerThreadId: Long = thread.id

    init {
        // Warm the looper so the first real job does not pay startup cost.
        handler.post { DiagnosticLogger.info(TAG, "Transformer looper ready (tid=$ownerThreadId)") }
    }

    val looper: Looper get() = thread.looper

    /** Runs [block] on the owning looper. Safe from any thread. */
    fun post(block: () -> Unit) {
        if (Thread.currentThread().id == ownerThreadId) block() else handler.post(block)
    }

    /** Runs [block] on the owning looper and blocks for the result (engine-internal use only). */
    @Suppress("UNCHECKED_CAST")
    fun <T> sync(block: () -> T): T {
        if (Thread.currentThread().id == ownerThreadId) return block()

        val holder = arrayOfNulls<Any?>(1)
        val error = arrayOfNulls<Throwable>(1)
        val latch = java.util.concurrent.CountDownLatch(1)

        handler.post {
            try {
                holder[0] = block()
            } catch (t: Throwable) {
                error[0] = t
            } finally {
                latch.countDown()
            }
        }

        latch.await()
        error[0]?.let { throw it }
        return holder[0] as T
    }

    /** Throws if the caller is not on the Transformer thread — used in asserts/tests. */
    fun assertOwnerThread() {
        check(Thread.currentThread().id == ownerThreadId) {
            "Transformer accessed on the wrong thread: expected tid=$ownerThreadId, actual=${Thread.currentThread().id}"
        }
    }

    fun isOwnerThread(): Boolean = Thread.currentThread().id == ownerThreadId
}
