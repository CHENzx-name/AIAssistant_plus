package com.example.aiassistant.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.aiassistant.R
import java.io.File

class ManualsActivity : AppCompatActivity() {

    private lateinit var manualsListView: ListView
    private lateinit var emptyTextView: TextView
    private val manualsList = mutableListOf<String>()
    private val manualFiles = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manuals)

        // 显示返回箭头
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "应用说明书"

        manualsListView = findViewById(R.id.manuals_list_view)
        emptyTextView = findViewById(R.id.empty_text_view)

        loadManuals()
        setupListView()
    }

    private fun loadManuals() {
        // 清空列表
        manualsList.clear()
        manualFiles.clear()
        
        // 从内部存储加载说明书
        val manualsDir = File(filesDir, "manuals")
        if (manualsDir.exists()) {
            val files = manualsDir.listFiles { _, name -> name.endsWith(".md") }
            if (files != null && files.isNotEmpty()) {
                for (file in files) {
                    val fileName = file.name
                    val appName = fileName.replace("_manual.md", "").replace("_", " ")
                    manualsList.add(appName)
                    manualFiles.add(file)
                }
            }
        }

        // 从assets加载说明书
        try {
            val assetsManuals = assets.list("manuals")
            if (assetsManuals != null) {
                for (fileName in assetsManuals) {
                    if (!fileName.endsWith(".md")) continue
                    val appName = fileName.replace("_manual.md", "").replace("_", " ")
                    if (!manualsList.contains(appName)) {
                        manualsList.add(appName)
                        manualFiles.add(File("assets/$fileName"))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupListView() {
        try {
            if (manualsList.isEmpty()) {
                manualsListView.visibility = View.GONE
                emptyTextView.visibility = View.VISIBLE
            } else {
                manualsListView.visibility = View.VISIBLE
                emptyTextView.visibility = View.GONE

                val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, manualsList)
                manualsListView.adapter = adapter

                manualsListView.onItemClickListener = AdapterView.OnItemClickListener {
                    _, _, position, _ ->
                    try {
                        if (position < manualFiles.size && position < manualsList.size) {
                            val selectedFile = manualFiles[position]
                            val intent = Intent(this, ManualDetailActivity::class.java)
                            intent.putExtra("file_path", selectedFile.absolutePath)
                            intent.putExtra("app_name", manualsList[position])
                            startActivity(intent)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            manualsListView.visibility = View.GONE
            emptyTextView.visibility = View.VISIBLE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
