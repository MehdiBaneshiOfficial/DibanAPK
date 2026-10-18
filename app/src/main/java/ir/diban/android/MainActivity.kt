package ir.diban.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import ir.diban.android.Session.State
import ir.diban.android.Session.Tone
import ir.diban.android.WizardSteps.Action
import ir.diban.android.vpn.DibanService
import java.io.File
import java.util.concurrent.Executors

/**
 * Single-activity UI, built in code (no XML layouts): wizard on first run, then one big switch.
 * Every call into Python that can block (start, probe, CA generation) runs on [io].
 */
class MainActivity : AppCompatActivity() {

    private enum class Screen { WIZARD, MAIN, HELP, LOG, CA, IMPORT }

    private class HelpSpec(
        val title: String,
        val items: List<WizardSteps.Item>,
        val tip: String? = null,
        val intro: String? = null,
        val action: Pair<String, () -> Unit>? = null,
        val back: () -> Unit,
    )

    private companion object {
        const val AUTHOR = "مهدی بانشی"
        const val BALE_URL = "https://ble.ir/moinkarami"
        const val TG_URL = "https://t.me/MehdiBaneshiOfficial"
        const val PREF_VPN = "vpn_mode"
        const val PREF_CA_SKIPPED = "ca_skipped"
        const val PREF_NOTIF_ASKED = "notif_asked"
    }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("diban", MODE_PRIVATE) }

    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout

    private var screen = Screen.MAIN
    private var wizardStep = 0
    private var wizardAdd = false
    private var help: HelpSpec? = null
    private var pendingVpn = true

    // references to live views (valid only for the screen that created them)
    private var toggle: ToggleView? = null
    private var stateLabel: TextView? = null
    private var hintLabel: TextView? = null
    private var uptimeLabel: TextView? = null
    private var statusCard: View? = null
    private var statusText: TextView? = null
    private var troubleLink: View? = null
    private var pillVpn: TextView? = null
    private var pillProxy: TextView? = null
    private var modeNote: TextView? = null
    private var actionBtn: TextView? = null
    private var nextBtn: TextView? = null
    private var entry: EditText? = null
    private var entryMsg: TextView? = null
    private var caMsg: TextView? = null

    private val sessionListener: () -> Unit = { updateMain() }

    // ───────────── activity results ─────────────
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            startConnection(true)
        } else {
            Session.set(State.IDLE, getString(R.string.err_vpn_denied), Tone.BAD)
        }
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterNotificationStep()
    }

    private val saveCa = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-x509-ca-cert")) { uri ->
        if (uri != null) {
            io.execute {
                try {
                    val bytes = File(Bridge.ensureCa()).readBytes()
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ui.post { setCaMsg("گواهی ذخیره شد ✓ حالا تنظیمات امنیتی را باز کنید", Palette.GREEN) }
                } catch (e: Exception) {
                    ui.post { setCaMsg("ذخیره‌ی گواهی ناموفق بود\n${e.message}", Palette.RED) }
                }
            }
        }
    }

    // ───────────── lifecycle ─────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(16))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Palette.BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        setContentView(root)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        screen = if (Bridge.hasConfig()) Screen.MAIN else Screen.WIZARD
        render()
    }

    override fun onStart() {
        super.onStart()
        Session.addListener(sessionListener)
        updateMain(animate = false)
    }

    override fun onStop() {
        Session.removeListener(sessionListener)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets the focused app read the clipboard.
        if (hasFocus) autoPasteDeploymentId()
    }

    private fun goBack() {
        when (screen) {
            Screen.MAIN -> finish() // the connection keeps running in its service
            Screen.WIZARD -> {
                val first = if (wizardAdd) 1 else 0
                if (wizardStep > first) {
                    wizardStep--
                    render()
                } else if (Bridge.hasConfig()) {
                    wizardAdd = false; wizardStep = 0
                    show(Screen.MAIN)
                } else {
                    finish()
                }
            }
            Screen.HELP -> (help?.back ?: { show(Screen.MAIN) })()
            else -> show(if (Bridge.hasConfig()) Screen.MAIN else Screen.WIZARD)
        }
    }

    // ───────────── screen plumbing ─────────────
    private fun show(s: Screen) {
        screen = s
        render()
    }

    private fun showHelp(spec: HelpSpec) {
        help = spec
        show(Screen.HELP)
    }

    private fun render() {
        toggle = null; stateLabel = null; hintLabel = null; uptimeLabel = null; statusCard = null
        statusText = null; troubleLink = null; pillVpn = null; pillProxy = null; modeNote = null
        actionBtn = null; nextBtn = null; entry = null; entryMsg = null; caMsg = null

        content.removeAllViews()
        content.addView(header(), lp(top = 8))
        when (screen) {
            Screen.WIZARD -> buildWizard()
            Screen.MAIN -> buildMain()
            Screen.HELP -> buildHelp()
            Screen.LOG -> buildLog()
            Screen.CA -> buildCa()
            Screen.IMPORT -> buildImport()
        }
        content.addView(credits(), lp(top = 24, bottom = 8))
        scroll.scrollTo(0, 0)
    }

    private fun header(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(this).apply { setImageResource(R.drawable.ic_logo) }, lp(dp(44), dp(50), end = 12))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label(getString(R.string.app_name), 26f, bold = true))
        titles.addView(TextView(this).apply {
            text = "DIBAN"; textSize = 11f; setTextColor(Palette.BLUE); setTypeface(Typeface.DEFAULT_BOLD)
            textDirection = View.TEXT_DIRECTION_LTR; gravity = Gravity.START
        })
        row.addView(titles)
        box.addView(row)
        return box
    }

    private fun credits(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(label("ساخته شده توسط $AUTHOR", 12f, Palette.MUTED, center = true))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(link("کانال بله", Palette.BLUE, 13f) { openUrl(BALE_URL) })
        row.addView(link("کانال تلگرام", Palette.BLUE, 13f) { openUrl(TG_URL) })
        box.addView(row)
        return box
    }

    // ───────────── wizard ─────────────
    private fun buildWizard() {
        val steps = WizardSteps.STEPS
        val s = steps[wizardStep]
        val last = wizardStep == steps.lastIndex

        if (wizardStep == 0) {
            content.addView(label("فقط یک بار لازم است", 14f, Palette.MUTED, center = true), lp(top = 6))
        } else {
            content.addView(progressDots(wizardStep, steps.size - 1), lp(top = 16))
            content.addView(label("مرحله ${fa(wizardStep)} از ${fa(steps.size - 1)}", 13f, Palette.MUTED), lp(top = 8))
        }
        content.addView(label(s.title, 21f, bold = true), lp(top = if (wizardStep == 0) 20 else 2, bottom = 10))

        val c = card()
        if (wizardAdd && wizardStep == 1) {
            c.addView(
                label("برای سهمیه‌ی بیشتر همین مراحل را با یک حساب گوگل دیگر انجام دهید و رمز همان رمز فعلی است",
                    13f, Palette.AMBER),
                lp(bottom = 6)
            )
        }
        s.items.forEachIndexed { i, item ->
            c.addView(stepRow(if (wizardStep > 0) i + 1 else null, item.text, item.chips))
        }
        s.tip?.let { c.addView(label(it, 12.5f, Palette.MUTED), lp(top = 8)) }
        s.action?.let { (text, act) ->
            val b = button(text, Palette.BLUE) { runAction(act) }
            c.addView(b, lp(top = 16))
            actionBtn = b
        }
        content.addView(c)

        if (s.entry) {
            val e = input("AKfycb…  /  https://script.google.com/macros/s/…/exec")
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(t: Editable?) = checkEntry()
            })
            entry = e
            content.addView(e, lp(top = 14))
            entryMsg = label("", 13f, Palette.MUTED).also { content.addView(it, lp(top = 6)) }
        }

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val first = if (wizardAdd) 1 else 0
        if (wizardStep > first) nav.addView(link("قبلی", Palette.MUTED, 15f) { wizardStep--; render() })
        nav.addView(View(this), lp(0, 1, weight = 1f))
        val nextLabel = if (last) "ذخیره و اتصال" else if (wizardStep == 0) "شروع" else "بعدی"
        val nb = button(nextLabel, if (s.action != null) Palette.SLATE else Palette.BLUE) { nextStep() }
        nb.minWidth = dp(150)
        if (last) nb.setClay(null, Palette.SLATE, enabled = false)
        nextBtn = nb
        nav.addView(nb)
        content.addView(nav, lp(top = 18))

        if (wizardStep == 0 && !wizardAdd) {
            content.addView(link("کد اتصال آماده دارم", Palette.BLUE, 15f) { show(Screen.IMPORT) },
                LinearLayout.LayoutParams(WRAP, WRAP).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(8) })
        }
    }

    private fun nextStep() {
        if (wizardStep == WizardSteps.STEPS.lastIndex) {
            finishWizard()
        } else {
            wizardStep++
            render()
        }
    }

    /** Marks an action button as done and lets the user move on. */
    private fun markDone(text: String) {
        actionBtn?.setClay(text, Palette.GREEN)
        if (wizardStep != WizardSteps.STEPS.lastIndex) nextBtn?.setClay(null, Palette.BLUE)
    }

    private fun runAction(act: Action) {
        when (act) {
            Action.OPEN_SCRIPT -> {
                if (openUrl(WizardSteps.OPEN_URL)) markDone("صفحه باز شد ✓")
                else Toast.makeText(this, "مرورگری برای باز کردن صفحه پیدا نشد", Toast.LENGTH_LONG).show()
            }
            Action.COPY_SCRIPT -> io.execute {
                try {
                    val template = assets.open("Code.gs").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val key = if (wizardAdd) Bridge.authKey().ifEmpty { Bridge.pendingKey() } else Bridge.pendingKey()
                    val code = Bridge.applyAuthKey(template, key)
                    ui.post {
                        copyToClipboard("Code.gs", code)
                        markDone("کد کپی شد ✓")
                    }
                } catch (e: Exception) {
                    ui.post { Toast.makeText(this, "خواندن کد آماده ناموفق بود\n${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
            Action.PASTE_ID -> {
                val t = clipboardText().trim()
                if (t.isEmpty()) {
                    entryMsg?.apply { text = "کلیپ‌بورد خالی است و ابتدا شناسه را در گوگل کپی کنید"; setTextColor(Palette.AMBER) }
                } else {
                    entry?.setText(t)
                }
            }
        }
    }

    /** Live validation: the save button only lights up for a valid ID. */
    private fun checkEntry() {
        val txt = entry?.text?.toString().orEmpty().trim()
        val msg = entryMsg ?: return
        if (looksLikeDeploymentId(txt)) {
            msg.text = "شناسه معتبر است ✓"; msg.setTextColor(Palette.GREEN)
            nextBtn?.setClay(null, Palette.GREEN, enabled = true)
        } else {
            msg.text = if (txt.isNotEmpty()) "این مقدار شبیه شناسه نیست و باید یک رشته‌ی طولانی باشد" else ""
            msg.setTextColor(Palette.AMBER)
            nextBtn?.setClay(null, Palette.SLATE, enabled = false)
        }
    }

    /** Coming back from the browser: fill the ID by itself when the clipboard clearly holds one. */
    private fun autoPasteDeploymentId() {
        if (screen != Screen.WIZARD || wizardStep != WizardSteps.STEPS.lastIndex) return
        val e = entry ?: return
        if (e.text.isNotBlank()) return
        val t = clipboardText().trim()
        if ((t.startsWith("AKfycb") || t.contains("/macros/s/")) && looksLikeDeploymentId(t)) e.setText(t)
    }

    private fun finishWizard() {
        val text = entry?.text?.toString().orEmpty()
        nextBtn?.isEnabled = false
        io.execute {
            val r = Bridge.saveDeployment(text, wizardAdd)
            ui.post {
                if (!r.ok) {
                    entryMsg?.apply { this.text = r.error; setTextColor(Palette.RED) }
                    return@post
                }
                wizardAdd = false
                wizardStep = 0
                show(Screen.MAIN)
                Session.set(status = "تنظیم کامل شد و در حال اتصال", tone = Tone.GOOD)
                ui.postDelayed({ if (Session.state == State.IDLE) beginConnect() }, 400)
            }
        }
    }

    // ───────────── import connection code ─────────────
    private fun buildImport() {
        content.addView(label("کدی که شخص مورد اعتماد برایتان فرستاده را اینجا بچسبانید", 16f), lp(top = 20, bottom = 10))
        val e = input("MHR1-…")
        val clip = clipboardText()
        if (clip.isNotBlank() && Bridge.codeInText(clip)) e.setText(clip)
        content.addView(e)
        content.addView(label(WizardSteps.PRIVACY_IMPORTED, 13f, Palette.AMBER), lp(top = 10))
        val msg = label("", 13f, Palette.RED).also { content.addView(it, lp(top = 6)) }
        content.addView(button("متوجه شدم و ثبت کد", Palette.BLUE) {
            val text = e.text.toString()
            io.execute {
                val r = Bridge.importCode(text)
                ui.post {
                    if (r.ok) {
                        wizardStep = 0
                        show(Screen.MAIN)
                    } else {
                        msg.text = r.error
                    }
                }
            }
        }, lp(top = 14))
        content.addView(link("بازگشت", Palette.MUTED, 15f) { goBack() })
    }

    // ───────────── main screen ─────────────
    private fun buildMain() {
        val t = ToggleView(this).also { it.setOnClickListener { onToggle() } }
        toggle = t
        content.addView(t, LinearLayout.LayoutParams(WRAP, WRAP).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(14) })
        stateLabel = label("", 24f, bold = true, center = true).also { content.addView(it, lp(top = 4)) }
        hintLabel = label("", 14f, Palette.MUTED, center = true).also { content.addView(it, lp(top = 2)) }
        uptimeLabel = label("", 17f, Palette.BLUE, center = true).also { content.addView(it, lp(top = 4)) }

        // connection type
        val mode = card()
        mode.addView(label("نوع اتصال", 16f, bold = true))
        val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pillVpn = pill("وی‌پی‌ان").also {
            it.setOnClickListener { setMode(true) }
            pills.addView(it, lp(0, WRAP, weight = 1f, end = 6))
        }
        pillProxy = pill("فقط پراکسی").also {
            it.setOnClickListener { setMode(false) }
            pills.addView(it, lp(0, WRAP, weight = 1f, start = 6))
        }
        mode.addView(pills, lp(top = 10))
        modeNote = label("", 12.5f, Palette.MUTED).also { mode.addView(it, lp(top = 8)) }
        content.addView(mode, lp(top = 16))

        // status message
        val sc = card()
        statusText = label("", 14f, center = true).also { sc.addView(it) }
        statusCard = sc
        content.addView(sc, lp(top = 14))
        troubleLink = link("اگر وصل نمی‌شود", Palette.BLUE, 14f) { showTrouble() }.also {
            content.addView(it, LinearLayout.LayoutParams(WRAP, WRAP).also { p -> p.gravity = Gravity.CENTER_HORIZONTAL })
        }

        // telegram
        val tg = card()
        tg.addView(label("تلگرام", 17f, bold = true))
        tg.addView(label("با یک کلیک پروکسی را به تلگرام اضافه کنید", 14f, Palette.MUTED), lp(top = 2, bottom = 10))
        tg.addView(button("افزودن پروکسی به تلگرام", Palette.BLUE, 46) { addTelegram() })
        tg.addView(link("راهنمای دستی", Palette.BLUE, 13f) {
            showHelp(HelpSpec("راهنمای دستی تلگرام", WizardSteps.TG_STEPS, tip = WizardSteps.TG_TIP,
                action = "افزودن خودکار به تلگرام" to { addTelegram() }, back = { show(Screen.MAIN) }))
        }, LinearLayout.LayoutParams(WRAP, WRAP).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(6) })
        content.addView(tg, lp(top = 14))

        content.addView(link("بیشتر", Palette.MUTED, 15f) { showMenu(it) },
            LinearLayout.LayoutParams(WRAP, WRAP).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(10) })

        updateMain(animate = false)
    }

    private fun pill(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        typeface = Fonts.bold
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(12), dp(8), dp(12))
        isClickable = true
    }

    private fun vpnMode() = prefs.getBoolean(PREF_VPN, true)

    private fun setMode(vpn: Boolean) {
        if (Session.state != State.IDLE) {
            Session.set(status = "ابتدا اتصال را قطع کنید", tone = Tone.WARN)
            return
        }
        prefs.edit().putBoolean(PREF_VPN, vpn).apply()
        updateMain(animate = false)
    }

    private fun toneColor(t: Tone) = when (t) {
        Tone.NORMAL -> Palette.FG
        Tone.MUTED -> Palette.MUTED
        Tone.GOOD -> Palette.GREEN
        Tone.WARN -> Palette.AMBER
        Tone.BAD -> Palette.RED
    }

    private fun updateMain(animate: Boolean = true) {
        if (screen != Screen.MAIN) return
        val st = Session.state
        toggle?.setState(st, animate)

        val (color, title, hint) = when (st) {
            State.IDLE -> Triple(Palette.FG, "قطع است", "برای وصل شدن کلید را لمس کنید")
            State.CONNECTING -> Triple(Palette.AMBER, "در حال اتصال", "چند لحظه صبر کنید")
            State.CONNECTED -> Triple(Palette.BLUE, "متصل است", "برای قطع کردن کلید را لمس کنید")
        }
        stateLabel?.apply { text = title; setTextColor(color) }
        hintLabel?.text = hint

        val msg = Session.status
        statusCard?.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
        statusText?.apply { text = msg; setTextColor(toneColor(Session.tone)) }
        troubleLink?.visibility =
            if (Session.probeFailed || (st == State.IDLE && Session.tone == Tone.BAD)) View.VISIBLE else View.GONE

        val vpn = if (st == State.IDLE) vpnMode() else Session.vpnMode
        pillVpn?.apply {
            background = rounded(if (vpn) Palette.BLUE else Palette.SLATE, 12)
            setTextColor(if (vpn) Palette.WHITE else Palette.FG)
        }
        pillProxy?.apply {
            background = rounded(if (!vpn) Palette.BLUE else Palette.SLATE, 12)
            setTextColor(if (!vpn) Palette.WHITE else Palette.FG)
        }
        val idle = st == State.IDLE
        pillVpn?.alpha = if (idle) 1f else 0.6f
        pillProxy?.alpha = if (idle) 1f else 0.6f
        modeNote?.text = if (vpn) "همه‌ی برنامه‌های گوشی از دیبان استفاده می‌کنند"
        else "فقط یک پراکسی محلی روشن می‌شود و آن را خودتان در وای‌فای یا تلگرام وارد می‌کنید"

        if (st == State.CONNECTED) {
            ui.removeCallbacks(tick)
            ui.post(tick)
        } else {
            uptimeLabel?.text = ""
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val l = uptimeLabel ?: return
            if (Session.state != State.CONNECTED || screen != Screen.MAIN) {
                l.text = ""
                return
            }
            val total = ((System.currentTimeMillis() - Session.connectedAtMs) / 1000).toInt()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            l.text = fa(if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s))
            ui.postDelayed(this, 1000)
        }
    }

    // ───────────── connect / disconnect ─────────────
    private fun onToggle() {
        when (Session.state) {
            State.CONNECTING -> Unit
            State.CONNECTED -> {
                Session.set(status = "در حال قطع اتصال", tone = Tone.MUTED)
                startService(Intent(this, DibanService::class.java).setAction(DibanService.ACTION_STOP))
            }
            State.IDLE -> beginConnect()
        }
    }

    /** CA first (browsers must trust it), then notification permission, VPN consent, service. */
    private fun beginConnect() {
        if (Session.state != State.IDLE) return
        val vpn = vpnMode()
        Session.set(State.CONNECTING, "در حال آماده‌سازی گواهی امنیتی", Tone.MUTED, probeFailed = false)
        io.execute {
            try {
                val ca = Bridge.ensureCa()
                if (!prefs.getBoolean(PREF_CA_SKIPPED, false) && !CaTrust.isInstalled(ca)) {
                    Session.set(State.IDLE, "", Tone.MUTED)
                    ui.post { show(Screen.CA) }
                    return@execute
                }
                ui.post { launchConnection(vpn) }
            } catch (e: Exception) {
                Session.set(State.IDLE, getString(R.string.st_failed, (e.message ?: e.javaClass.simpleName).trimEnd('.')), Tone.BAD)
            }
        }
    }

    private fun launchConnection(vpn: Boolean) {
        pendingVpn = vpn
        val needNotif = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(PREF_NOTIF_ASKED, false)
        if (needNotif) {
            prefs.edit().putBoolean(PREF_NOTIF_ASKED, true).apply()
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            afterNotificationStep()
        }
    }

    private fun afterNotificationStep() {
        if (pendingVpn) {
            val consent = VpnService.prepare(this)
            if (consent != null) {
                vpnPermission.launch(consent)
                return
            }
        }
        startConnection(pendingVpn)
    }

    private fun startConnection(vpn: Boolean) {
        Session.set(State.CONNECTING, getString(R.string.st_starting), Tone.MUTED, probeFailed = false)
        ContextCompat.startForegroundService(
            this,
            Intent(this, DibanService::class.java)
                .setAction(DibanService.ACTION_START)
                .putExtra(DibanService.EXTRA_VPN, vpn)
        )
    }

    // ───────────── telegram ─────────────
    private fun addTelegram() {
        val ok = openUrl("tg://socks?server=127.0.0.1&port=${Bridge.socksPort()}")
        if (!ok) {
            Session.set(status = "تلگرام روی گوشی پیدا نشد", tone = Tone.WARN)
        } else if (Session.state == State.CONNECTED) {
            Session.set(status = "در تلگرام دکمه‌ی اتصال به پروکسی را بزنید", tone = Tone.NORMAL)
        } else {
            Session.set(status = "پروکسی به تلگرام اضافه می‌شود و برای کار کردن باید دیبان وصل باشد", tone = Tone.WARN)
        }
        if (screen == Screen.HELP) show(Screen.MAIN)
    }

    // ───────────── menu & help pages ─────────────
    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "اگر وصل نمی‌شود")
        m.menu.add(0, 2, 1, "گواهی امنیتی")
        m.menu.add(0, 3, 2, "راهنمای حالت فقط پراکسی")
        m.menu.add(0, 4, 3, "راهنمای تنظیم از اول")
        m.menu.add(0, 5, 4, "افزودن حساب گوگل دیگر برای سهمیه‌ی بیشتر")
        m.menu.add(0, 6, 5, "گزارش فنی")
        m.menu.add(0, 7, 6, "حذف کامل برنامه از گوشی")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showTrouble()
                2 -> show(Screen.CA)
                3 -> showHelp(HelpSpec("راهنمای حالت فقط پراکسی", WizardSteps.PROXY_STEPS, tip = WizardSteps.PROXY_TIP,
                    action = "باز کردن تنظیمات وای‌فای" to { startActivitySafely(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                    back = { show(Screen.MAIN) }))
                4 -> rerunWizard()
                5 -> addServer()
                6 -> show(Screen.LOG)
                7 -> confirmUninstall()
            }
            true
        }
        m.show()
    }

    private fun showTrouble() = showHelp(
        HelpSpec("اگر وصل نشد اینها را بررسی کنید", WizardSteps.TROUBLE, back = { show(Screen.MAIN) })
    )

    private fun buildHelp() {
        val h = help ?: run { show(Screen.MAIN); return }
        content.addView(label(h.title, 19f, bold = true), lp(top = 18, bottom = 8))
        h.action?.let { (text, fn) -> content.addView(button(text, Palette.BLUE, 44) { fn() }, lp(bottom = 10)) }
        val c = card()
        h.intro?.let { c.addView(label(it, 14f, Palette.MUTED), lp(bottom = 8)) }
        h.items.forEachIndexed { i, item -> c.addView(stepRow(i + 1, item.text, item.chips)) }
        h.tip?.let { c.addView(label(it, 12.5f, Palette.MUTED), lp(top = 8)) }
        content.addView(c)
        content.addView(button("بازگشت", Palette.SLATE, 44) { h.back() }, lp(top = 12))
    }

    private fun busyNote(): Boolean {
        if (Session.state != State.IDLE) {
            Session.set(status = "ابتدا اتصال را قطع کنید", tone = Tone.WARN)
            return true
        }
        return false
    }

    private fun addServer() {
        if (busyNote()) return
        wizardAdd = true
        wizardStep = 1
        show(Screen.WIZARD)
    }

    private fun rerunWizard() {
        if (busyNote()) return
        AlertDialog.Builder(this)
            .setMessage("مراحل تنظیم از اول انجام شود؟\n(تنظیمات فعلی جایگزین می‌شود)")
            .setPositiveButton("بله") { _, _ ->
                io.execute {
                    Bridge.resetConfig()
                    ui.post { wizardAdd = false; wizardStep = 0; show(Screen.WIZARD) }
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun confirmUninstall() {
        if (busyNote()) return
        AlertDialog.Builder(this)
            .setMessage("گواهی امنیتی و همه‌ی تنظیمات برنامه از گوشی پاک شود؟")
            .setPositiveButton("پاک شود") { _, _ ->
                io.execute {
                    Bridge.resetAll()
                    prefs.edit().clear().apply()
                    ui.post {
                        showHelp(HelpSpec("پاک‌سازی انجام شد", WizardSteps.CA_REMOVE,
                            action = "باز کردن تنظیمات امنیتی" to { startActivitySafely(Intent(Settings.ACTION_SECURITY_SETTINGS)) },
                            back = { wizardAdd = false; wizardStep = 0; show(Screen.WIZARD) }))
                    }
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    // ───────────── certificate screen ─────────────
    private fun buildCa() {
        content.addView(label("نصب گواهی امنیتی", 21f, bold = true), lp(top = 18, bottom = 6))
        content.addView(label(WizardSteps.CA_WHY, 14f, Palette.MUTED), lp(bottom = 10))

        val c = card()
        WizardSteps.CA_STEPS.forEachIndexed { i, item -> c.addView(stepRow(i + 1, item.text, item.chips)) }
        c.addView(label(WizardSteps.CA_TIP, 12.5f, Palette.MUTED), lp(top = 8))
        c.addView(button("ذخیره‌ی گواهی", Palette.BLUE, 46) {
            io.execute {
                try {
                    Bridge.ensureCa()
                    ui.post { saveCa.launch("Diban-CA.crt") }
                } catch (e: Exception) {
                    ui.post { setCaMsg("ساخت گواهی ناموفق بود\n${e.message}", Palette.RED) }
                }
            }
        }, lp(top = 14))
        c.addView(button("باز کردن تنظیمات امنیتی", Palette.BLUE, 46) {
            startActivitySafely(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }, lp(top = 10))
        content.addView(c)

        content.addView(label(WizardSteps.CA_LIMITS, 12.5f, Palette.AMBER), lp(top = 12))
        caMsg = label("", 14f, Palette.MUTED, center = true).also { content.addView(it, lp(top = 10)) }
        content.addView(button("نصب کردم، بررسی کن", Palette.GREEN) { checkCa() }, lp(top = 10))
        content.addView(link("ادامه بدون گواهی", Palette.MUTED, 14f) {
            prefs.edit().putBoolean(PREF_CA_SKIPPED, true).apply()
            show(Screen.MAIN)
            if (Session.state == State.IDLE) beginConnect()
        })
        content.addView(link("بازگشت", Palette.MUTED, 15f) { show(Screen.MAIN) })
    }

    private fun setCaMsg(text: String, color: Int) {
        caMsg?.apply { this.text = text; setTextColor(color) }
    }

    private fun checkCa() {
        setCaMsg("در حال بررسی", Palette.MUTED)
        io.execute {
            val ok = try { CaTrust.isInstalled(Bridge.ensureCa()) } catch (_: Exception) { false }
            ui.post {
                if (ok) {
                    prefs.edit().putBoolean(PREF_CA_SKIPPED, false).apply()
                    setCaMsg("گواهی نصب شده است ✓", Palette.GREEN)
                    ui.postDelayed({
                        show(Screen.MAIN)
                        if (Session.state == State.IDLE) beginConnect()
                    }, 600)
                } else {
                    setCaMsg("هنوز گواهی دیبان پیدا نشد و مراحل را دوباره بررسی کنید", Palette.AMBER)
                }
            }
        }
    }

    // ───────────── log screen ─────────────
    private fun buildLog() {
        content.addView(label("گزارش فنی", 19f, bold = true), lp(top = 18, bottom = 8))
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Palette.FG)
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.START
            setTextIsSelectable(true)
            text = "…"
        }
        val c = card().also { it.addView(tv) }
        var logText = ""
        io.execute {
            logText = Bridge.log().ifEmpty { "—" }
            ui.post { tv.text = logText }
        }
        content.addView(button("کپی گزارش برای ارسال به پشتیبان", Palette.SLATE, 46) { b ->
            copyToClipboard("Diban log", logText)
            (b as TextView).setClay("کپی شد ✓", Palette.GREEN)
        }, lp(bottom = 10))
        content.addView(c)
        content.addView(button("بازگشت", Palette.SLATE, 44) { show(Screen.MAIN) }, lp(top = 12))
    }

    // ───────────── small helpers ─────────────
    private fun input(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        typeface = Typeface.MONOSPACE
        textSize = 13f
        minLines = 3
        gravity = Gravity.TOP or Gravity.START
        textDirection = View.TEXT_DIRECTION_LTR
        setTextColor(Palette.FG)
        setHintTextColor(Palette.GRAY)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(Palette.CARD, 12, Palette.SHADOW)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }

    private fun clipboardText(): String {
        val cm = getSystemService(ClipboardManager::class.java) ?: return ""
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
    }

    private fun copyToClipboard(label: String, text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun startActivitySafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "این صفحه روی گوشی شما پیدا نشد", Toast.LENGTH_LONG).show()
        }
    }
}
