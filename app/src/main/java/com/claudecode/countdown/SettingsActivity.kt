package com.claudecode.countdown

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.claudecode.countdown.ThemeManager.Palette
import com.claudecode.countdown.widget.CountdownWidgetProvider

class SettingsActivity : AppCompatActivity() {

    private lateinit var containerMode: LinearLayout
    private lateinit var containerAccent: LinearLayout
    private lateinit var containerPresets: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        containerMode = findViewById(R.id.container_mode)
        containerAccent = findViewById(R.id.container_accent)
        containerPresets = findViewById(R.id.container_presets)

        val textStatus = findViewById<TextView>(R.id.text_update_status)
        findViewById<TextView>(R.id.text_version).text =
            getString(R.string.update_version, Updater.currentVersionName(this))
        findViewById<View>(R.id.btn_check_update).setOnClickListener {
            Updater.checkForUpdates(this, manual = true) { textStatus.text = it }
        }
        render()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun onChanged() {
        // May recreate this activity when the effective day/night mode changes.
        ThemeManager.applyNightMode(this)
        CountdownWidgetProvider.updateAllWidgets(this)
        render()
    }

    private fun render() {
        val p = ThemeManager.palette(this)
        // Paint the static views first: the dynamic ones below set their own colors.
        ThemeManager.apply(this)
        buildModes(p)
        buildAccents(p)
        buildPresets(p)
    }

    private fun buildModes(p: Palette) {
        containerMode.removeAllViews()
        val current = ThemeManager.getMode(this)
        val modes = listOf(
            ThemeManager.MODE_SYSTEM to getString(R.string.mode_system),
            ThemeManager.MODE_LIGHT to getString(R.string.mode_light),
            ThemeManager.MODE_DARK to getString(R.string.mode_dark)
        )
        for ((mode, label) in modes) {
            containerMode.addView(chip(label, mode == current, p) {
                ThemeManager.setMode(this, mode)
                onChanged()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun buildAccents(p: Palette) {
        containerAccent.removeAllViews()
        val override = ThemeManager.getAccentOverride(this)
        for (rowColors in ThemeManager.ACCENTS.chunked(6)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (color in rowColors) {
                val cell = FrameLayout(this)
                val dot = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        if (override == color) setStroke(dp(3), p.text)
                    }
                    setOnClickListener {
                        ThemeManager.setAccentOverride(this@SettingsActivity, color)
                        onChanged()
                    }
                }
                cell.addView(dot, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
                row.addView(cell, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            containerAccent.addView(row)
        }
        val reset = chip(getString(R.string.accent_from_theme), override == null, p) {
            ThemeManager.setAccentOverride(this, null)
            onChanged()
        }
        containerAccent.addView(reset, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
    }

    private fun buildPresets(p: Palette) {
        containerPresets.removeAllViews()
        val selectedId = ThemeManager.getPresetId(this)
        for (preset in ThemeManager.PRESETS) {
            val sc = if (p.isDark) preset.dark else preset.light
            val selected = preset.id == selectedId
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = ThemeManager.rounded(
                    sc.bg, dp(16).toFloat(),
                    if (selected) sc.accent else sc.surface,
                    if (selected) dp(3) else dp(2)
                )
                setOnClickListener {
                    ThemeManager.setPreset(this@SettingsActivity, preset.id)
                    onChanged()
                }
            }
            card.addView(TextView(this).apply {
                text = preset.name
                textSize = 16f
                setTextColor(sc.text)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            for (color in listOf(sc.surface, sc.text, sc.accent)) {
                card.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke(dp(1), sc.text and 0x00FFFFFF or 0x55000000)
                    }
                }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(6) })
            }
            containerPresets.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun chip(label: String, selected: Boolean, p: Palette, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor(if (selected) p.onAccent else p.text)
            background = ThemeManager.rounded(if (selected) p.accent else p.surface, dp(12).toFloat())
            setOnClickListener { onClick() }
        }
}
