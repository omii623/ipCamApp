package com.example.ipcamapp

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * A ZXing beolvasó álló tájolásban (a manifestben screenOrientation="portrait"),
 * saját elrendezéssel, ahol a felirat feljebb, a képernyő tetején jelenik meg.
 */
class PortraitCaptureActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.zxing_portrait)
        return findViewById(R.id.zxing_barcode_scanner)
    }
}
