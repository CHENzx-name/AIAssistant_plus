package com.example.aiassistant.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
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
                    var appName = fileName
                    // 处理不同的文件命名格式
                    if (appName.endsWith("_manual.md")) {
                        appName = appName.replace("_manual.md", "")
                    } else if (appName.endsWith("_manal.md")) {
                        appName = appName.replace("_manal.md", "")
                    } else {
                        appName = appName.replace(".md", "")
                    }
                    // 替换下划线为空格
                    appName = appName.replace("_", " ")
                    // 在默认文件夹中的说明书标题前面加"默认"
                    val displayName = "默认 $appName"
                    if (!manualsList.contains(displayName)) {
                        manualsList.add(displayName)
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
            
            // 添加长按菜单，用于编辑用户创建的说明书
            manualsListView.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, view, position, _ ->
                if (position < manualFiles.size && position < manualsList.size) {
                    val selectedFile = manualFiles[position]
                    // 只有内部存储的说明书可以编辑，assets中的说明书不能编辑
                    if (!selectedFile.absolutePath.contains("assets")) {
                        showEditMenu(view, selectedFile, position)
                        return@OnItemLongClickListener true
                    }
                }
                return@OnItemLongClickListener false
            }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            manualsListView.visibility = View.GONE
            emptyTextView.visibility = View.VISIBLE
        }
    }

    /**
     * 显示编辑菜单
     * @param anchorView 长按的视图
     * @param file 要编辑的文件
     * @param position 文件在列表中的位置
     */
    private fun showEditMenu(anchorView: View, file: File, position: Int) {
        val popupMenu = PopupMenu(this, anchorView)
        popupMenu.menuInflater.inflate(R.menu.manual_list_menu, popupMenu.menu)
        
        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> {
                    editManual(file, position)
                    true
                }
                R.id.action_delete -> {
                    deleteManual(file, position)
                    true
                }
                else -> false
            }
        }
        
        popupMenu.show()
    }
    
    /**
     * 编辑说明书
     * @param file 要编辑的文件
     * @param position 文件在列表中的位置
     */
    private fun editManual(file: File, position: Int) {
        val intent = Intent(this, ManualDetailActivity::class.java)
        intent.putExtra("file_path", file.absolutePath)
        intent.putExtra("app_name", manualsList[position])
        startActivity(intent)
    }
    
    /**
     * 删除说明书
     * @param file 要删除的文件
     * @param position 文件在列表中的位置
     */
    private fun deleteManual(file: File, position: Int) {
        try {
            if (file.delete()) {
                // 删除成功，更新列表
                manualsList.removeAt(position)
                manualFiles.removeAt(position)
                loadManuals()
                setupListView()
                
                Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}