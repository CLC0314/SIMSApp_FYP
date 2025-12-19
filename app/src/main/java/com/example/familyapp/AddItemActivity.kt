// com.example.familyapp/AddItemActivity.kt

package com.example.familyapp

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.familyapp.data.InventoryItemFirestore
import com.example.familyapp.databinding.ActivityAddItemBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AddItemActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddItemBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = Firebase.firestore

    private var selectedExpiryDate: String? = null
    private var currentFamilyId: String? = null
    private var memberNameMap = mutableMapOf<String, String>()

    // 预设单位和分类
    private val unitOptions = listOf("Item", "Pack", "Kg", "Bottle", "Box")
    private val categoryOptions = listOf("Food", "Accessories", "Cleaning", "Medical")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddItemBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "添加新物品"

        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // 从 Intent 获取 Family ID
        currentFamilyId = intent.getStringExtra("FAMILY_ID")

        if (currentFamilyId.isNullOrEmpty()) {
            Toast.makeText(this, "错误：缺少家庭 ID，无法添加物品。", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupUI()
    }

    private fun setupUI() {
        // 设置单位下拉菜单
        setupDropdown(binding.actvUnit, unitOptions)
        binding.actvUnit.setText(unitOptions.first(), false)

        // 设置分类下拉菜单
        setupDropdown(binding.actvCategory, categoryOptions)
        binding.actvCategory.setText(categoryOptions.first(), false)

        // 加载家庭成员
        setupMemberSelector()

        // 日期选择
        binding.btnExpiryDate.setOnClickListener { showDatePicker() }

        // 归属切换逻辑
        binding.rgBelonging.setOnCheckedChangeListener { _, checkedId ->
            binding.tilOwner.visibility = if (checkedId == R.id.rbPrivate) View.VISIBLE else View.GONE
        }

        // 低库存预警切换
        binding.swLowStockAlert.setOnCheckedChangeListener { _, isChecked ->
            binding.tilLowStockValue.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // 保存按钮
        binding.btnAddItem.setOnClickListener { saveItemToFirestore() }
    }

    private fun setupMemberSelector() {
        db.collection("users").whereEqualTo("familyId", currentFamilyId).get()
            .addOnSuccessListener { snapshots ->
                memberNameMap.clear()
                val memberNames = mutableListOf<String>()
                for(doc in snapshots) {
                    val name = doc.getString("name") ?: "未知成员"
                    memberNameMap[name] = doc.id
                    memberNames.add(name)
                }
                setupDropdown(binding.actvOwner, memberNames)
            }
    }

    private fun setupDropdown(actv: AutoCompleteTextView, items: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items)
        actv.setAdapter(adapter)
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        val picker = DatePickerDialog(this, { _, year, month, day ->
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            calendar.set(year, month, day)
            selectedExpiryDate = sdf.format(calendar.time)
            binding.tvExpiryDateDisplay.text = "过期日期: $selectedExpiryDate"
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
        picker.show()
    }

    private fun saveItemToFirestore() {
        val name = binding.etItemName.text.toString().trim()
        val quantityStr = binding.etQuantity.text.toString().trim()
        val unit = binding.actvUnit.text.toString()
        val category = binding.actvCategory.text.toString()
        val remarks = binding.etRemarks.text.toString().trim()
        val isPrivate = binding.rbPrivate.isChecked
        val ownerName = binding.actvOwner.text.toString()

        if (name.isEmpty() || quantityStr.isEmpty()) {
            Toast.makeText(this, "请填写名称和数量", Toast.LENGTH_SHORT).show()
            return
        }

        val quantity = quantityStr.toIntOrNull() ?: 1
        val ownerId = if (isPrivate) memberNameMap[ownerName] else null

        // 🆕 这里的 InventoryItemFirestore 不要手动传入 id 字段
        val newItem = InventoryItemFirestore(
            familyId = currentFamilyId!!,
            name = name,
            category = category,
            quantity = quantity,
            unit = unit,
            expiryDate = selectedExpiryDate,
            ownerId = ownerId,
            ownerName = if (isPrivate) ownerName else null,
            notes = remarks
        )

        // 执行写入
        db.collection("inventory")
            .add(newItem) // 🔴 使用 add 自动生成文档 ID，且不手动更新 "id" 字段
            .addOnSuccessListener {
                Toast.makeText(this, "item '${name}' added successfully！", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}