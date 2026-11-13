package com.example.ipcamapp

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.json.JSONObject

/** Teljes értékű beállítások oldal: téma, nyelv és kiemelőszín. */
class SettingsActivity : AppCompatActivity() {

    private var ready = false
    private lateinit var preview: android.view.View
    private lateinit var hue: SeekBar

    // Export: fájl létrehozása (SAF), majd beleírjuk a mentést
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { writeExport(it) } }

    // Import: fájl kiválasztása (SAF), beolvasás és visszaállítás
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readImport(it) } }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        padForSystemBars()
        setTitle(R.string.settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val themeGroup = findViewById<RadioGroup>(R.id.themeGroup)
        val langGroup = findViewById<RadioGroup>(R.id.langGroup)
        preview = findViewById(R.id.accentPreview)
        hue = findViewById(R.id.hueSlider)

        themeGroup.check(when (Settings.nightMode(this)) {
            AppCompatDelegate.MODE_NIGHT_NO -> R.id.themeLight
            AppCompatDelegate.MODE_NIGHT_YES -> R.id.themeDark
            else -> R.id.themeSystem
        })

        val lang = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            .substringBefore('-').lowercase()
        langGroup.check(when (lang) {
            "hu" -> R.id.langHu
            "en" -> R.id.langEn
            else -> R.id.langSystem
        })

        setupHueSlider()
        setupSearchPorts()
        setupData()
        setupAbout()
        applyAccentToScreen()
        ready = true

        themeGroup.setOnCheckedChangeListener { _, id ->
            if (!ready) return@setOnCheckedChangeListener
            Settings.setNightMode(this, when (id) {
                R.id.themeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.themeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }) // az oldal újraindul az új témával
        }

        langGroup.setOnCheckedChangeListener { _, id ->
            if (!ready) return@setOnCheckedChangeListener
            AppCompatDelegate.setApplicationLocales(when (id) {
                R.id.langHu -> LocaleListCompat.forLanguageTags("hu")
                R.id.langEn -> LocaleListCompat.forLanguageTags("en")
                else -> LocaleListCompat.getEmptyLocaleList()
            }) // az oldal újraindul az új nyelvvel
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    /** Alapértelmezett keresési portok: mentés, ha érvényes. */
    private fun setupSearchPorts() {
        val layout = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.searchPortsLayout)
        val input = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.searchPortsInput)
        input.setText(Settings.searchPorts(this))
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString().orEmpty()
                try {
                    val ports = PortScanner.parsePorts(text)
                    layout.error = null
                    Settings.setSearchPorts(this@SettingsActivity, ports.joinToString(", "))
                } catch (e: PortScanner.RangeException) {
                    layout.error = e.message(this@SettingsActivity) // érvénytelen értéket nem mentünk
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    /** Adatok szekció: export / import / QR beolvasás. */
    private fun setupData() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.exportButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.export_warning)
                .setPositiveButton(R.string.export) { _, _ ->
                    val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                    exportLauncher.launch("ipcam-backup-$date.json")
                }
                .setNegativeButton(R.string.cancel, null)
                .show().also { it.tintButtonsAccent(accentColor()) }
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.importButton).setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }
    }

    private fun writeExport(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(Backup.export(this).toString(2).toByteArray())
            }
            toast(getString(R.string.backup_ok))
        } catch (_: Exception) {
            toast(getString(R.string.backup_failed))
        }
    }

    private fun readImport(uri: Uri) {
        val root = try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            JSONObject(text)
        } catch (_: Exception) {
            toast(getString(R.string.import_invalid)); return
        }
        if (!Backup.isValid(root)) { toast(getString(R.string.import_invalid)); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.import_choose)
            .setItems(arrayOf(getString(R.string.import_merge), getString(R.string.import_replace))) { _, which ->
                try {
                    Backup.import(this, root, replace = which == 1)
                    relaunch() // téma/nyelv/szín is változhatott
                } catch (_: Exception) {
                    toast(getString(R.string.backup_failed))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show().also { it.tintButtonsAccent(accentColor()) }
    }

    /** Újraindítás a főképernyőre (az importált téma/nyelv/szín így érvényesül mindenhol). */
    private fun relaunch() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finishAffinity()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Névjegy: app neve + verzió + egyedi build-hash, koppintásra rövid leírás. */
    private fun setupAbout() {
        val about = findViewById<android.widget.TextView>(R.id.aboutText)
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.0"
        val hash = BuildConfig.BUILD_HASH
        val line = getString(R.string.about_version, getString(R.string.app_name), version)
        about.text = "$line\nbuild $hash"
        about.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.about)
                .setMessage(line + "\nbuild " + hash + "\n\n" + getString(R.string.about_text))
                .setPositiveButton(R.string.ok, null)
                .show().also { it.tintButtonsAccent(accentColor()) }
        }
    }

    /** A kiválasztott szín beállítása, és az oldal azonnali átszínezése (újraindítás nélkül). */
    private fun chooseAccent(color: Int) {
        Settings.setAccentColor(this, color)
        applyAccentToScreen()
    }

    private fun setupHueSlider() {
        // alapérték: az aktuális kiemelőszín árnyalata (beállítatlanul a türkiz alap)
        val hsv = FloatArray(3)
        Color.colorToHSV(accentColor(), hsv)
        hue.progress = hsv[0].toInt()
        hue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                // élénk, de nem neon szín a csúszka értékéből
                chooseAccent(Color.HSVToColor(floatArrayOf(value.toFloat(), 0.72f, 0.88f)))
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    /** Az oldal elemeinek átszínezése az aktuális kiemelőszínnel. */
    private fun applyAccentToScreen() {
        val color = accentColor()
        tintAccent()
        preview.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        val csl = ColorStateList.valueOf(color)
        hue.progressTintList = csl
        hue.thumbTintList = csl
        // a rádiógombok kijelölő színe is kövesse a kiemelőszínt
        val radioCsl = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(color, themeColor(android.R.attr.textColorSecondary)))
        listOf<RadioGroup>(findViewById(R.id.themeGroup), findViewById(R.id.langGroup)).forEach { g ->
            for (i in 0 until g.childCount) {
                (g.getChildAt(i) as? android.widget.RadioButton)?.buttonTintList = radioCsl
            }
        }
    }
}
