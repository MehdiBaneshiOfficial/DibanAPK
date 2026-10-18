package ir.diban.android

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.json.JSONObject

/** Typed wrapper over python/diban_bridge.py.  Blocking calls must stay off the main thread. */
object Bridge {
    private val mod: PyObject by lazy { Python.getInstance().getModule("diban_bridge") }

    data class Result(val ok: Boolean, val error: String, val note: String)

    private fun parse(json: String): Result {
        val o = JSONObject(json)
        return Result(o.optBoolean("ok"), o.optString("error"), o.optString("note"))
    }

    fun init(dataDir: String) { mod.callAttr("init", dataDir) }

    fun version(): String = mod.callAttr("version").toString()
    fun hasConfig(): Boolean = mod.callAttr("has_config").toBoolean()
    fun serverCount(): Int = mod.callAttr("server_count").toInt()
    fun socksPort(): Int = JSONObject(mod.callAttr("ports").toString()).getInt("socks")
    fun httpPort(): Int = JSONObject(mod.callAttr("ports").toString()).getInt("http")

    fun codeInText(text: String): Boolean = mod.callAttr("code_in_text", text).toBoolean()
    fun importCode(text: String): Result = parse(mod.callAttr("import_code", text).toString())

    fun extractDeploymentId(text: String): String = mod.callAttr("extract_deployment_id", text).toString()
    fun authKey(): String = mod.callAttr("auth_key").toString()
    fun pendingKey(): String = mod.callAttr("pending_key").toString()
    fun applyAuthKey(template: String, key: String): String =
        mod.callAttr("apply_auth_key", template, key).toString()
    fun saveDeployment(text: String, add: Boolean): Result =
        parse(mod.callAttr("save_deployment", text, add).toString())

    /** Creates the MITM CA if needed (slow the first time) and returns the ca.crt path. */
    fun ensureCa(): String = mod.callAttr("ensure_ca").toString()

    /** "" on success, otherwise a Persian error message. */
    fun start(): String = mod.callAttr("start").toString()
    fun stop() { mod.callAttr("stop") }
    fun isRunning(): Boolean = mod.callAttr("is_running").toBoolean()
    fun probe(): Boolean = mod.callAttr("probe").toBoolean()

    fun log(): String = mod.callAttr("get_log").toString()
    fun resetConfig() { mod.callAttr("reset_config") }
    fun resetAll() { mod.callAttr("reset_all") }
}
