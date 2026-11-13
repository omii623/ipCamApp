package com.example.ipcamapp

import android.content.Context
import org.json.JSONArray

class CameraRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cameras", Context.MODE_PRIVATE)

    /**
     * Kamerák, amelyek jelszava nem fejthető vissza (id -> az eredeti titkosított érték).
     * Ezeket mentéskor változatlanul visszaírjuk, amíg a felhasználó új jelszót nem ad meg,
     * így egy átmeneti / eszközváltás miatti hiba nem törli véglegesen a jelszót.
     */
    private val locked = mutableMapOf<String, String>()

    /** Hány kamera jelszava nem fejthető vissza (pl. telefonváltás után). */
    fun lockedCount(): Int { getAll(); return locked.size }

    fun getAll(): List<Camera> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        locked.clear()
        return (0 until arr.length()).map { i ->
            val (cam, blob) = decodeEntry(arr.getJSONObject(i), Crypto::decrypt)
            if (blob != null) locked[cam.id] = blob
            cam
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
        // a jelszót titkosítva tároljuk (a memóriában nyílt szövegként van)
        list.forEach { cam -> arr.put(encodeEntry(cam, locked[cam.id], Crypto::encrypt)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "list"

        /**
         * Egy tárolt bejegyzés -> kamera. Ha a jelszó titkosított, de nem fejthető vissza,
         * a kamera üres jelszót kap, a második érték pedig az eredeti titkosított adat (különben null).
         * Tiszta függvény (a titkosítás kívülről jön) – unit-tesztelhető.
         */
        fun decodeEntry(o: org.json.JSONObject, decrypt: (String) -> String?): Pair<Camera, String?> {
            val cam = Camera.fromJson(o)
            if (!o.optBoolean("pw_enc") || cam.password.isEmpty()) return cam to null
            val plain = decrypt(cam.password)
            return if (plain != null) cam.copy(password = plain) to null
                   else cam.copy(password = "") to cam.password
        }

        /**
         * Kamera -> tárolt bejegyzés.
         * - üres jelszó + [lockedBlob]: a vissza nem fejthető eredeti marad (nem írjuk felül üressel),
         * - sikeres titkosítás: titkosítva,
         * - sikertelen titkosítás: nyíltan (pw_enc=false) – inkább ez, mint a jelszó elvesztése.
         */
        fun encodeEntry(cam: Camera, lockedBlob: String?, encrypt: (String) -> String?): org.json.JSONObject {
            val o = cam.toJson()
            when {
                cam.password.isEmpty() && lockedBlob != null -> { o.put("password", lockedBlob); o.put("pw_enc", true) }
                cam.password.isEmpty() -> o.put("pw_enc", false)
                else -> {
                    val enc = encrypt(cam.password)
                    if (enc != null) { o.put("password", enc); o.put("pw_enc", true) }
                    else o.put("pw_enc", false)
                }
            }
            return o
        }

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
