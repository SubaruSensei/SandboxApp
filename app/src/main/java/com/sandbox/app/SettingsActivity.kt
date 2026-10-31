package com.sandbox.app

import android.content.Context
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val spinnerResolution = findViewById<Spinner>(R.id.spinnerResolution)
        val editDpi = findViewById<EditText>(R.id.editDpi)
        val spinnerOrientation = findViewById<Spinner>(R.id.spinnerOrientation)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)

        val resolutions = arrayOf("Native (Auto)", "1280x720", "1920x1080", "800x600", "1024x768")
        spinnerResolution.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resolutions)

        val orientations = arrayOf("Landscape (Horizontal)", "Portrait (Vertical)")
        spinnerOrientation.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, orientations)

        val prefs = getSharedPreferences("sandbox_settings", Context.MODE_PRIVATE)
        editDpi.setText(prefs.getInt("dpi", 160).toString())
        spinnerResolution.setSelection(prefs.getInt("res_index", 0))
        spinnerOrientation.setSelection(prefs.getInt("ori_index", 0))

        btnSave.setOnClickListener {
            val editor = prefs.edit()
            editor.putInt("dpi", editDpi.text.toString().toIntOrNull() ?: 160)
            editor.putInt("res_index", spinnerResolution.selectedItemPosition)
            editor.putInt("ori_index", spinnerOrientation.selectedItemPosition)
            editor.apply()

            Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
