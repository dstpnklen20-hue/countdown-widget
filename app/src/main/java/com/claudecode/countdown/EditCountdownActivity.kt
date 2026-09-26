package com.claudecode.countdown

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.claudecode.countdown.data.CountdownRepository
import com.claudecode.countdown.widget.CountdownWidgetProvider
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class EditCountdownActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COUNTDOWN_ID = "countdown_id"
        const val RESULT_COUNTDOWN_ID = "countdown_id"
    }

    private var countdownId: String? = null
    private val calendar: Calendar = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private lateinit var editTitle: EditText
    private lateinit var textDate: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_countdown)
        ThemeManager.apply(this)

        editTitle = findViewById(R.id.edit_title)
        textDate = findViewById(R.id.text_selected_date)
        val btnPickDate = findViewById<Button>(R.id.btn_pick_date)
        val btnSave = findViewById<Button>(R.id.btn_save)
        val btnDelete = findViewById<Button>(R.id.btn_delete)

        countdownId = intent.getStringExtra(EXTRA_COUNTDOWN_ID)
        countdownId?.let { id ->
            CountdownRepository.get(this, id)?.let { countdown ->
                editTitle.setText(countdown.title)
                calendar.timeInMillis = countdown.targetMillis
                btnDelete.visibility = View.VISIBLE
            }
        }
        updateDateLabel()

        btnPickDate.setOnClickListener { showDatePicker() }

        btnSave.setOnClickListener {
            val title = editTitle.text.toString().trim()
            if (title.isEmpty()) {
                Toast.makeText(this, R.string.error_empty_title, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val savedId = CountdownRepository.save(this, countdownId, title, calendar.timeInMillis)
            val data = intent
            data.putExtra(RESULT_COUNTDOWN_ID, savedId)
            setResult(RESULT_OK, data)
            CountdownWidgetProvider.updateAllWidgets(this)
            finish()
        }

        btnDelete.setOnClickListener {
            countdownId?.let { CountdownRepository.delete(this, it) }
            setResult(RESULT_CANCELED)
            CountdownWidgetProvider.updateAllWidgets(this)
            finish()
        }
    }

    private fun showDatePicker() {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                calendar.set(year, month, day)
                showTimePicker()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showTimePicker() {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                updateDateLabel()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun updateDateLabel() {
        val fmt = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru"))
        textDate.text = fmt.format(calendar.time)
    }
}
