package ir.diban.android

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide connection state shared by the service (writer) and the UI (reader). */
object Session {
    enum class State { IDLE, CONNECTING, CONNECTED }
    enum class Tone { NORMAL, MUTED, GOOD, WARN, BAD }

    @Volatile var state: State = State.IDLE
        private set
    @Volatile var status: String = ""
        private set
    @Volatile var tone: Tone = Tone.MUTED
        private set
    @Volatile var connectedAtMs: Long = 0L
        private set
    /** true = system-wide VPN, false = local proxy only. */
    @Volatile var vpnMode: Boolean = true

    /** True when the last probe through the relay failed (shows the troubleshooting hint). */
    @Volatile var probeFailed: Boolean = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun set(state: State? = null, status: String? = null, tone: Tone? = null, probeFailed: Boolean? = null) {
        if (state != null) {
            if (state == State.CONNECTED && this.state != State.CONNECTED) connectedAtMs = System.currentTimeMillis()
            if (state != State.CONNECTED) connectedAtMs = 0L
            this.state = state
        }
        if (status != null) this.status = status
        if (tone != null) this.tone = tone
        if (probeFailed != null) this.probeFailed = probeFailed
        main.post { listeners.forEach { it() } }
    }
}
