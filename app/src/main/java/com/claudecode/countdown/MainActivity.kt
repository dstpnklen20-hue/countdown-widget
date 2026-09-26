package com.claudecode.countdown

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.claudecode.countdown.data.CountdownRepository
import com.claudecode.countdown.model.Countdown
import com.claudecode.countdown.widget.CountdownWidgetProvider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var adapter: CountdownAdapter
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tickRunnable: Runnable

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.list_countdowns)
        emptyView = findViewById(R.id.text_empty)
        val fab = findViewById<FloatingActionButton>(R.id.fab_add)
        val fabSettings = findViewById<FloatingActionButton>(R.id.fab_settings)

        adapter = CountdownAdapter(this, mutableListOf())
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val countdown = adapter.getItem(position) ?: return@setOnItemClickListener
            val intent = Intent(this, EditCountdownActivity::class.java)
            intent.putExtra(EditCountdownActivity.EXTRA_COUNTDOWN_ID, countdown.id)
            editLauncher.launch(intent)
        }

        fab.setOnClickListener {
            editLauncher.launch(Intent(this, EditCountdownActivity::class.java))
        }

        fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (savedInstanceState == null) {
            Updater.checkForUpdates(this, manual = false)
        }

        tickRunnable = object : Runnable {
            override fun run() {
                adapter.notifyDataSetChanged()
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Theme may have changed in SettingsActivity.
        ThemeManager.apply(this)
        adapter.palette = ThemeManager.palette(this)
        refreshList()
        handler.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickRunnable)
    }

    private fun refreshList() {
        val data = CountdownRepository.getAll(this)
        adapter.replace(data)
        emptyView.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
        CountdownWidgetProvider.updateAllWidgets(this)
    }
}

private class CountdownAdapter(
    private val context: MainActivity,
    private var items: MutableList<Countdown>
) : ArrayAdapter<Countdown>(context, 0, items) {

    var palette: ThemeManager.Palette = ThemeManager.palette(context)

    fun replace(newItems: List<Countdown>) {
        items = newItems.toMutableList()
        clear()
        addAll(items)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_countdown, parent, false)
        val countdown = getItem(position) ?: return view

        val titleView = view.findViewById<TextView>(R.id.item_title)
        val remainingView = view.findViewById<TextView>(R.id.item_remaining)
        val dateView = view.findViewById<TextView>(R.id.item_date)

        titleView.text = countdown.title
        dateView.text = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru")).format(countdown.targetMillis)
        remainingView.text = formatRemaining(countdown.targetMillis)
        ThemeManager.paint(view, palette)

        return view
    }

    private fun formatRemaining(targetMillis: Long): String {
        val remaining = targetMillis - System.currentTimeMillis()
        if (remaining <= 0) return context.getString(R.string.arrived)

        val days = TimeUnit.MILLISECONDS.toDays(remaining)
        val hours = TimeUnit.MILLISECONDS.toHours(remaining) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remaining) % 60

        return if (days > 0) {
            "$days ${pluralRu(days, "день", "дня", "дней")} $hours ч $minutes мин"
        } else {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        }
    }
}
