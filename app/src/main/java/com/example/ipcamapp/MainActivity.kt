package com.example.ipcamapp

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var repo: CameraRepository
    private lateinit var adapter: CameraAdapter
    private lateinit var emptyView: TextView
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var groupBar: View
    private lateinit var groupChips: ChipGroup
    private var titleText: TextView? = null
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    /**
     * Aktuális szűrő: null = összes, "" = csoport nélküliek, egyébként a csoport neve.
     * Megjegyezzük, hogy újraindítás után is ugyanaz a csoport legyen kiválasztva.
     */
    private var filter: String?
        get() = if (prefs.contains(KEY_FILTER)) prefs.getString(KEY_FILTER, null) else null
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_FILTER) else putString(KEY_FILTER, value) }.apply()
        }

    // A keresőből visszakapott kamerát előre kitöltve megnyitjuk a szerkesztőben
    private val discoverLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val json = res.data?.getStringExtra(DiscoveryActivity.EXTRA_CAMERA) ?: return@registerForActivityResult
        showEditor(null, Camera.fromJson(JSONObject(json)))
    }

    // QR beolvasás a + menüből: a kapott kamera felvétele
    private val scanLauncher = registerForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        val cam = Qr.parseCamera(contents)
        if (cam == null) {
            android.widget.Toast.makeText(this, R.string.qr_invalid, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            repo.save(cam); refresh()
            android.widget.Toast.makeText(this, getString(R.string.qr_added, cam.name), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        padForSystemBars()
        repo = CameraRepository(this)
        emptyView = findViewById(R.id.emptyView)
        groupBar = findViewById(R.id.groupBar)
        groupChips = findViewById(R.id.groupChips)

        // Kattintható cím az akciósávban: megnyitja a beállító menüt
        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayShowCustomEnabled(true)
            setCustomView(R.layout.actionbar_title)
            titleText = customView.findViewById(R.id.titleText)
            customView.setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }

        adapter = CameraAdapter(
            onClick = { cam ->
                startActivity(Intent(this, ViewerActivity::class.java)
                    .putExtra(ViewerActivity.EXTRA_ID, cam.id))
            },
            onLongClick = { cam -> showActions(cam) },
            onStartDrag = { holder -> touchHelper.startDrag(holder) },
        )
        val list = findViewById<RecyclerView>(R.id.cameraList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        // Átrendezés: a fogantyút megfogva fel-le húzható; elengedéskor mentjük a sorrendet
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false // a hosszú nyomás a menüé marad

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.apply { alpha = 0.85f; elevation = 8f * resources.displayMetrics.density }
                }
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                viewHolder.itemView.alpha = 1f
                viewHolder.itemView.elevation = 0f
                // szűrt nézetben csak a látható kamerák sorrendje változik, a többi a helyén marad
                repo.saveOrder(adapter.items)
            }
        })
        touchHelper.attachToRecyclerView(list)
        findViewById<FloatingActionButton>(R.id.addButton).setOnClickListener { showAddMenu() }
        tintAccent()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.findItem(R.id.action_discover)?.icon?.setTint(accentColor())
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_grid)?.isVisible = adapter.items.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_discover -> discoverLauncher.launch(Intent(this, DiscoveryActivity::class.java))
            R.id.action_grid -> startActivity(Intent(this, GridActivity::class.java).apply {
                // az osztott nézet az épp kiválasztott csoportot mutatja
                filter?.let { putExtra(GridActivity.EXTRA_GROUP, it) }
            })
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        tintAccent() // ha a beállításokban más kiemelőszínt választottak
        invalidateOptionsMenu()
        refresh()
    }

    private fun refresh() {
        val all = repo.getAll()
        val groups = repo.groups()
        val hasUngrouped = all.any { it.group.isEmpty() }

        // ha a kiválasztott csoport közben megszűnt, vissza az "Összes"-re
        val f = filter
        if (f != null && ((f.isEmpty() && (!hasUngrouped || groups.isEmpty())) || (f.isNotEmpty() && f !in groups))) {
            filter = null
        }

        buildChips(groups, hasUngrouped)
        adapter.showGroup = filter == null
        adapter.submit(repo.getByGroup(filter))
        emptyView.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        // A cím mindig az app neve (a nyelv szerint), a csoportszűrő nem változtatja meg
        titleText?.text = getString(R.string.app_name)
        invalidateOptionsMenu()
    }

    private fun buildChips(groups: List<String>, hasUngrouped: Boolean) {
        groupChips.setOnCheckedStateChangeListener(null)
        groupChips.removeAllViews()
        groupBar.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
        if (groups.isEmpty()) return

        val entries = buildList<Pair<String?, String>> {
            add(null to getString(R.string.group_all))
            groups.forEach { add(it to it) }
            if (hasUngrouped) add("" to getString(R.string.group_none))
        }
        val current = filter
        for ((key, label) in entries) {
            val chip = makeChip(label).apply {
                tag = key
                isChecked = key == current
                if (!key.isNullOrEmpty()) setOnLongClickListener { showGroupActions(key); true }
            }
            groupChips.addView(chip)
        }
        groupChips.setOnCheckedStateChangeListener { group, ids ->
            val chip = ids.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            filter = chip.tag as String?
            refresh()
        }
    }

    private fun makeChip(label: String) = Chip(this).apply {
        id = View.generateViewId()
        text = label
        isCheckable = true
        isCheckedIconVisible = false
        val accent = accentColor()
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        chipBackgroundColor = ColorStateList(states, intArrayOf(
            accent,
            ContextCompat.getColor(context, R.color.surface_variant)))
        setTextColor(ColorStateList(states, intArrayOf(
            accent.onAccentText(),
            ContextCompat.getColor(context, R.color.text_primary))))
    }

    private fun showGroupActions(group: String) {
        AlertDialog.Builder(this)
            .setTitle(group)
            .setItems(arrayOf(getString(R.string.rename_group), getString(R.string.dissolve_group))) { _, which ->
                if (which == 0) {
                    askText(R.string.rename_group, group) { newName ->
                        if (newName != group) {
                            repo.renameGroup(group, newName)
                            if (filter == group) filter = newName
                            refresh()
                        }
                    }
                } else {
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.confirm_dissolve, group))
                        .setPositiveButton(R.string.dissolve_group) { _, _ ->
                            repo.clearGroup(group)
                            if (filter == group) filter = null
                            refresh()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show().also { it.tintButtonsAccent(accentColor()) }
                }
            }
            .show()
    }

    /** Saját gyártói sablon felvétele (név, típus, útvonal, gyári felhasználónév). */
    private fun showAddPreset(onAdded: () -> Unit) {
        val v = layoutInflater.inflate(R.layout.dialog_preset, null)
        v.tintAccentTree(accentColor())
        val name = v.findViewById<TextInputEditText>(R.id.inputPresetName)
        val nameLayout = v.findViewById<TextInputLayout>(R.id.layoutPresetName)
        val typeView = v.findViewById<AutoCompleteTextView>(R.id.inputPresetType)
        val path = v.findViewById<TextInputEditText>(R.id.inputPresetPath)
        val userF = v.findViewById<TextInputEditText>(R.id.inputPresetUser)

        val types = StreamType.entries
        typeView.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, types.map { it.label }))
        var t = StreamType.RTSP
        typeView.setText(t.label, false)
        path.setText(t.defaultPath)
        typeView.setOnItemClickListener { _, _, pos, _ ->
            val old = t; t = types[pos]
            if (path.text.toString() == old.defaultPath) path.setText(t.defaultPath)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.add_preset_title)
            .setView(v)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.tintButtonsAccent(accentColor())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                if (n.isEmpty()) { nameLayout.error = getString(R.string.preset_name_required); return@setOnClickListener }
                PresetStore.add(this, Preset.user(n, t, path.text.toString().trim(), userF.text.toString().trim()))
                onAdded()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showActions(cam: Camera) {
        val actions = arrayOf(
            getString(R.string.edit),
            getString(R.string.move_to_group),
            getString(R.string.share_qr),
            getString(R.string.delete),
        )
        AlertDialog.Builder(this)
            .setTitle(cam.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showEditor(cam)
                    1 -> moveToGroup(cam)
                    2 -> shareQr(cam)
                    else -> AlertDialog.Builder(this)
                        .setMessage(getString(R.string.confirm_delete, cam.name))
                        .setPositiveButton(R.string.delete) { _, _ -> repo.delete(cam.id); refresh() }
                        .setNegativeButton(R.string.cancel, null)
                        .show().also { it.tintButtonsAccent(accentColor()) }
                }
            }
            .show()
    }

    /** A + gomb menüje bottom sheetként: kézi hozzáadás vagy QR-beolvasás. */
    private fun showAddMenu() {
        val view = layoutInflater.inflate(R.layout.sheet_add, null)
        val accent = accentColor()
        view.findViewById<android.widget.ImageView>(R.id.sheetManualIcon).setColorFilter(accent)
        view.findViewById<android.widget.ImageView>(R.id.sheetScanIcon).setColorFilter(accent)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.setContentView(view)
        // az alapértelmezett szürke hátteret átlátszóra tesszük, hogy csak a lekerekített sarkú
        // saját hátterünk látsszon
        sheet.setOnShowListener {
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundResource(android.R.color.transparent)
        }
        view.findViewById<View>(R.id.sheetManual).setOnClickListener { sheet.dismiss(); showEditor(null) }
        view.findViewById<View>(R.id.sheetScan).setOnClickListener { sheet.dismiss(); launchScan() }
        sheet.show()
    }

    private fun launchScan() {
        scanLauncher.launch(
            com.journeyapps.barcodescanner.ScanOptions()
                .setCaptureActivity(PortraitCaptureActivity::class.java)
                .setOrientationLocked(false)
                .setBeepEnabled(false)
                .setPrompt("")
        )
    }

    /** Egy kamera megosztása QR-kóddal (a másik telefon beolvassa). */
    private fun shareQr(cam: Camera) {
        val bmp = try { Qr.encode(Qr.buildCamera(cam)) } catch (_: Exception) { return }
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val img = android.widget.ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
        }
        val warn = TextView(this).apply {
            text = getString(R.string.qr_warning)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, pad, 0, 0)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(img)
            addView(warn)
        }
        AlertDialog.Builder(this)
            .setTitle(cam.name)
            .setView(box)
            .setPositiveButton(R.string.ok, null)
            .show().also { it.tintButtonsAccent(accentColor()) }
    }

    /** Gyors áthelyezés: meglévő csoport / új csoport / nincs csoport. */
    private fun moveToGroup(cam: Camera) {
        val groups = repo.groups()
        val options = groups + getString(R.string.new_group) + getString(R.string.no_group)
        val checked = if (cam.group.isEmpty()) options.size - 1 else groups.indexOf(cam.group)
        AlertDialog.Builder(this)
            .setTitle(R.string.move_to_group)
            .setSingleChoiceItems(options.toTypedArray(), checked) { d, which ->
                d.dismiss()
                when {
                    which < groups.size -> { repo.save(cam.copy(group = groups[which])); refresh() }
                    which == groups.size -> askText(R.string.new_group, "") { name ->
                        repo.save(cam.copy(group = name)); refresh()
                    }
                    else -> { repo.save(cam.copy(group = "")); refresh() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show().also { it.tintButtonsAccent(accentColor()) }
    }

    /** Egyszerű szövegbekérő ablak (csoportnévhez). */
    private fun askText(titleRes: Int, initial: String, onOk: (String) -> Unit) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
            hint = getString(R.string.group_name)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk)
            }
            .setNegativeButton(R.string.cancel, null)
            .show().also { it.tintButtonsAccent(accentColor()) }
    }

    /** [existing]: szerkesztés; [prefill]: új kamera előre kitöltött adatokkal (pl. keresésből). */
    private fun showEditor(existing: Camera?, prefill: Camera? = null) {
        val src = existing ?: prefill
        val v = layoutInflater.inflate(R.layout.dialog_camera, null)
        v.tintAccentTree(accentColor())
        val name = v.findViewById<TextInputEditText>(R.id.inputName)
        val group = v.findViewById<AutoCompleteTextView>(R.id.inputGroup)
        val host = v.findViewById<TextInputEditText>(R.id.inputHost)
        val hostLayout = v.findViewById<TextInputLayout>(R.id.layoutHost)
        val port = v.findViewById<TextInputEditText>(R.id.inputPort)
        val path = v.findViewById<TextInputEditText>(R.id.inputPath)
        val user = v.findViewById<TextInputEditText>(R.id.inputUser)
        val pass = v.findViewById<TextInputEditText>(R.id.inputPass)
        val typeView = v.findViewById<AutoCompleteTextView>(R.id.inputType)
        val presetView = v.findViewById<AutoCompleteTextView>(R.id.inputPreset)
        val testButton = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.testButton)
        val testProgress = v.findViewById<View>(R.id.testProgress)
        val testIcon = v.findViewById<android.widget.ImageView>(R.id.testIcon)
        val testResult = v.findViewById<TextView>(R.id.testResult)

        val types = StreamType.entries
        typeView.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, types.map { it.label }))
        var selectedType = src?.type ?: StreamType.RTSP
        typeView.setText(selectedType.label, false)
        typeView.setOnItemClickListener { _, _, pos, _ ->
            val old = selectedType
            selectedType = types[pos]
            // Ha a felhasználó nem írta át, frissítjük az alapértelmezett portot/útvonalat
            if (port.text.toString() == old.defaultPort.toString()) port.setText(selectedType.defaultPort.toString())
            if (path.text.toString() == old.defaultPath) path.setText(selectedType.defaultPath)
        }

        // (2) Gyártói kitöltő: beállítja a típust, az útvonalat és a gyári felhasználónevet (jelszó nélkül).
        // A lista bővíthető saját sablonokkal, az utolsó elem a „+ Új gyártó…”.
        fun applyPreset(p: Preset) {
            selectedType = p.type
            typeView.setText(p.type.label, false)
            port.setText(p.type.defaultPort.toString())
            path.setText(p.path)
            if (p.defaultUser.isNotEmpty()) user.setText(p.defaultUser)
            testResult.text = ""
        }
        fun refreshPresets() {
            val labels = Presets.labels(this) + getString(R.string.add_preset)
            presetView.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        }
        refreshPresets()
        presetView.setOnItemClickListener { _, _, pos, _ ->
            val presets = Presets.all(this)
            if (pos >= presets.size) {              // a „+ Új gyártó…” sor
                presetView.setText("", false)
                showAddPreset { refreshPresets() }
            } else {
                applyPreset(presets[pos])
            }
        }
        // saját sablon törlése: hosszú koppintás a mezőn
        presetView.setOnLongClickListener {
            val userPresets = PresetStore.getAll(this)
            if (userPresets.isEmpty()) return@setOnLongClickListener false
            AlertDialog.Builder(this)
                .setTitle(R.string.remove_preset)
                .setItems(userPresets.map { it.label!! }.toTypedArray()) { _, i ->
                    PresetStore.remove(this, userPresets[i].label!!)
                    refreshPresets()
                }
                .setNegativeButton(R.string.cancel, null)
                .show().also { it.tintButtonsAccent(accentColor()) }
            true
        }

        group.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, repo.groups()))
        // új kamera: ha épp egy csoport van kiválasztva, abba kerüljön alapból
        val defaultGroup = src?.group?.takeIf { it.isNotEmpty() } ?: filter?.takeIf { existing == null } ?: ""
        group.setText(defaultGroup, false)

        name.setText(src?.name ?: "")
        host.setText(src?.host ?: "")
        port.setText((src?.port ?: selectedType.defaultPort).toString())
        path.setText(src?.path ?: selectedType.defaultPath)
        user.setText(src?.username ?: "")
        pass.setText(src?.password ?: "")

        // (3) Legutóbbi belépés felajánlása: új kameránál, ha még nincs beírva felhasználónév
        val lastUser = prefs.getString(KEY_LAST_USER, "") ?: ""
        if (existing == null && src?.username.isNullOrEmpty() && lastUser.isNotEmpty()) {
            testResult.setTextColor(accentColor())
            testResult.text = getString(R.string.use_last_login, lastUser)
            testResult.setOnClickListener {
                user.setText(lastUser)
                pass.setText(Crypto.decrypt(prefs.getString(KEY_LAST_PASS, "") ?: ""))
                testResult.text = ""
                testResult.setOnClickListener(null)
            }
        }

        // (1) Kapcsolat tesztelése a beírt egy belépéssel – az eredményt ikon jelzi
        testButton.setOnClickListener {
            val h = host.text.toString().trim()
            if (h.isEmpty()) {
                android.widget.Toast.makeText(this, R.string.test_fill_host, android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val probe = Camera(
                name = "", host = h,
                port = port.text.toString().toIntOrNull() ?: selectedType.defaultPort,
                type = selectedType, path = path.text.toString().trim(),
                username = user.text.toString().trim(), password = pass.text.toString(),
            )
            testButton.isEnabled = false
            testProgress.visibility = View.VISIBLE
            testIcon.visibility = View.GONE
            testResult.text = ""
            testResult.setOnClickListener(null)
            Thread {
                val result = ConnectionTester.test(probe)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    testButton.isEnabled = true
                    testProgress.visibility = View.GONE
                    val (iconRes, descRes) = when (result) {
                        is ConnectionTester.Result.Ok -> R.drawable.ic_test_ok to R.string.test_ok
                        is ConnectionTester.Result.AuthFailed -> R.drawable.ic_test_auth to R.string.test_auth
                        is ConnectionTester.Result.Unreachable -> R.drawable.ic_test_unreachable to R.string.test_unreachable
                        is ConnectionTester.Result.Other -> R.drawable.ic_test_other to R.string.test_other
                    }
                    testIcon.setImageResource(iconRes)
                    testIcon.contentDescription = getString(descRes)
                    testIcon.visibility = View.VISIBLE
                    // a részletek (ikonra koppintva / egyéb hiba) buborékban olvashatók
                    val detail = (result as? ConnectionTester.Result.Other)?.message ?: getString(descRes)
                    testIcon.setOnClickListener {
                        android.widget.Toast.makeText(this, detail, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.add_camera else R.string.edit_camera)
            .setView(v)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.tintButtonsAccent(accentColor())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val h = host.text.toString().trim()
                if (h.isEmpty()) {
                    hostLayout.error = getString(R.string.error_host)
                    return@setOnClickListener
                }
                val cam = Camera(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name.text.toString().trim().ifEmpty { h },
                    host = h,
                    port = port.text.toString().toIntOrNull() ?: selectedType.defaultPort,
                    type = selectedType,
                    path = path.text.toString().trim(),
                    username = user.text.toString().trim(),
                    password = pass.text.toString(),
                    group = group.text.toString().trim(),
                )
                repo.save(cam)
                // (3) Legutóbbi belépés megjegyzése a következő kamerához
                if (cam.username.isNotEmpty()) {
                    prefs.edit()
                        .putString(KEY_LAST_USER, cam.username)
                        .putString(KEY_LAST_PASS, Crypto.encrypt(cam.password))
                        .apply()
                }
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private companion object {
        const val KEY_FILTER = "group_filter"
        const val KEY_LAST_USER = "last_user"
        const val KEY_LAST_PASS = "last_pass"
    }
}

private class CameraAdapter(
    private val onClick: (Camera) -> Unit,
    private val onLongClick: (Camera) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<CameraAdapter.VH>() {

    val items = mutableListOf<Camera>()

    /** "Összes" nézetben a csoport nevét is kiírjuk a kamera alá. */
    var showGroup = true

    fun submit(list: List<Camera>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        if (from < 0 || to < 0 || from == to) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.camName)
        val subtitle: TextView = v.findViewById(R.id.camUrl)
        val handle: View = v.findViewById(R.id.dragHandle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_camera, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cam = items[position]
        holder.title.text = cam.name
        val prefix = if (showGroup && cam.group.isNotEmpty()) "${cam.group} • " else ""
        holder.subtitle.text = "$prefix${cam.type.label} • ${cam.displayUrl()}"
        holder.itemView.setOnClickListener { onClick(cam) }
        holder.itemView.setOnLongClickListener { onLongClick(cam); true }
        holder.handle.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onStartDrag(holder)
            }
            false
        }
    }
}
