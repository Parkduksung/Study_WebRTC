/*
 *  Copyright 2015 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package com.example.studywebrtc.webrtc

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ThreadUtils {
    /**
     * Throws exception if called from other than main thread.
     */
    fun checkIsOnMainThread() {
        check(!(Thread.currentThread() !== Looper.getMainLooper().thread)) { "Not on main thread!" }
    }

    /**
     * Utility method to make sure a blocking operation is executed to completion without getting
     * interrupted. This should be used in cases where the operation is waiting for some critical
     * work, e.g. cleanup, that must complete before returning. If the thread is interrupted during
     * the blocking operation, this function will re-run the operation until completion, and only then
     * re-interrupt the thread.
     */
    fun executeUninterruptibly(operation: BlockingOperation) {
        var wasInterrupted = false
        while (true) {
            wasInterrupted = try {
                operation.run()
                break
            } catch (e: InterruptedException) {
                // Someone is asking us to return early at our convenience. We can't cancel this operation,
                // but we should preserve the information and pass it along.
                true
            }
        }
        // Pass interruption information along.
        if (wasInterrupted) {
            Thread.currentThread().interrupt()
        }
    }

    fun joinUninterruptibly(thread: Thread, timeoutMs: Long): Boolean {
        val startTimeMs = SystemClock.elapsedRealtime()
        var timeRemainingMs = timeoutMs
        var wasInterrupted = false
        while (timeRemainingMs > 0) {
            try {
                thread.join(timeRemainingMs)
                break
            } catch (e: InterruptedException) {
                // Someone is asking us to return early at our convenience. We can't cancel this operation,
                // but we should preserve the information and pass it along.
                wasInterrupted = true
                val elapsedTimeMs = SystemClock.elapsedRealtime() - startTimeMs
                timeRemainingMs = timeoutMs - elapsedTimeMs
            }
        }
        // Pass interruption information along.
        if (wasInterrupted) {
            Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    fun joinUninterruptibly(thread: Thread) {
        executeUninterruptibly(object : BlockingOperation {
            @Throws(InterruptedException::class)
            override fun run() {
                thread.join()
            }
        })
    }

    fun awaitUninterruptibly(latch: CountDownLatch) {
        executeUninterruptibly(object : BlockingOperation {
            @Throws(InterruptedException::class)
            override fun run() {
                latch.await()
            }
        })
    }

    fun awaitUninterruptibly(barrier: CountDownLatch, timeoutMs: Long): Boolean {
        val startTimeMs = SystemClock.elapsedRealtime()
        var timeRemainingMs = timeoutMs
        var wasInterrupted = false
        var result = false
        do {
            try {
                result = barrier.await(timeRemainingMs, TimeUnit.MILLISECONDS)
                break
            } catch (e: InterruptedException) {
                // Someone is asking us to return early at our convenience. We can't cancel this operation,
                // but we should preserve the information and pass it along.
                wasInterrupted = true
                val elapsedTimeMs = SystemClock.elapsedRealtime() - startTimeMs
                timeRemainingMs = timeoutMs - elapsedTimeMs
            }
        } while (timeRemainingMs > 0)
        // Pass interruption information along.
        if (wasInterrupted) {
            Thread.currentThread().interrupt()
        }
        return result
    }

    /**
     * Post `callable` to `handler` and wait for the result.
     */
    fun <V> invokeAtFrontUninterruptibly(
        handler: Handler, callable: Callable<V>
    ): V? {
        if (handler.looper.thread === Thread.currentThread()) {
            return try {
                callable.call()
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
        // Place-holder classes that are assignable inside nested class.
        class CaughtException {
            var e: Exception? = null
        }

        class Result {
            var value: V? = null
        }

        val result = Result()
        val caughtException = CaughtException()
        val barrier = CountDownLatch(1)
        handler.post {
            try {
                result.value = callable.call()
            } catch (e: Exception) {
                caughtException.e = e
            }
            barrier.countDown()
        }
        awaitUninterruptibly(barrier)
        // Re-throw any runtime exception caught inside the other thread. Since this is an invoke, add
        // stack trace for the waiting thread as well.
        if (caughtException.e != null) {
            val runtimeException = RuntimeException(caughtException.e)
            runtimeException.stackTrace =
                concatStackTraces(caughtException.e!!.stackTrace, runtimeException.stackTrace)
            throw runtimeException
        }
        return result.value ?: null
    }

    /**
     * Post `runner` to `handler`, at the front, and wait for completion.
     */
    fun invokeAtFrontUninterruptibly(handler: Handler, runner: Callable<Void?>) {
        invokeAtFrontUninterruptibly(handler, Callable<Void?> {
            runner.run {  }
            null
        })
    }

    fun concatStackTraces(
        inner: Array<StackTraceElement?>, outer: Array<StackTraceElement?>
    ): Array<StackTraceElement?> {
        val combined = arrayOfNulls<StackTraceElement>(inner.size + outer.size)
        System.arraycopy(inner, 0, combined, 0, inner.size)
        System.arraycopy(outer, 0, combined, inner.size, outer.size)
        return combined
    }

    /**
     * Utility class to be used for checking that a method is called on the correct thread.
     */
    class ThreadChecker {
        private var thread = Thread.currentThread()
        fun checkIsOnValidThread() {
            if (thread == null) {
                thread = Thread.currentThread()
            }
            check(!(Thread.currentThread() !== thread)) { "Wrong thread" }
        }

        fun detachThread() {
            thread = null
        }
    }

    /**
     * Utility interface to be used with executeUninterruptibly() to wait for blocking operations
     * to complete without getting interrupted..
     */
    interface BlockingOperation {
        @Throws(InterruptedException::class)
        fun run()
    }
}