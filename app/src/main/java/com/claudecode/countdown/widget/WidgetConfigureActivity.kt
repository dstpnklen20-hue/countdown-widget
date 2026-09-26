package com.claudecode.countdown.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.claudecode.countdown.EditCountdownActivity
import com.claudecode.countdown.R
import com.claudecode.countdown.data.CountdownRepository
import com.claudecode.countdown.model.Countdown
import java.text.SimpleDateFormat
import java.util.Locale

class WidgetConfigureActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var countdowns: List<Countdown> = emptyList()

    private val createLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = result.data?.getStringExtra(EditCountdownActivity.RESULT_COUNTDOWN_ID)
        if (result.resultCode == RESULT_OK && id != null) {
            finishConfiguring(id)
        } else {
            loadCountdowns()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_configure)

        appWidgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val listView = findViewById<ListView>(R.id.list_configure)
        val btnCreate = findViewById<Button>(R.id.btn_create_new)

        listView.setOnItemClickListener { _, _, position, _ ->
            finishConfiguring(countdowns[position].id)
        }

        btnCreate.setOnClickListener {
            createLauncher.launch(Intent(this, EditCountdownActivity::class.java))
        }

        loadCountdowns()
    }

    private fun loadCountdowns() {
        countdowns = CountdownRepository.getAll(this)
        val listView = findViewById<ListView>(R.id.list_configure)
        val fmt = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru"))
        val labels = countdowns.map { "${it.title}\n${fmt.format(it.targetMillis)}" }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun finishConfiguring(countdownId: String) {
        CountdownRepository.setWidgetCountdown(this, appWidgetId, countdownId)
        val manager = AppWidgetManager.getInstance(this)
        CountdownWidgetProvider.updateWidget(this, manager, appWidgetId)
        WidgetUpdateScheduler.schedule(this)

        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
