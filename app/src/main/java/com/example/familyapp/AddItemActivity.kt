// com.example.familyapp/AddItemActivity.kt

package com.example.familyapp

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.familyapp.data.InventoryItemFirestore
import com.example.familyapp.databinding.ActivityAddItemBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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
    private val categoryOptions = mutableListOf("Food", "Accessories", "Cleaning", "Medical")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddItemBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Add New Inventory Item"

        // 检查用户和 familyId
        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // 必须从 Intent 中获取 Family ID
        currentFamilyId = intent.getStringExtra("FAMILY_ID")
        if (currentFamilyId == null) {
            Toast.makeText(this, "Error: Family ID missing.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // 1. 设置单位下拉菜单
        setupDropdown(binding.actvUnit, unitOptions)
        binding.actvUnit.setText(unitOptions.first(), false) // 默认值

        // 2. 设置分类下拉菜单 (TODO: 后续从 Firestore 动态加载)
        setupDropdown(binding.actvCategory, categoryOptions)
        binding.actvCategory.setText(categoryOptions.first(), false) // 默认值

        // 3. 设置成员选择器 (如果 Private)
        setupMemberSelector()

        // 4. Expiry Date 按钮点击事件
        binding.btnExpiryDate.setOnClickListener {
            showDatePicker()
        }

        // 5. Belonging Radio Group 切换事件
        binding.rgBelonging.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbPrivate) {
                binding.tilOwner.visibility = View.VISIBLE
            } else {
                binding.tilOwner.visibility = View.GONE
            }
        }

        // 6. Low Stock Switch 切换事件
        binding.swLowStockAlert.setOnCheckedChangeListener { _, isChecked ->
            binding.tilLowStockValue.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // 7. 添加物品按钮
        binding.btnAddItem.setOnClickListener {
            saveItemToFirestore()
        }
    }

    /**
     * 加载家庭成员列表以供私有物品选择
     */
    private fun setupMemberSelector() {
        // 获取成员列表（这里我们简化为只获取当前家庭的成员）
        db.collection("users").whereEqualTo("familyId", currentFamilyId).get()
            .addOnSuccessListener { snapshots ->
                memberNameMap.clear()
                val memberNames = mutableListOf<String>()

                for(doc in snapshots) {
                    val uid = doc.id
                    val name = doc.getString("name") ?: "Unknown Member"
                    memberNameMap[name] = uid // Name -> UID 映射
                    memberNames.add(name)
                }

                setupDropdown(binding.actvOwner, memberNames)
                if (memberNames.isNotEmpty()) {
                    binding.actvOwner.setText(memberNames.first(), false) // 默认选择第一个成员
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load family members.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupDropdown(actv: AutoCompleteTextView, items: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items)
        actv.setAdapter(adapter)
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val picker = DatePickerDialog(this, { _, selectedYear, selectedMonth, selectedDay ->
            val selectedCalendar = Calendar.getInstance().apply {
                set(selectedYear, selectedMonth, selectedDay)
            }
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            selectedExpiryDate = sdf.format(selectedCalendar.time)

            binding.tvExpiryDateDisplay.text = "Expiry Date: $selectedExpiryDate"
        }, year, month, day)

        picker.show()
    }

    /**
     * 验证输入并写入 Firestore
     */
    private fun saveItemToFirestore() {
        val name = binding.etItemName.text.toString().trim()
        val quantityStr = binding.etQuantity.text.toString().trim()
        val unit = binding.actvUnit.text.toString()
        val category = binding.actvCategory.text.toString()
        val remarks = binding.etRemarks.text.toString().trim()
        val isPrivate = binding.rbPrivate.isChecked
        val ownerName = binding.actvOwner.text.toString()

        // 1. 基础验证
        if (name.isEmpty() || quantityStr.isEmpty()) {
            Toast.makeText(this, "Item Name and Quantity are required.", Toast.LENGTH_SHORT).show()
            return
        }
        val quantity = quantityStr.toIntOrNull()
        if (quantity == null || quantity <= 0) {
            Toast.makeText(this, "Quantity must be a positive number.", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. 确定 Owner ID
        val ownerId: String? = if (isPrivate) {
            memberNameMap[ownerName]
        } else {
            null
        }

        if (isPrivate && ownerId == null) {
            Toast.makeText(this, "Please select a valid member for private item.", Toast.LENGTH_SHORT).show()
            return
        }

        // 3. 构建 Firestore 数据对象
        val newItem = InventoryItemFirestore(
            // Firestore 会自动分配 ID，这里为空
            familyId = currentFamilyId!!,
            name = name,
            category = category,
            quantity = quantity,
            unit = unit,
            expiryDate = selectedExpiryDate,
            ownerId = ownerId,
            ownerName = if (isPrivate) ownerName else null,
            notes = remarks,
        )

        // TODO: 处理 Low Stock Alert 的存储逻辑 (这需要添加到 InventoryItemFirestore 模型中)
        val lowStockEnabled = binding.swLowStockAlert.isChecked
        val lowStockValue = binding.etLowStockValue.text.toString().toIntOrNull()

        // 4. 写入 Firestore
        db.collection("inventory")
            .add(newItem)
            .addOnSuccessListener { documentReference ->
                // 成功后，更新文档 ID (可选，但推荐)
                documentReference.update("id", documentReference.id)

                // 成功后返回主界面
                Toast.makeText(this, "Item '${name}' added successfully!", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error saving item: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}