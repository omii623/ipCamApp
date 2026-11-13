package com.example.ipcamapp

import org.junit.Assert.assertEquals
import org.junit.Test

/** #2: sikeres újracsatlakozás után a "megjött a kép" jelzés újra lefut (eltűnik a hibaüzenet). */
class FirstFrameSignalTest {
    @Test fun firesOnceForContinuousStream() {
        val s = FirstFrameSignal()
        assertEquals(listOf(true, false, false), List(3) { s.onFrame() })
    }

    @Test fun firesAgainAfterErrorAndReconnect() {
        val s = FirstFrameSignal()
        s.onFrame()          // kép megy
        s.onError()          // kiesés -> hibaüzenet
        assertEquals(true, s.onFrame())   // újracsatlakozás: jelezni kell
        assertEquals(false, s.onFrame())
    }

    @Test fun errorBeforeAnyFrameStillFiresOnFirstFrame() {
        val s = FirstFrameSignal()
        s.onError(); s.onError()          // több sikertelen próbálkozás
        assertEquals(true, s.onFrame())
    }
}
