package com.example.familyapp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.familyapp.adapter.ShoppingAdapter
import com.example.familyapp.data.ShoppingItem
import com.example.familyapp.databinding.ActivityShoppingListBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class ShoppingListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShoppingListBinding
    private lateinit var adapter: ShoppingAdapter
    private val db = Firebase.firestore
    private val auth = FirebaseAuth.getInstance()
    private var currentFamilyId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShoppingListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 获取家庭 ID
        currentFamilyId = intent.getStringExtra("FAMILY_ID")

        // 2. 初始化列表
        adapter = ShoppingAdapter(mutableListOf(), db)
        binding.rvShoppingList.layoutManager = LinearLayoutManager(this)
        binding.rvShoppingList.adapter = adapter

        // 3. 实时监听购物清单
        listenToShoppingList()

        // 4. 快速添加逻辑
        binding.btnAddShoppingItem.setOnClickListener {
            val name = binding.etQuickAddName.text.toString().trim()
            if (name.isNotEmpty()) {
                addShoppingItem(name)
                binding.etQuickAddName.text.clear()
            }
        }

        // 5. 完成并入库 (这里目前只做清理已买项，入库逻辑可后续极致化)
        binding.btnCompletePurchase.setOnClickListener {
            completePurchase()
        }

        setSupportActionBar(binding.toolbarShopping)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun listenToShoppingList() {
        val familyId = currentFamilyId ?: return
        db.collection("shopping_lists")
            .whereEqualTo("familyId", familyId)
            .addSnapshotListener { snapshots, _ ->
                if (snapshots != null) {
                    val list = snapshots.map { doc ->
                        doc.toObject(ShoppingItem::class.java).apply { id = doc.id }
                    }
                    adapter.updateData(list)
                }
            }
    }

    private fun addShoppingItem(name: String) {
        val newItem = ShoppingItem(
            familyId = currentFamilyId ?: "",
            name = name,
            quantity = 1,
            addedBy = auth.currentUser?.uid ?: ""
        )
        db.collection("shopping_lists").add(newItem)
    }

    private fun completePurchase() {
        // 这里的逻辑：找到所有 isChecked = true 的项，从购物清单删除
        // 下一阶段：在这里加入“批量填日期入库”的弹窗
        db.collection("shopping_lists")
            .whereEqualTo("familyId", currentFamilyId)
            .whereEqualTo("isChecked", true)
            .get()
            .addOnSuccessListener { snapshots ->
                val batch = db.batch()
                for (doc in snapshots) {
                    batch.delete(doc.reference)
                }
                batch.commit().addOnSuccessListener {
                    Toast.makeText(this, "Items processed!", Toast.LENGTH_SHORT).show()
                }
            }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}