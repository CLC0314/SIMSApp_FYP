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
import java.util.Date
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

    private var editingItemId: String? = null
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
        editingItemId = intent.getStringExtra("ITEM_ID")
        currentFamilyId = intent.getStringExtra("FAMILY_ID")
        setupUI()
        currentFamilyId?.let { loadCategories(it) }
        if (editingItemId != null) {
            loadItemData(editingItemId!!)
            binding.btnAddItem.text = "Update Item" // 改变按钮文字
            supportActionBar?.title = "Edit Item"
        }
    }

    private fun loadCategories(familyId: String) {
        // 1. 定义你想要的全维度固定清单
        val presetCategories = mutableListOf(
            "Fresh Food", "Pantry", "Frozen", "Beverages", "Snacks", "Spices",
            "Cleaning", "Laundry", "Paper Goods", "Tools",
            "Medical", "Supplements", "First Aid",
            "Toiletries", "Skincare", "Baby Care",
            "Electronics", "Pets", "Stationery", "Others"
        )

        // 2. 从 Firestore 获取该家庭特有的自定义分类
        db.collection("families").document(familyId).get()
            .addOnSuccessListener { snapshot ->
                val customCategories = snapshot.get("customCategories") as? List<String> ?: emptyList()

                // 3. 合并、去重、排序
                val allCategories = (presetCategories + customCategories).distinct().sorted()

                // 4. 更新 UI 适配器
                val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, allCategories)
                binding.actvCategory.setAdapter(adapter)

                // 确保点击时弹出
                binding.actvCategory.setOnClickListener {
                    binding.actvCategory.showDropDown()
                }
            }
    }
    private fun setupCategorySpinner() {
        // 按照你要求的全维度家庭分类清单
        val categories = arrayOf(
            // Kitchen (厨房)
            "Fresh Food", "Pantry", "Frozen", "Beverages", "Snacks", "Spices",
            // Household (家政)
            "Cleaning", "Laundry", "Paper Goods", "Tools",
            // Health (健康)
            "Medical", "Supplements", "First Aid",
            // Personal (个人)
            "Toiletries", "Skincare", "Baby Care",
            // Others (其他)
            "Electronics", "Pets", "Stationery", "Others"
        )

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categories)
        binding.actvCategory.setAdapter(adapter)

        // 点击输入框时立即展开下拉列表
        binding.actvCategory.setOnClickListener {
            binding.actvCategory.showDropDown()
        }
    }
    private fun loadItemData(itemId: String) {
        db.collection("inventory").document(itemId).get()
            .addOnSuccessListener { doc ->
                val item = doc.toObject(InventoryItemFirestore::class.java)
                item?.let {
                    binding.apply {
                        etItemName.setText(it.name)
                        etQuantity.setText(it.quantity.toString())
                        actvUnit.setText(it.unit, false)
                        actvCategory.setText(it.category, false)
                        etRemarks.setText(it.notes)

                        // 设置日期显示
                        if (it.expiryDate != null && it.expiryDate!! > 0) {
                            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            selectedExpiryDate = sdf.format(Date(it.expiryDate!!))
                            tvExpiryDateDisplay.text = "Expiry Date: $selectedExpiryDate"
                        }

                        // 设置归属 (Owner)
                        if (it.ownerId != "PUBLIC") {
                            rbPrivate.isChecked = true
                            actvOwner.setText(it.ownerName, false)
                            tilOwner.visibility = View.VISIBLE
                        } else {
                            rbPublic.isChecked = true
                        }
                    }
                }
            }
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
        // 1. 获取输入并清理空格
        val name = binding.etItemName.text.toString().trim()
        val quantityStr = binding.etQuantity.text.toString().trim()
        val unit = binding.actvUnit.text.toString()

        // --- 第3步：分类格式化处理 ---
        val categoryRaw = binding.actvCategory.text.toString().trim()
        val category = if (categoryRaw.isNotEmpty()) {
            // 确保首字母大写，其余小写 (例如 "food" -> "Food")
            categoryRaw.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        } else {
            "Others"
        }
        // -------------------------

        val remarks = binding.etRemarks.text.toString().trim()
        val isPrivate = binding.rbPrivate.isChecked
        val ownerNameInput = binding.actvOwner.text.toString()

        // 2. 基础验证
        if (name.isEmpty() || quantityStr.isEmpty()) {
            Toast.makeText(this, "Please fill in Name and Quantity", Toast.LENGTH_SHORT).show()
            return
        }

        val quantity = quantityStr.toIntOrNull() ?: 1

        // 3. 日期转换：确保输出为 Long
        val expiryTimestamp: Long = selectedExpiryDate?.let { dateStr ->
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                sdf.parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        } ?: 0L

        // 4. 归属权逻辑
        val finalOwnerId: String = if (isPrivate) {
            memberNameMap[ownerNameInput] ?: auth.currentUser?.uid ?: ""
        } else {
            "PUBLIC"
        }

        val finalOwnerName: String = if (isPrivate) {
            if (ownerNameInput.isEmpty()) "Private" else ownerNameInput
        } else {
            "Public"
        }

        // 5. 构建实体类对象
        val newItem = InventoryItemFirestore(
            id = editingItemId ?: "",
            familyId = currentFamilyId ?: "",
            name = name,
            category = category, // 使用处理后的分类
            quantity = quantity,
            unit = unit,
            expiryDate = expiryTimestamp,
            ownerId = finalOwnerId,
            ownerName = finalOwnerName,
            notes = remarks,
            location = ""
        )

        // 6. 执行写入
        binding.btnAddItem.isEnabled = false
        val docRef = if (editingItemId != null) {
            db.collection("inventory").document(editingItemId!!)
        } else {
            db.collection("inventory").document()
        }

        docRef.set(newItem)
            .addOnSuccessListener {
                val msg = if (editingItemId != null) "Item updated!" else "Item added!"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { e ->
                binding.btnAddItem.isEnabled = true
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}