package com.example.safepathapp

import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

class ViewIncidentsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        setContentView(webView)

        // The URL from your Google Sheet (File > Share > Publish to web > Web Page)
        val publishedSheetUrl = "https://docs.google.com/spreadsheets/d/e/2PACX-1vSo80wiyMSVxtTChr0eRCOLn3gTDYwInErtIKWo2XZemm4nT3jjDLVYEaoUxuLJ5GQR751M_ojwrYwI/pubhtml"

        webView.settings.javaScriptEnabled = true
        webView.loadUrl(publishedSheetUrl)
    }
}