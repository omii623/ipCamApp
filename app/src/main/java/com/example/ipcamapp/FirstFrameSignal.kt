package com.example.ipcamapp

/**
 * Mikor kell jelezni, hogy "megjött a kép" (a töltés / hibaüzenet eltüntetéséhez)?
 * - az első képkockánál,
 * - és minden hiba UTÁN az első képkockánál is, különben egy sikeres újracsatlakozás után
 *   a hibaüzenet a futó videó fölött maradna.
 * Egyetlen szálról használandó (a stream munkaszála).
 */
class FirstFrameSignal {
    private var armed = true

    /** Képkocka érkezett: true, ha most kell jelezni. */
    fun onFrame(): Boolean {
        if (!armed) return false
        armed = false
        return true
    }

    /** Hiba történt (a hibaüzenet kint van): a következő képkockánál újra jelezni kell. */
    fun onError() { armed = true }
}
