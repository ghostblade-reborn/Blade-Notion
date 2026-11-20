package com.ghostblade.notion

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal framework-only UI helpers for GhostBlade Notion, in GhostBlade
 * Task style: dark background, gold accent (#d9a441), views built in code.
 * Kept deliberately small for v0.1 — no glass/wallpaper system yet.
 */
object Ui {
    val BG: Int = 0xFF0B0B10.toInt()
    val PANEL: Int = 0xFF17171F.toInt()
    val PANEL_LIGHT: Int = 0xFF22222E.toInt()
    val TEXT: Int = 0xFFF5F5F7.toInt()
    val MUTED: Int = 0xFFB9B9C9.toInt()
    val ACCENT: Int = 0xFFD9A441.toInt() // gold, GhostBlade accent
    val ACCENT_DARK: Int = 0xFF8A6A1F.toInt()
    val GREEN: Int = 0xFF30D158.toInt()
    val RED: Int = 0xFFFF6961.toInt()
    val CHIP_BG: Int = 0xFF2A2A38.toInt()

    fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun panelBg(ctx: Context, radiusDp: Float = 16f): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(PANEL)
        g.cornerRadius = dp(ctx, radiusDp).toFloat()
        g.setStroke(dp(ctx, 1f), Color.argb(60, 255, 255, 255))
        return g
    }

    fun chipBg(ctx: Context, accent: Boolean = false): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(if (accent) ACCENT else CHIP_BG)
        g.cornerRadius = dp(ctx, 999f).toFloat()
        return g
    }

    fun tv(ctx: Context, text: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    fun button(ctx: Context, label: String, primary: Boolean = false, click: () -> Unit): TextView {
        val b = tv(ctx, label, 15f, if (primary) 0xFF111111.toInt() else TEXT, primary)
        b.gravity = Gravity.CENTER
        if (primary) {
            val g = GradientDrawable()
            g.setColor(ACCENT)
            g.cornerRadius = dp(ctx, 999f).toFloat()
            b.background = g
        } else {
            b.background = chipBg(ctx)
        }
        b.setPadding(dp(ctx, 14f), dp(ctx, 11f), dp(ctx, 14f), dp(ctx, 11f))
        b.setOnClickListener { click() }
        return b
    }

    fun chip(ctx: Context, label: String, accent: Boolean = false): TextView {
        val c = tv(ctx, label, 11f, if (accent) 0xFF111111.toInt() else MUTED, true)
        c.background = chipBg(ctx, accent)
        c.setPadding(dp(ctx, 8f), dp(ctx, 4f), dp(ctx, 8f), dp(ctx, 4f))
        return c
    }

    fun input(ctx: Context, hint: String, value: String = "", multi: Boolean = false): EditText {
        val e = EditText(ctx)
        e.hint = hint
        e.setText(value)
        e.textSize = 15f
        e.setTextColor(TEXT)
        e.setHintTextColor(MUTED)
        e.background = panelBg(ctx, 12f)
        e.setPadding(dp(ctx, 12f), dp(ctx, 11f), dp(ctx, 12f), dp(ctx, 11f))
        if (!multi) e.setSingleLine(true) else {
            e.minLines = 4
            e.gravity = Gravity.TOP
        }
        return e
    }

    fun lp(ctx: Context, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
           l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.setMargins(dp(ctx, l), dp(ctx, t), dp(ctx, r), dp(ctx, b))
        return p
    }

    fun card(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        l.background = panelBg(ctx, 16f)
        val pad = dp(ctx, 14f)
        l.setPadding(pad, pad, pad, pad)
        return l
    }

    fun row(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun column(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    // ---- Round 2 (Team Two): selectable toggle chips ----

    fun accentBg(ctx: Context): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(ACCENT)
        g.cornerRadius = dp(ctx, 999f).toFloat()
        return g
    }

    /** Restyles an existing toggle button in place: gold pill + dark bold
     *  text when selected, dark chip + light text when not. Used by the
     *  language selector and channel/language pickers so the selected
     *  state is always visible (fixes Round 1 finding N-4). */
    fun setToggle(ctx: Context, v: TextView, selected: Boolean) {
        v.background = if (selected) accentBg(ctx) else chipBg(ctx)
        v.setTextColor(if (selected) 0xFF111111.toInt() else TEXT)
        v.setTypeface(v.typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    fun toggleButton(ctx: Context, label: String, selected: Boolean, click: () -> Unit): TextView {
        val b = tv(ctx, label, 14f, TEXT, false)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(ctx, 14f), dp(ctx, 10f), dp(ctx, 14f), dp(ctx, 10f))
        setToggle(ctx, b, selected)
        b.setOnClickListener { click() }
        return b
    }
}
