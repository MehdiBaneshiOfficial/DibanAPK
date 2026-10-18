package ir.diban.android

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import kotlin.math.max
import kotlin.math.min

/** Light "clay" palette: white base, one blue accent (same as the desktop app). */
object Palette {
    val BG = 0xFFF4F7FC.toInt()
    val FG = 0xFF0F172A.toInt()
    val MUTED = 0xFF64748B.toInt()
    val CARD = 0xFFFFFFFF.toInt()
    val GRAY = 0xFFCBD5E1.toInt()
    val SLATE = 0xFFDFE7F5.toInt()
    val SHADOW = 0xFFD3DBEB.toInt()
    val AMBER = 0xFFD97706.toInt()
    val GREEN = 0xFF16A34A.toInt()
    val RED = 0xFFDC2626.toInt()
    val BLUE = 0xFF2563EB.toInt()
    val CHIP_BG = 0xFFE6EFFF.toInt()
    val CHIP_FG = 0xFF1D4ED8.toInt()
    val WHITE = 0xFFFFFFFF.toInt()
}

object Fonts {
    var regular: Typeface = Typeface.DEFAULT
    var bold: Typeface = Typeface.DEFAULT_BOLD

    fun init(ctx: Context) {
        ResourcesCompat.getFont(ctx, R.font.vazirmatn_regular)?.let { regular = it }
        ResourcesCompat.getFont(ctx, R.font.vazirmatn_bold)?.let { bold = it }
    }
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private val FA_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
fun fa(n: Any): String = n.toString().map { if (it in '0'..'9') FA_DIGITS[it - '0'] else it }.joinToString("")

private val ID_URL = Regex("/macros/s/([A-Za-z0-9_\\-]{20,})")
private val ID_RAW = Regex("^[A-Za-z0-9_\\-]{20,}$")

/** Same rule as diban_bridge.extract_deployment_id, used for live validation while typing. */
fun looksLikeDeploymentId(text: String): Boolean {
    val t = text.trim().trim('"', '\'')
    return ID_URL.containsMatchIn(t) || ID_RAW.matches(t)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.openUrl(url: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: Exception) {
    false
}

fun shade(color: Int, f: Float): Int {
    fun c(v: Int) = min(255, max(0, (v * f).toInt()))
    return Color.rgb(c(Color.red(color)), c(Color.green(color)), c(Color.blue(color)))
}

fun Context.rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

fun Context.oval(color: Int): GradientDrawable =
    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

/** Rounded face with a darker "lip" underneath, like the desktop clay buttons. */
fun Context.clay(face: Int, radiusDp: Int, lipDp: Int = 4): Drawable {
    val layers = LayerDrawable(arrayOf(rounded(shade(face, 0.78f), radiusDp), rounded(face, radiusDp)))
    layers.setLayerInset(1, 0, 0, 0, dp(lipDp))
    return RippleDrawable(ColorStateList.valueOf(0x22000000), layers, null)
}

fun Context.lp(
    w: Int = MATCH, h: Int = WRAP, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0, weight: Float = 0f
): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight).also {
        it.topMargin = dp(top); it.bottomMargin = dp(bottom); it.marginStart = dp(start); it.marginEnd = dp(end)
    }

fun Context.label(
    text: CharSequence = "", sizeSp: Float = 15f, color: Int = Palette.FG, bold: Boolean = false, center: Boolean = false
): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    typeface = if (bold) Fonts.bold else Fonts.regular
    gravity = if (center) Gravity.CENTER_HORIZONTAL else Gravity.START
    textDirection = View.TEXT_DIRECTION_RTL
    setLineSpacing(0f, 1.3f)
}

/** Clay-style button.  Use [setClay] later to change colour/label (e.g. "done" state). */
fun Context.button(text: CharSequence, color: Int = Palette.BLUE, heightDp: Int = 50, onClick: (View) -> Unit): TextView =
    TextView(this).apply {
        textSize = 15f
        typeface = Fonts.bold
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minHeight = dp(heightDp)
        setPadding(dp(18), 0, dp(18), dp(4))
        setClay(text, color)
        setOnClickListener(onClick)
    }

fun TextView.setClay(text: CharSequence? = null, color: Int, enabled: Boolean = true) {
    if (text != null) this.text = text
    background = context.clay(color, 14)
    setTextColor(if (color == Palette.SLATE) Palette.FG else Palette.WHITE)
    isEnabled = enabled
    alpha = if (enabled) 1f else 0.55f
}

fun Context.link(text: CharSequence, color: Int = Palette.MUTED, sizeSp: Float = 14f, onClick: (View) -> Unit): TextView =
    label(text, sizeSp, color).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isClickable = true
        isFocusable = true
        setOnClickListener(onClick)
    }

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(14), dp(16), dp(18))
    val layers = LayerDrawable(arrayOf(rounded(Palette.SHADOW, 18), rounded(Palette.CARD, 18)))
    layers.setLayerInset(1, 0, 0, 0, dp(4))
    background = layers
}

fun Context.chip(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 12f
    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
    setTextColor(Palette.CHIP_FG)
    textDirection = View.TEXT_DIRECTION_LTR
    setPadding(dp(8), dp(2), dp(8), dp(2))
    background = rounded(Palette.CHIP_BG, 6)
}

/** English button names, always left-to-right, with arrows between them ("then, in this order"). */
fun Context.chips(items: List<String>): View {
    val flow = FlowLayout(this, dp(4), dp(6))
    items.forEachIndexed { i, c ->
        if (i > 0) flow.addView(TextView(this).apply {
            text = "→"; setTextColor(Palette.MUTED); textSize = 13f
        })
        flow.addView(chip(c))
    }
    return flow
}

/** Numbered row: badge at the start (right in RTL), Persian sentence, optional chips below. */
fun Context.stepRow(n: Int?, text: String, chipList: List<String>?): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, dp(6))
    }
    val badge = TextView(this).apply {
        gravity = Gravity.CENTER
        if (n != null) {
            this.text = fa(n); textSize = 13f; typeface = Fonts.bold; setTextColor(Palette.WHITE)
            background = oval(Palette.BLUE)
        } else {
            this.text = "•"; textSize = 20f; setTextColor(Palette.GRAY)
        }
    }
    row.addView(badge, lp(dp(26), dp(26), top = 0, end = 10).also { it.topMargin = dp(2) })
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    body.addView(label(text, 15f), lp())
    if (!chipList.isNullOrEmpty()) {
        body.addView(chips(chipList), LinearLayout.LayoutParams(WRAP, WRAP).also {
            it.topMargin = dp(6); it.gravity = Gravity.START
        })
    }
    row.addView(body, lp(0, WRAP, weight = 1f))
    return row
}

/** Wizard progress: the current step is a longer pill. */
fun Context.progressDots(step: Int, total: Int): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
    for (n in 1..total) {
        val v = View(this).apply { background = rounded(if (n <= step) Palette.BLUE else Palette.GRAY, 4) }
        row.addView(v, lp(dp(if (n == step) 26 else 10), dp(8), end = 6))
    }
    return row
}

/** Children flow left-to-right and wrap, regardless of the layout direction. */
class FlowLayout(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0; var usedW = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            val cw = c.measuredWidth; val ch = c.measuredHeight
            if (x > 0 && x + cw > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += cw + hGap
            rowH = max(rowH, ch)
            usedW = max(usedW, x - hGap)
        }
        setMeasuredDimension(
            resolveSize(usedW + paddingLeft + paddingRight, widthSpec),
            resolveSize(y + rowH + paddingTop + paddingBottom, heightSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val cw = c.measuredWidth; val ch = c.measuredHeight
            if (x > 0 && x + cw > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + cw, paddingTop + y + ch)
            x += cw + hGap
            rowH = max(rowH, ch)
        }
    }
}

/** The big on/off switch: knob slides over, spinner ring while connecting. */
class ToggleView(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private var state = Session.State.IDLE
    private var progress = 0f
    private var spin = 0f
    private var slide: ValueAnimator? = null
    private var spinner: ValueAnimator? = null

    fun setState(s: Session.State, animate: Boolean = true) {
        state = s
        val target = if (s == Session.State.IDLE) 0f else 1f
        slide?.cancel()
        if (animate && progress != target) {
            slide = ValueAnimator.ofFloat(progress, target).apply {
                duration = 220
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            progress = target
        }
        if (s == Session.State.CONNECTING) {
            if (spinner == null) spinner = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { spin = -(it.animatedValue as Float); invalidate() }
                start()
            }
        } else {
            spinner?.cancel(); spinner = null
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        slide?.cancel(); spinner?.cancel(); spinner = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((220 * d).toInt(), (130 * d).toInt())

    override fun onDraw(c: Canvas) {
        c.save()
        c.scale(d, d)
        val base = when (state) {
            Session.State.IDLE -> 0xFFDBE3F1.toInt()
            Session.State.CONNECTING -> 0xFF86ADF6.toInt()
            Session.State.CONNECTED -> Palette.BLUE
        }
        paint.style = Paint.Style.FILL
        paint.color = shade(base, 0.8f); c.drawRoundRect(20f, 32f, 200f, 104f, 36f, 36f, paint)
        paint.color = base; c.drawRoundRect(20f, 26f, 200f, 98f, 36f, 36f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.strokeCap = Paint.Cap.ROUND
        paint.color = shade(base, 1.35f); c.drawLine(56f, 35f, 164f, 35f, paint)

        val x = 56f + 108f * progress
        val cy = 62f
        paint.style = Paint.Style.FILL
        paint.color = shade(base, 0.62f); c.drawCircle(x, cy + 6f, 29f, paint)
        paint.color = 0xFFEEF2F9.toInt(); c.drawCircle(x, cy, 29f, paint)
        paint.color = Palette.WHITE; c.drawOval(x - 22f, cy - 25f, x + 6f, cy - 9f, paint)
        if (state == Session.State.CONNECTING) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = Palette.WHITE
            arc.set(x - 38f, cy - 38f, x + 38f, cy + 38f)
            c.drawArc(arc, spin, 90f, false, paint)
        }
        c.restore()
    }
}
