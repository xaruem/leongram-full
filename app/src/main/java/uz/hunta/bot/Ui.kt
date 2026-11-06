package uz.hunta.bot

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Небольшой набор помощников для экранов без XML-разметки. */
object Ui {
    val BG = 0xFF0B0B0F.toInt()
    val CARD = 0xFF14141A.toInt()
    val TEXT = 0xFFFFFFFF.toInt()
    val MUTED = 0xFF9A9AAB.toInt()
    val GREEN = 0xFF1FB86A.toInt()
    val RED = 0xFFE5484D.toInt()
    val BLUE = 0xFF8A4DDB.toInt()      // акцент (фиолетовый), раньше был синий
    val GRAY = 0xFF2A2A34.toInt()
    val DARK = 0xFF3B3B8F.toInt()
    val STROKE = 0xFF2E2E3A.toInt()
    val FIELD = 0xFF1C1C24.toInt()
    // градиент Instagram: фиолетовый → розовый → оранжевый
    val GRAD = intArrayOf(0xFF833AB4.toInt(), 0xFFE1306C.toInt(), 0xFFF77737.toInt())

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun bg(c: Context, color: Int, radius: Int = 12): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(c, radius).toFloat() }

    fun lp(c: Context, top: Int = 0, bottom: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams =
        if (weight > 0f) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            topMargin = dp(c, top); bottomMargin = dp(c, bottom)
        } else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(c, top); bottomMargin = dp(c, bottom)
        }

    fun text(c: Context, t: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(c, 14).toFloat(); setStroke(dp(c, 1), STROKE) }
        val p = dp(c, 14); setPadding(p, p, p, p)
    }

    fun row(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

    /**
     * kind: 0 — вторичная (тонкая обводка), 1 — главная (градиент Instagram),
     * 2 — опасная (красная), 3 — зелёная. icon — необязательный значок перед текстом.
     */
    fun button(c: Context, label: String, kind: Int = 0, icon: String = "", onClick: () -> Unit): Button =
        object : Button(c) {
            override fun setEnabled(enabled: Boolean) {
                super.setEnabled(enabled); alpha = if (enabled) 1f else 0.4f
            }
        }.apply {
            text = if (icon.isEmpty()) label else "$icon  $label"
            isAllCaps = false; textSize = 14f; stateListAnimator = null
            val r = dp(c, 12).toFloat()
            val face = when (kind) {
                1 -> GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, GRAD).apply { cornerRadius = r }
                2 -> GradientDrawable().apply { setColor(RED); cornerRadius = r }
                3 -> GradientDrawable().apply { setColor(GREEN); cornerRadius = r }
                else -> GradientDrawable().apply { setColor(CARD); cornerRadius = r; setStroke(dp(c, 1), STROKE) }
            }
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x44FFFFFF), face, null
            )
            setTextColor(0xFFFFFFFF.toInt())
            if (kind != 0) setTypeface(typeface, Typeface.BOLD)
            minHeight = dp(c, 46); minimumHeight = dp(c, 46)
            setOnClickListener { onClick() }
        }

    /** Тёмное поле ввода со скруглением. */
    fun styleEdit(c: Context, e: EditText): EditText = e.apply {
        setTextColor(TEXT); setHintTextColor(MUTED)
        background = GradientDrawable().apply { setColor(FIELD); cornerRadius = dp(c, 10).toFloat(); setStroke(dp(c, 1), STROKE) }
        val p = dp(c, 12); setPadding(p, p, p, p)
    }

    /** Закрасить текст градиентом Instagram. */
    fun gradientText(tv: TextView): TextView = tv.apply {
        val w = paint.measureText(text.toString()).coerceAtLeast(1f)
        paint.shader = android.graphics.LinearGradient(0f, 0f, w, 0f, GRAD, null, android.graphics.Shader.TileMode.CLAMP)
    }

    fun chip(c: Context, t: String, color: Int): TextView = TextView(c).apply {
        text = t; textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
        background = bg(c, color, 20)
        setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3))
    }

    fun spinner(c: Context, items: List<String>, sel: Int, onPick: (Int) -> Unit): Spinner = Spinner(c).apply {
        adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel.coerceIn(0, items.size - 1), false)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onPick(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}

/** Общая основа экранов: почти чёрный фон, прокрутка, вертикальная колонка [col]. */
abstract class Page : Activity() {
    protected lateinit var col: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(this); BotLog.init(this)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@Page, 16); setPadding(p, p, p, p * 2)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
    }

    override fun onResume() { super.onResume(); render() }

    protected abstract fun render()

    protected fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
