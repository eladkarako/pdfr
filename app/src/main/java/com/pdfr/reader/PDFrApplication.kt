package com.karako.pdfr

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PDFrApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // PDFBox needs its bundled cmaps and font resources before it can parse
        // or save a document. PDFr uses it for metadata and decryption only.
        PDFBoxResourceLoader.init(this)
    }
}
