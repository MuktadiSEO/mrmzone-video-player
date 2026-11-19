package com.mrmzone.player.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.mrmzone.player.R

/** Tiny helper to build settings-style screens in code (same look as MRM Zone IDM). */
class Form(private val a: Activity, title: String) {
    val dp = a.resources.displayMetrics.density
    val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ContextCompat.getColor(a, R.color.bg)) }
    val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (32 * dp).toInt()) }

    init {
        val tb = MaterialToolbar(a).apply {
            this.title = title
            setTitleTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(ContextCompat.getColor(a, R.color.bar))
            setNavigationIcon(R.drawable.ic_back_w)
            setNavigationContentDescription("Back")
            setNavigationOnClickListener { a.finish() }
        }
        root.addView(tb, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { })
        tb.minimumHeight = (56 * dp).toInt()
        root.addView(ScrollView(a).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    fun header(t: String) = add(TextView(a).apply {
        text = t; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(a, R.color.brand_orange)); setPadding(0, (20 * dp).toInt(), 0, (4 * dp).toInt())
    })

    fun note(t: String) = add(TextView(a).apply {
        text = t; textSize = 12f; setTextColor(ContextCompat.getColor(a, R.color.text_secondary))
        setPadding(0, (2 * dp).toInt(), 0, (4 * dp).toInt())
    }) as TextView

    fun switch(t: String, value: Boolean, on: (Boolean) -> Unit): MaterialSwitch {
        val s = MaterialSwitch(a).apply {
            text = t; isChecked = value; textSize = 15f
            setTextColor(ContextCompat.getColor(a, R.color.text_primary))
            minHeight = (48 * dp).toInt()
            setOnCheckedChangeListener { _, b -> on(b) }
        }
        add(s); return s
    }

    fun radios(options: List<String>, selected: Int, on: (Int) -> Unit): RadioGroup {
        val rg = RadioGroup(a)
        options.forEachIndexed { i, t ->
            rg.addView(RadioButton(a).apply {
                text = t; id = 1000 + i; isChecked = selected == i; textSize = 15f
                setTextColor(ContextCompat.getColor(a, R.color.text_primary))
            })
        }
        rg.setOnCheckedChangeListener { _, id -> on(id - 1000) }
        add(rg); return rg
    }

    fun slider(t: String, from: Int, to: Int, step: Int, value: Int, fmt: (Int) -> String = { it.toString() }, on: (Int) -> Unit): Slider {
        val label = TextView(a).apply {
            textSize = 15f; text = "$t: ${fmt(value)}"; setPadding(0, (10 * dp).toInt(), 0, 0)
            setTextColor(ContextCompat.getColor(a, R.color.text_primary))
        }
        add(label)
        val s = Slider(a).apply {
            valueFrom = from.toFloat(); valueTo = to.toFloat(); stepSize = step.toFloat()
            this.value = (((value.coerceIn(from, to) - from) / step) * step + from).toFloat()
            setLabelFormatter { fmt(it.toInt()) }
            addOnChangeListener { _, v, _ -> label.text = "$t: ${fmt(v.toInt())}"; on(v.toInt()) }
        }
        add(s); return s
    }

    fun add(v: View): View { body.addView(v, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)); return v }

    fun show() = a.setContentView(root)
}
