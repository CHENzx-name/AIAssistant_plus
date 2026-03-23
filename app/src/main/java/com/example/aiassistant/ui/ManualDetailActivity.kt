package com.example.aiassistant.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.aiassistant.R
import java.io.File
import java.nio.charset.StandardCharsets

class ManualDetailActivity : AppCompatActivity() {

    private lateinit var contentEditText: EditText
    private var filePath: String? = null
    private var isEditable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_manual_detail)

            // 显示返回箭头
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            // 显示编辑按钮
            supportActionBar?.setDisplayShowTitleEnabled(true)

            contentEditText = findViewById(R.id.manual_content)

            val appName = intent.getStringExtra("app_name")
            filePath = intent.getStringExtra("file_path")

            title = "${appName}操作说明"

            if (filePath != null) {
                loadManualContent(filePath!!)
                // 只有内部存储的说明书可以编辑
                isEditable = !filePath!!.contains("assets")
            } else {
                contentEditText.setText("未找到说明书文件路径")
                contentEditText.isEnabled = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 如果初始化失败，显示错误信息
            setContentView(R.layout.activity_manual_detail)
            contentEditText = findViewById(R.id.manual_content)
            contentEditText.setText("初始化失败: ${e.message}")
            contentEditText.isEnabled = false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        if (isEditable) {
            menuInflater.inflate(R.menu.manual_menu, menu)
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> {
                saveManualContent()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadManualContent(filePath: String) {
        try {
            if (filePath.contains("assets")) {
                // 从assets加载
                try {
                    var fileName = filePath
                    // 提取文件名，确保不包含路径
                    if (fileName.contains("/")) {
                        fileName = fileName.substring(fileName.lastIndexOf("/") + 1)
                    }
                    // 确保文件名不为空
                    if (fileName.isNotEmpty()) {
                        val inputStream = assets.open("manuals/$fileName")
                        val content = inputStream.bufferedReader().use { it.readText() }
                        contentEditText.setText(content)
                        contentEditText.isEnabled = false
                    } else {
                        contentEditText.setText("无效的说明书文件")
                        contentEditText.isEnabled = false
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    contentEditText.setText("加载默认说明书失败: ${e.message}")
                    contentEditText.isEnabled = false
                }
            } else {
                // 从内部存储加载
                try {
                    val file = File(filePath)
                    if (file.exists()) {
                        val content = file.readText(StandardCharsets.UTF_8)
                        contentEditText.setText(content)
                        contentEditText.isEnabled = true
                    } else {
                        contentEditText.setText("说明书文件不存在")
                        contentEditText.isEnabled = false
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    contentEditText.setText("加载用户说明书失败: ${e.message}")
                    contentEditText.isEnabled = false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            contentEditText.setText("加载说明书失败: ${e.message}")
            contentEditText.isEnabled = false
        }
    }

    private fun saveManualContent() {
        val path = filePath ?: return
        if (path.contains("assets")) {
            Toast.makeText(this, "无法修改assets中的说明书", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val file = File(path)
            val content = contentEditText.text.toString()
            file.writeText(content, StandardCharsets.UTF_8)
            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
