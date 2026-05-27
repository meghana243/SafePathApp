package com.example.safepathapp

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class NewsActivity : AppCompatActivity() {

    private val crimeKeywords = listOf(
        "crime", "theft", "robbery", "assault",
        "murder", "rape", "violence", "kidnap",
        "attack", "fraud", "harassment"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news)

        // 1. Get data from MapActivity
        val city = intent.getStringExtra("CITY_NAME") ?: "Unknown Location"
        val allNews = intent.getStringArrayListExtra("NEWS_LIST") ?: arrayListOf()

        // 2. UI references
        val tvHeader = findViewById<TextView>(R.id.tvNewsHeader)
        val tvSubHeader = findViewById<TextView>(R.id.tvNewsSubHeader)
        val tvEmptyState = findViewById<TextView>(R.id.tvEmptyState)
        val listView = findViewById<ListView>(R.id.listViewNews)
        val btnBack = findViewById<Button>(R.id.btnBackToMap)

        // 3. Set header
        tvHeader.text = "🚨 Safety News: $city"

        // 4. Filter news
        val filteredNews = getFilteredNews(allNews, city)

        // 5. Handle UI based on result
        if (filteredNews.size == 1 &&
            filteredNews[0].startsWith("No recent or historical")) {

            tvSubHeader.text = "Historical crime reports"
            tvEmptyState.visibility = View.VISIBLE
            listView.visibility = View.GONE
            tvEmptyState.text = filteredNews[0]

        } else {
            tvSubHeader.text = "Safety reports for $city"
            tvEmptyState.visibility = View.GONE
            listView.visibility = View.VISIBLE

            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                filteredNews
            )
            listView.adapter = adapter
        }

        // 6. Back button
        btnBack.setOnClickListener {
            finish()
        }
    }

    /**
     * Filters news by destination.
     * If no recent news found, loads historical crime-related news.
     */
    private fun getFilteredNews(
        newsList: List<String>,
        location: String
    ): List<String> {

        // Destination-based news
        val locationNews = newsList.filter {
            it.contains(location, ignoreCase = true)
        }

        if (locationNews.isNotEmpty()) {
            return locationNews
        }

        // Historical crime-related news
        val crimeNews = newsList.filter { news ->
            news.contains(location, ignoreCase = true) &&
                    crimeKeywords.any { keyword ->
                        news.contains(keyword, ignoreCase = true)
                    }
        }

        return if (crimeNews.isNotEmpty()) {
            crimeNews
        } else {
            listOf("No recent or historical crime news found for this location.")
        }
    }
}
