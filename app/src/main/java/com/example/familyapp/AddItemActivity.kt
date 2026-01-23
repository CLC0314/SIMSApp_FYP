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
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AddItemActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddItemBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = Firebase.firestore
    private var selectedExpiryDate: String? = null
    private var currentFamilyId: String? = null
    private var memberNameMap = mutableMapOf<String, String>()
    private val unitOptions = listOf("Item", "Pack", "Kg", "Bottle", "Box", "G", "Ml")
    private var editingItemId: String? = null
    private var currentItem: InventoryItemFirestore? = null
    private val itemTemplates = mutableListOf<ItemTemplate>()
    private var currentScannedBarcode: String? = null

    data class ItemTemplate(
        val name: String,
        val category: String,
        val unit: String,
        val threshold: Int?
    )

    // 初始化页面与逻辑入口
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddItemBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        currentFamilyId = intent.getStringExtra("FAMILY_ID")
        editingItemId = intent.getStringExtra("ITEM_ID")

        val nameHint = intent.getStringExtra("NAME_HINT")
        val categoryHint = intent.getStringExtra("CATEGORY_HINT")
        val unitHint = intent.getStringExtra("UNIT_HINT")
        val ownerIdHint = intent.getStringExtra("OWNER_ID_HINT")
        val ownerNameHint = intent.getStringExtra("OWNER_NAME_HINT")

        if (currentFamilyId.isNullOrEmpty()) {
            Toast.makeText(this, "Error: Missing Family ID", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupUI()
        loadCategories(currentFamilyId!!)

        if (!nameHint.isNullOrEmpty()) {
            binding.etItemName.setText(nameHint)
            categoryHint?.let { binding.actvCategory.setText(it, false) }
            unitHint?.let { binding.actvUnit.setText(it, false) }

            if (ownerIdHint != null && ownerIdHint != "PUBLIC") {
                binding.rbPrivate.isChecked = true
                binding.tilOwner.visibility = View.VISIBLE
                binding.actvOwner.setText(ownerNameHint, false)
            } else {
                binding.rbPublic.isChecked = true
            }

            binding.etQuantity.requestFocus()
            Snackbar.make(binding.root, "Adding new batch for $nameHint", Snackbar.LENGTH_SHORT).show()
        }

        if (editingItemId != null) {
            loadItemData(editingItemId!!)
            binding.btnAddItem.text = "Update Batch"
            supportActionBar?.title = "Edit Batch"
        } else {
            supportActionBar?.title = "Add New Batch"
        }

        binding.tilItemName.setEndIconOnClickListener {
            val intent = Intent(this, ScannerActivity::class.java)
            startActivityForResult(intent, 1001)
        }
    }

    // 加载已有物品的数据进行编辑
    private fun loadItemData(itemId: String) {
        db.collection("inventory").document(itemId).get()
            .addOnSuccessListener { doc ->
                val item = doc.toObject(InventoryItemFirestore::class.java)
                item?.let {
                    currentItem = it
                    binding.apply {
                        etItemName.setText(it.name)
                        etQuantity.setText(it.quantity.toString())
                        actvUnit.setText(it.unit, false)
                        actvCategory.setText(it.category, false)
                        etRemarks.setText(it.notes)

                        if (it.expiryDate > 0) {
                            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            selectedExpiryDate = sdf.format(Date(it.expiryDate))
                            tvExpiryDateDisplay.text = "Expiry Date: $selectedExpiryDate"
                        }

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

    // 设置界面元素与交互
    private fun setupUI() {
        setupItemNameAutocomplete()
        setupDropdown(binding.actvUnit, unitOptions)
        setupMemberSelector()

        binding.btnExpiryDate.setOnClickListener { showDatePicker() }

        binding.rgBelonging.setOnCheckedChangeListener { _, id ->
            binding.tilOwner.visibility = if (id == R.id.rbPrivate) View.VISIBLE else View.GONE
        }

        binding.btnAddItem.setOnClickListener { saveBatchToFirestore() }
    }

    // 将物品批次数据保存至 Firestore
    private fun saveBatchToFirestore() {
        val nameInput = binding.etItemName.text.toString().trim()
        val categoryInput = binding.actvCategory.text.toString().trim()
        val quantityStr = binding.etQuantity.text.toString().trim()
        val unit = binding.actvUnit.text.toString()
        val remarks = binding.etRemarks.text.toString().trim()
        val category = binding.actvCategory.text.toString().trim().ifEmpty { "Others" }

        val isPrivate = binding.rbPrivate.isChecked
        val ownerNameInput = binding.actvOwner.text.toString()
        val expiryTimestamp: Long = selectedExpiryDate?.let {
            try { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(it)?.time } catch (e: Exception) { 0L }
        } ?: 0L

        if (nameInput.isEmpty() || quantityStr.isEmpty()) {
            Toast.makeText(this, "Please fill in Name and Quantity", Toast.LENGTH_SHORT).show()
            return
        }

        val isKnownItem = itemTemplates.any { it.name.equals(nameInput, ignoreCase = true) }

        val isPending = if (editingItemId != null) {
            // 🟢 修复：在编辑模式下，如果用户填了保质期，或者分类不是强制要求的，就取消 Pending
            if (expiryTimestamp > 0L) {
                false // 有了保质期，转正
            } else {
                // 如果依然没填日期，检查一下现在这个分类是否还强制要求日期
                isExpiryRequired(categoryInput)
            }
        } else {
            // 新增模式下的逻辑保持不变
            if (isKnownItem) {
                false
            } else if (isExpiryRequired(categoryInput) && expiryTimestamp <= 0L) {
                true
            } else {
                false
            }
        }

        val quantity = quantityStr.toIntOrNull() ?: 1
        val finalOwnerId = if (isPrivate) (memberNameMap[ownerNameInput] ?: auth.currentUser?.uid ?: "") else "PUBLIC"
        val finalOwnerName = if (isPrivate) ownerNameInput.ifEmpty { "Private" } else "Public"

        binding.btnAddItem.isEnabled = false

        val docRef = if (editingItemId != null) db.collection("inventory").document(editingItemId!!) else db.collection("inventory").document()

        val itemData = mutableMapOf<String, Any>(
            "id" to docRef.id,
            "familyId" to (currentFamilyId ?: ""),
            "name" to nameInput,
            "category" to category,
            "quantity" to quantity,
            "unit" to unit,
            "expiryDate" to expiryTimestamp,
            "ownerId" to finalOwnerId,
            "ownerName" to finalOwnerName,
            "notes" to remarks,
            "pendingSetup" to isPending
        )

        if (editingItemId == null && isKnownItem) {
            val template = itemTemplates.find { it.name.equals(nameInput, ignoreCase = true) }
            template?.threshold?.let { itemData["minThreshold"] = it }
        }

        docRef.set(itemData, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                if (currentScannedBarcode != null) {
                    val barcodeData = hashMapOf(
                        "name" to nameInput,
                        "category" to category,
                        "unit" to unit
                    )
                    db.collection("families").document(currentFamilyId!!)
                        .collection("barcode_library").document(currentScannedBarcode!!)
                        .set(barcodeData)
                }
                checkOwnerTotalAndAlert(nameInput, finalOwnerId)
                Toast.makeText(this, "Batch saved successfully.", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener {
                binding.btnAddItem.isEnabled = true
                Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // 检查物品库存总量是否低于预警线并更新提醒状态
    private fun checkOwnerTotalAndAlert(itemName: String, ownerId: String) {
        val fid = currentFamilyId ?: return
        db.collection("inventory")
            .whereEqualTo("familyId", fid)
            .whereEqualTo("name", itemName)
            .whereEqualTo("ownerId", ownerId)
            .get()
            .addOnSuccessListener { snapshots ->
                if (snapshots.isEmpty) {
                    // 如果东西全删光了，记得把警报也删了
                    db.collection("alerts").document("${itemName.lowercase().trim()}_$ownerId").delete()
                    return@addOnSuccessListener
                }

                val batches = snapshots.toObjects(InventoryItemFirestore::class.java)
                val totalQty = batches.sumOf { it.quantity }

                // 🟢 修复点：从所有批次中找出一个有效的阈值，而不是只看第一个
                val threshold = batches.mapNotNull { it.minThreshold }.firstOrNull { it > 0 }

                val alertDocId = "${itemName.lowercase().trim()}_$ownerId"
                val alertRef = db.collection("alerts").document(alertDocId)

                // 🟢 逻辑优化：有阈值且数量不足 -> 报警；有阈值但数量够了 -> 删除报警
                if (threshold != null) {
                    if (totalQty <= threshold && totalQty > 0) {
                        val alertData = hashMapOf(
                            "itemName" to itemName,
                            "ownerId" to ownerId,
                            "familyId" to fid,
                            "status" to "PENDING",
                            "currentTotal" to totalQty,
                            "threshold" to threshold,
                            "unit" to (batches.firstOrNull { it.unit.isNotEmpty() }?.unit ?: "Pcs"),
                            "ignoredBy" to emptyList<String>()
                        )
                        alertRef.set(alertData)
                    } else if (totalQty > threshold || totalQty <= 0) {
                        // 如果补货了或者彻底没了（数量为0时根据需求可选是否保留，通常建议删除）
                        alertRef.delete()
                    }
                } else {
                    // 如果用户取消了阈值设置，也删掉警报
                    alertRef.delete()
                }
            }
    }

    // 设置物品名称自动补全列表
    private fun setupItemNameAutocomplete() {
        db.collection("inventory").whereEqualTo("familyId", currentFamilyId).get()
            .addOnSuccessListener { snapshots ->
                itemTemplates.clear()
                val distinctItems = snapshots.documents.distinctBy { it.getString("name") }
                val nameList = distinctItems.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    itemTemplates.add(ItemTemplate(name, doc.getString("category") ?: "Others", doc.getString("unit") ?: "Item", doc.getLong("minThreshold")?.toInt()))
                    name
                }
                binding.etItemName.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, nameList))
            }

        binding.etItemName.setOnItemClickListener { parent, _, pos, _ ->
            val selected = parent.getItemAtPosition(pos) as String
            itemTemplates.find { it.name == selected }?.let {
                binding.actvUnit.setText(it.unit, false)
                binding.actvCategory.setText(it.category, false)
            }
        }
    }

    // 加载家庭分类列表（预设+自定义）
    private fun loadCategories(familyId: String) {
        val presets = mutableListOf("Fresh Food", "Pantry", "Frozen", "Beverages", "Snacks", "Spices", "Cleaning", "Medical", "Toiletries", "Others")
        db.collection("families").document(familyId).get().addOnSuccessListener { snapshot ->
            val customs = snapshot.get("customCategories") as? List<String> ?: emptyList()
            val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, (presets + customs).distinct().sorted())
            binding.actvCategory.setAdapter(adapter)
            binding.actvCategory.setOnClickListener { binding.actvCategory.showDropDown() }
        }
    }

    // 判断该分类是否必须填写过期日期
    private fun isExpiryRequired(category: String): Boolean {
        val criticalList = listOf("FRESH FOOD", "FOOD", "FROZEN", "MEDICAL", "BEVERAGES", "SNACKS")
        return criticalList.contains(category.uppercase().trim())
    }

    // 设置家庭成员选择器
    private fun setupMemberSelector() {
        db.collection("users").whereEqualTo("familyId", currentFamilyId).get().addOnSuccessListener { snapshots ->
            val names = snapshots.map { doc ->
                val name = doc.getString("name") ?: "Unknown"
                memberNameMap[name] = doc.id
                name
            }
            setupDropdown(binding.actvOwner, names)
        }
    }

    // 通用下拉列表设置辅助函数
    private fun setupDropdown(actv: AutoCompleteTextView, items: List<String>) {
        actv.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items))
        actv.setOnClickListener { actv.showDropDown() }
    }

    // 显示日期选择弹窗
    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            selectedExpiryDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
            binding.tvExpiryDateDisplay.text = "Expiry Date: $selectedExpiryDate"
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // 处理扫描二维码返回的结果
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val barcode = data?.getStringExtra("SCAN_RESULT")
            if (barcode != null) {
                currentScannedBarcode = barcode
                searchBarcodeLibrary(barcode)
            }
        }
    }

    // 在家庭条码库中搜索识别条码
    private fun searchBarcodeLibrary(barcode: String) {
        val fid = currentFamilyId ?: return
        db.collection("families").document(fid)
            .collection("barcode_library").document(barcode)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    binding.etItemName.setText(doc.getString("name"))
                    binding.actvCategory.setText(doc.getString("category"), false)
                    binding.actvUnit.setText(doc.getString("unit"), false)
                    Toast.makeText(this, "Recognized from family library!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "New item barcode detected.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    // 处理顶部返回按钮点击
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}