package com.example.ipcamapp

import android.content.Context
import org.json.JSONArray

class CameraRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cameras", Context.MODE_PRIVATE)

    fun getAll(): List<Camera> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val cam = Camera.fromJson(o)
            // a titkosított jelszót visszafejtjük; a régi (titkosítatlan) adat marad, ahogy van
            if (o.optBoolean("pw_enc")) cam.copy(password = Crypto.decrypt(cam.password)) else cam
        }
    }

    fun get(id: String): Camera? = getAll().firstOrNull { it.id == id }

    /**
     * Kamerák egy csoport szerint szűrve.
     * [group] == null: összes; "" : csoport nélküliek; egyébként az adott csoport.
     */
    fun getByGroup(group: String?): List<Camera> =
        if (group == null) getAll() else getAll().filter { it.group == group }

    /** A létező csoportnevek, az első előfordulásuk sorrendjében. */
    fun groups(): List<String> = getAll().map { it.group }.filter { it.isNotEmpty() }.distinct()

    fun save(camera: Camera) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == camera.id }
        if (idx >= 0) list[idx] = camera else list.add(camera)
        persist(list)
    }

    /**
     * Új sorrend mentése. Ha csak egy szűrt részhalmazt rendeztek át (pl. egy csoporton belül),
     * a többi kamera a helyén marad, és a részhalmaz elemei az eredeti helyeikre kerülnek új sorrendben.
     */
    fun saveOrder(reordered: List<Camera>) = persist(applyReorder(getAll(), reordered))

    fun renameGroup(old: String, new: String) =
        persist(getAll().map { if (it.group == old) it.copy(group = new) else it })

    /** A csoport megszüntetése: a kamerák megmaradnak, csak csoport nélküliek lesznek. */
    fun clearGroup(group: String) = renameGroup(group, "")

    fun delete(id: String) = persist(getAll().filterNot { it.id == id })

    /** Import: a teljes lista felülírása. */
    fun replaceAll(cameras: List<Camera>) = persist(cameras)

    /** Import: összefésülés id szerint (meglévő frissül, új hozzáadódik). */
    fun addOrUpdateAll(cameras: List<Camera>) = persist(mergeById(getAll(), cameras))

    private fun persist(list: List<Camera>) {
        val arr = JSONArray()
        list.forEach { cam ->
            val o = cam.toJson()
            // a jelszót titkosítva tároljuk (a memóriában marad nyílt szövegként)
            o.put("password", Crypto.encrypt(cam.password))
            o.put("pw_enc", true)
            arr.put(o)
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "list"

        /** Összefésülés id szerint: a meglévő listában a [incoming] elemei frissítik az
         *  azonos id-jűeket, az újak a végére kerülnek. Tiszta függvény (teszteléshez). */
        fun mergeById(existing: List<Camera>, incoming: List<Camera>): List<Camera> {
            val list = existing.toMutableList()
            incoming.forEach { cam ->
                val idx = list.indexOfFirst { it.id == cam.id }
                if (idx >= 0) list[idx] = cam else list.add(cam)
            }
            return list
        }

        /** Átrendezés alkalmazása: a [reordered] (esetleg szűrt) részhalmaz elemei az eredeti
         *  helyeikre kerülnek az új sorrendben; a többi elem marad. Tiszta függvény. */
        fun applyReorder(all: List<Camera>, reordered: List<Camera>): List<Camera> {
            val ids = reordered.map { it.id }.toSet()
            val next = reordered.iterator()
            return all.map { if (it.id in ids && next.hasNext()) next.next() else it }
        }
    }
}
