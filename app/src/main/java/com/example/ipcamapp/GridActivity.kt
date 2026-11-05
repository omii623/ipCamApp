package com.example.ipcamapp

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.ceil
import kotlin.math.sqrt

/** Osztott képernyős nézet: az összes kamera egyszerre, rácsban. */
class GridActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val tiles = mutableListOf<CameraTile>()
    private var columnsOverride: Int? = null
    private var group: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grid)
        padForSystemBars()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // csoport szűrő: nincs extra = összes, "" = csoport nélküliek, egyébként a csoport neve
        group = if (intent.hasExtra(EXTRA_GROUP)) intent.getStringExtra(EXTRA_GROUP) else null
        title = when {
            group == null -> getString(R.string.split_view)
            group!!.isEmpty() -> "${getString(R.string.split_view)} – ${getString(R.string.group_none)}"
            else -> "${getString(R.string.split_view)} – $group"
        }
        container = findViewById(R.id.gridContainer)
        columnsOverride = savedInstanceState?.getInt(KEY_COLS)?.takeIf { it > 0 }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_COLS, columnsOverride ?: 0)
    }

    override fun onStart() {
        super.onStart()
        buildGrid()
    }

    override fun onStop() {
        super.onStop()
        tiles.forEach { it.stop() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_grid, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val cols = when (item.itemId) {
            R.id.cols_auto -> 0
            R.id.cols_1 -> 1
            R.id.cols_2 -> 2
            R.id.cols_3 -> 3
            android.R.id.home -> { finish(); return true }
            else -> return super.onOptionsItemSelected(item)
        }
        columnsOverride = cols.takeIf { it > 0 }
        tiles.forEach { it.stop() }
        buildGrid()
        return true
    }

    private fun autoColumns(count: Int): Int {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val base = ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(1)
        return if (landscape) base.coerceAtMost(4) else base.coerceAtMost(2)
    }

    private fun buildGrid() {
        tiles.clear()
        container.removeAllViews()
        val cameras = CameraRepository(this).getByGroup(group)
        if (cameras.isEmpty()) { finish(); return }

        val cols = (columnsOverride ?: autoColumns(cameras.size)).coerceAtMost(cameras.size)
        val tileWidth = resources.displayMetrics.widthPixels / cols
        val tileHeight = tileWidth * 9 / 16

        cameras.chunked(cols).forEach { rowCams ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowCams.forEach { cam ->
                val tile = CameraTile(this, row, cam)
                tile.view.layoutParams = LinearLayout.LayoutParams(tileWidth, tileHeight)
                tile.view.setOnClickListener {
                    startActivity(Intent(this, ViewerActivity::class.java)
                        .putExtra(ViewerActivity.EXTRA_ID, cam.id))
                }
                row.addView(tile.view)
                tiles += tile
            }
            container.addView(row)
        }
        tiles.forEach { it.start() }
    }

    companion object {
        const val EXTRA_GROUP = "group"
        private const val KEY_COLS = "cols"
    }
}
