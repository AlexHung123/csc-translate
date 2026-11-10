package com.csc.translate.session

import android.util.Log
import com.csc.translate.net.RelayClient
import com.csc.translate.net.RelayPicture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One room for the current session. Pushes are coalesced, and a relay
 * failure never stops translation. [begin] and [finish] run on the main thread.
 */
class RelayShare(
    private val client: RelayClient,
    private val scope: CoroutineScope,
) {
    var onWatchUrl: (String) -> Unit = {}
    var onOpenFailed: () -> Unit = {}

    private val gate = Any()
    private val loop = Any()
    private val pending = AtomicBoolean(false)
    private val latest = AtomicReference<RelayPicture?>(null)
    private var generation = 0
    private var accepting = false
    private var room: String? = null
    private var meetingId: String = ""
    private var passcode: String = ""
    private var boundBase: String = ""
    private var base: String = ""
    private var warned = false
    private var retryAt = 0L
    private var looping = false

    fun begin() {
        synchronized(gate) {
            generation += 1
            accepting = true
            room = null
            meetingId = ""
            passcode = ""
            boundBase = ""
            base = ""
            warned = false
            retryAt = 0L
            latest.set(null)
        }
        onWatchUrl("")
    }

    /** Stop opening and updating. A later [sync] does nothing until [resume]. */
    fun halt() {
        synchronized(gate) {
            accepting = false
            latest.set(null)
        }
    }

    /** Allow pushes again after [halt]. */
    fun resume() {
        synchronized(gate) {
            if (accepting) return
            accepting = true
            warned = false
            retryAt = 0L
        }
    }

    fun sync(baseUrl: String, meetingId: String, passcode: String, picture: RelayPicture) {
        val url = baseUrl.trim().trimEnd('/')
        val id = meetingId.trim()
        synchronized(gate) {
            if (!accepting || url.isBlank() || id.isBlank()) return
            base = url
            this.meetingId = id
            this.passcode = passcode
            latest.set(picture)
        }
        wake()
    }

    fun finish(baseUrl: String, passcode: String, picture: RelayPicture) {
        val idle = picture.copy(phase = "idle", paused = false, status = "")
        val roomNow: String?
        val bound: String
        synchronized(gate) {
            generation += 1
            accepting = false
            latest.set(null)
            roomNow = room
            bound = boundBase.ifBlank { baseUrl.trim().trimEnd('/') }
            room = null
            boundBase = ""
            base = ""
        }
        onWatchUrl("")
        if (roomNow != null && bound.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                runCatching { client.update(bound, roomNow, passcode, idle) }
                    .onFailure { Log.w(TAG, "relay close", it) }
            }
        }
    }

    private fun wake() {
        pending.set(true)
        synchronized(loop) {
            if (looping) return
            looping = true
        }
        scope.launch(Dispatchers.IO) {
            try {
                while (pending.getAndSet(false)) {
                    runCatching { push() }.onFailure { Log.w(TAG, "relay", it) }
                }
            } finally {
                val again = synchronized(loop) {
                    if (pending.get()) {
                        true
                    } else {
                        looping = false
                        false
                    }
                }
                if (again) {
                    synchronized(loop) { looping = false }
                    wake()
                }
            }
        }
    }

    private fun push() {
        val gen: Int
        val picture: RelayPicture
        val url: String
        val existing: String?
        val bound: String
        val wanted: String
        val code: String
        synchronized(gate) {
            if (!accepting) return
            gen = generation
            picture = latest.get() ?: return
            url = base
            existing = room
            bound = boundBase
            wanted = meetingId
            code = passcode
        }
        if (url.isBlank() || wanted.isBlank() || picture.phase == "idle") return
        if (existing == null) {
            val now = System.currentTimeMillis()
            if (now < retryAt) return
            val opened = try {
                client.open(url, wanted, code, picture)
            } catch (error: Throwable) {
                retryAt = System.currentTimeMillis() + RETRY_MS
                val tell = synchronized(gate) {
                    val first = accepting && !warned
                    if (first) warned = true
                    first
                }
                if (tell) onOpenFailed()
                Log.w(TAG, "relay open", error)
                scope.launch {
                    delay(RETRY_MS)
                    wake()
                }
                return
            }
            val adopted = synchronized(gate) {
                if (!accepting || gen != generation) {
                    false
                } else {
                    room = opened.id
                    boundBase = url
                    true
                }
            }
            if (!adopted) {
                runCatching {
                    client.update(url, opened.id, code, picture.copy(phase = "idle", paused = false, status = ""))
                }
                return
            }
            onWatchUrl(opened.watchUrl)
            return
        }
        val stillCurrent = synchronized(gate) { accepting && gen == generation && room == existing }
        if (!stillCurrent) return
        try {
            client.update(bound.ifBlank { url }, existing, code, picture)
        } catch (error: Throwable) {
            Log.w(TAG, "relay update", error)
            if (error.message?.contains("404") == true) {
                synchronized(gate) {
                    if (room == existing) {
                        room = null
                        boundBase = ""
                    }
                }
                wake()
            }
        }
        val ended = synchronized(gate) { gen != generation }
        if (ended) {
            runCatching {
                client.update(
                    bound.ifBlank { url },
                    existing,
                    code,
                    picture.copy(phase = "idle", paused = false, status = ""),
                )
            }
        }
    }

    private companion object {
        const val TAG = "CscTranslate"
        const val RETRY_MS = 3_000L
    }
}
