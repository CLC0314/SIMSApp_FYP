package com.example.familyapp

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.familyapp.adapter.ShoppingAdapter
import com.example.familyapp.data.ShoppingItem
import com.example.familyapp.databinding.ActivityShoppingListBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.*
import android.content.Intent
import android.util.Log

class ShoppingListActivity : AppCompatActivity() {
    private var memberNameMap = mutableMapOf<String, String>()
    private lateinit var binding: ActivityShoppingListBinding
    private lateinit var adapter: ShoppingAdapter
    private val db = Firebase.firestore
    private val auth = FirebaseAuth.getInstance()
    private var currentFamilyId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShoppingListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentFamilyId = intent.getStringExtra("FAMILY_ID")

        setupRecyclerView()
        setupSwipeToDelete()
        listenToShoppingList()

        // 🟢 关键修复：加载家庭成员名单，用于名字反查校准
        currentFamilyId?.let { fetchMemberNames(it) }

        binding.btnScanShopping.setOnClickListener {
            val intent = Intent(this, ScannerActivity::class.java)
            startActivityForResult(intent, 3001)
        }

        binding.btnAddShoppingItem.setOnClickListener {
            val name = binding.etQuickAddName.text.toString().trim()
            if (name.isNotEmpty()) handleManualAdd(name)
        }

        binding.btnCompletePurchase.setOnClickListener { completePurchase() }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Shopping List"
        binding.toolbar.setTitleTextColor(android.graphics.Color.WHITE)

        binding.bottomNav.selectedItemId = R.id.nav_shopping
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_inventory -> {
                    finish()
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_family -> {
                    val intent = Intent(this, FamilyMemberActivity::class.java)
                    intent.putExtra("FAMILY_ID", currentFamilyId)
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_shopping -> true
                else -> false
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3001 && resultCode == RESULT_OK) {
            val barcode = data?.getStringExtra("SCAN_RESULT")
            barcode?.let { handleScanResult(it) }
        }
    }

    private fun handleScanResult(barcode: String) {
        val fid = currentFamilyId ?: return
        db.collection("families").document(fid)
            .collection("barcode_library").document(barcode).get()
            .addOnSuccessListener { doc ->
                val itemName = doc.getString("name")
                if (itemName != null) {
                    // 只有匹配成功才添加
                    handleManualAdd(itemName)
                } else {
                    // 需求 3: 扫了没有匹配的，只显示提示，不引导到添加
                    Toast.makeText(this, "No item record found. Use manual input instead.", Toast.LENGTH_LONG).show()
                }
            }
    }
    private fun fetchMemberNames(familyId: String) {
        db.collection("families").document(familyId).get().addOnSuccessListener { familyDoc ->
            val memberUids = familyDoc.get("members") as? List<String> ?: emptyList()
            if (memberUids.isNotEmpty()) {
                db.collection("users").whereIn(FieldPath.documentId(), memberUids).get()
                    .addOnSuccessListener { userSnaps ->
                        memberNameMap.clear()
                        for (doc in userSnaps) {
                            doc.getString("name")?.let { memberNameMap[doc.id] = it }
                        }
                    }
            }
        }
    }

    private fun handleManualAdd(name: String) {
        val fid = currentFamilyId ?: return
        val currentUid = auth.currentUser?.uid ?: ""

        // 🟢 修复：获取当前用户的名字，避免手动添加时默认为 Public
        val currentUserName = memberNameMap[currentUid] ?: "User"

        // 查询时根据当前 UID 过滤，确保私人物品合并，不污染公共项
        db.collection("shopping_lists")
            .whereEqualTo("familyId", fid)
            .whereEqualTo("name", name)
            .whereEqualTo("ownerId", currentUid)
            .whereEqualTo("isChecked", false)
            .limit(1)
            .get()
            .addOnSuccessListener { snaps ->
                if (!snaps.isEmpty) {
                    snaps.documents[0].reference.update("quantity", FieldValue.increment(1))
                } else {
                    val newItem = ShoppingItem(
                        name = name,
                        familyId = fid,
                        quantity = 1,
                        unit = "Pcs",
                        category = "UNCATEGORIZED",
                        ownerId = currentUid,       // 🟢 记录当前用户 ID
                        ownerName = currentUserName, // 🟢 记录当前用户名字
                        isChecked = false
                    )
                    db.collection("shopping_lists").add(newItem)
                }
                binding.etQuickAddName.text.clear()
            }
    }

    private fun completePurchase() {
        val currentUserId = auth.currentUser?.uid ?: ""
        val checkedItems = adapter.getCheckedItems()

        if (checkedItems.isEmpty()) {
            Toast.makeText(this, "No items selected", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnCompletePurchase.isEnabled = false
        var processedCount = 0

        checkedItems.forEach { sItem ->
            val fid = currentFamilyId ?: return@forEach

            db.collection("inventory")
                .whereEqualTo("familyId", fid)
                .whereEqualTo("name", sItem.name)
                .whereEqualTo("ownerId", sItem.ownerId)
                .get()
                .addOnSuccessListener { snapshots ->
                    val allDocs = snapshots.documents
                    val existingThreshold = allDocs.firstOrNull()?.getLong("minThreshold")
                    val existingCategory = allDocs.firstOrNull()?.getString("category") ?: "UNCATEGORIZED"
                    val isNewItem = allDocs.isEmpty()

                    db.runTransaction { transaction ->
                        val newRef = db.collection("inventory").document()
                        val data = HashMap<String, Any>()
                        data["name"] = sItem.name
                        data["quantity"] = sItem.quantity
                        data["unit"] = sItem.unit ?: "Pcs"
                        data["familyId"] = fid
                        data["ownerId"] = sItem.ownerId

                        // 🟢 核心修复：通过 memberNameMap 强制校准名字，彻底杜绝 "Public" 污染
                        val validatedName = if (sItem.ownerId == "PUBLIC") {
                            "Public"
                        } else {
                            memberNameMap[sItem.ownerId] ?: sItem.ownerName
                        }
                        data["ownerName"] = validatedName

                        data["lastModifiedBy"] = currentUserId
                        data["createdAt"] = System.currentTimeMillis()

                        if (isNewItem) {
                            data["category"] = "UNCATEGORIZED"
                            data["pendingSetup"] = true
                        } else {
                            data["category"] = existingCategory
                            if (existingThreshold != null) data["minThreshold"] = existingThreshold
                            if (sItem.expiryDate > 0) {
                                data["expiryDate"] = sItem.expiryDate
                                data["pendingSetup"] = false
                            } else {
                                data["pendingSetup"] = true
                            }
                        }

                        transaction.set(newRef, data)
                        transaction.delete(db.collection("shopping_lists").document(sItem.id))
                        null
                    }.addOnSuccessListener {
                        processedCount++
                        if (processedCount == checkedItems.size) {
                            Toast.makeText(this, "Checkout successful!", Toast.LENGTH_SHORT).show()
                            binding.btnCompletePurchase.isEnabled = true
                        }
                    }.addOnFailureListener { e ->
                        binding.btnCompletePurchase.isEnabled = true
                        Log.e("CHECKOUT_ERROR", "Transaction failed", e)
                    }
                }
        }
    }

    private fun setupRecyclerView() {
        adapter = ShoppingAdapter(
            mutableListOf(),
            onCheckedChange = { id, checked -> db.collection("shopping_lists").document(id).update("isChecked", checked) },
            onQuantityChange = { id, newQty -> db.collection("shopping_lists").document(id).update("quantity", newQty) },
            onDateClick = { item -> showDatePicker(item) },
            onCategoryClick = { item -> showCategoryPicker(item) }
        )
        binding.rvShoppingList.layoutManager = LinearLayoutManager(this)
        binding.rvShoppingList.adapter = adapter
    }

    private fun setupSwipeToDelete() {
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                db.collection("shopping_lists").document(adapter.getItemAt(viewHolder.adapterPosition).id).delete()
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.rvShoppingList)
    }

    private fun listenToShoppingList() {
        val fid = currentFamilyId ?: return
        db.collection("shopping_lists").whereEqualTo("familyId", fid)
            .addSnapshotListener { snaps, _ ->
                snaps?.let {
                    val list = it.map { doc -> doc.toObject(ShoppingItem::class.java).apply { id = doc.id } }
                        .sortedWith(compareBy({ it.isChecked }, { it.name }))
                    adapter.updateData(list)
                }
            }
    }

    private fun showDatePicker(item: ShoppingItem) {
        val cal = Calendar.getInstance()
        if (item.expiryDate > 0) cal.timeInMillis = item.expiryDate
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            db.collection("shopping_lists").document(item.id).update("expiryDate", cal.timeInMillis)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun showCategoryPicker(item: ShoppingItem) {
        val categories = arrayOf("Fresh Food", "Pantry", "Beverages", "Cleaning", "Medical", "Other")
        AlertDialog.Builder(this)
            .setTitle("Select Category")
            .setItems(categories) { _, which ->
                db.collection("shopping_lists").document(item.id).update("category", categories[which])
            }.show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}