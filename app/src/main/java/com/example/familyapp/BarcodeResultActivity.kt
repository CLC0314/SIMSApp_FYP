package com.example.familyapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.familyapp.data.InventoryItemFirestore
import com.example.familyapp.databinding.ActivityBarcodeResultBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.example.familyapp.data.OwnerGroup

class BarcodeResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBarcodeResultBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var currentFamilyId: String? = null

    // 初始化页面并获取扫描物品信息
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBarcodeResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val itemName = intent.getStringExtra("ITEM_NAME") ?: ""
        currentFamilyId = intent.getStringExtra("FAMILY_ID")

        binding.tvScannedName.text = itemName
        supportActionBar?.title = "Scan Result"

        loadOwnershipData(itemName)
        binding.btnCancel.setOnClickListener { finish() }
    }

    // 从 Firestore 加载该物品在不同成员名下的库存数据
    private fun loadOwnershipData(itemName: String) {
        db.collection("inventory")
            .whereEqualTo("familyId", currentFamilyId)
            .whereEqualTo("name", itemName)
            .get()
            .addOnSuccessListener { snapshots ->
                val allItems = snapshots.toObjects(InventoryItemFirestore::class.java)
                val myId = auth.currentUser?.uid ?: ""

                val grouped = allItems.groupBy { it.ownerId }.map { (ownerId, list) ->
                    OwnerGroup(
                        itemName = itemName,
                        ownerId = ownerId,
                        ownerName = list[0].ownerName,
                        totalQty = list.sumOf { it.quantity },
                        unit = list[0].unit
                    )
                }.sortedBy { it.ownerId != myId && it.ownerId != "PUBLIC" }

                setupRecyclerView(grouped)
            }
    }

    // 设置列表显示控件
    private fun setupRecyclerView(list: List<OwnerGroup>) {
        binding.rvOwnershipList.layoutManager = LinearLayoutManager(this)
        binding.rvOwnershipList.adapter = OwnershipAdapter(list) { selectedGroup ->
            handleItemClick(selectedGroup)
        }
    }

    // 处理列表点击事件，包含权限拦截逻辑
    private fun handleItemClick(group: OwnerGroup) {
        val myId = auth.currentUser?.uid

        // 需求 2 & 5: 权限检查（仅限本人或公共物品），提示语使用英文
        if (group.ownerId == myId || group.ownerId == "PUBLIC") {
            val intent = Intent(this, ItemDetailActivity::class.java).apply {
                // 核心传递参数：物品名称和具体的物主ID
                putExtra("ITEM_NAME", group.itemName)
                putExtra("OWNER_ID", group.ownerId)
                putExtra("FAMILY_ID", currentFamilyId)
                // 如果你的 ItemDetailActivity 需要具体的文档 ID，则可能需要额外逻辑
            }
            startActivity(intent)
            finish()
        } else {
            // 需求 5: 英文提示
            Toast.makeText(this, "Access Denied: This belongs to ${group.ownerName}.", Toast.LENGTH_LONG).show()
        }
    }

    // 内部类：所有权列表适配器
    inner class OwnershipAdapter(
        private val items: List<OwnerGroup>,
        private val onClick: (OwnerGroup) -> Unit
    ) : RecyclerView.Adapter<OwnershipAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvOwner: TextView = view.findViewById(android.R.id.text1)
            val tvDetails: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val myId = auth.currentUser?.uid
            val isLocked = item.ownerId != myId && item.ownerId != "PUBLIC"

            holder.tvOwner.text = if (isLocked) "🔒 ${item.ownerName} (Private)" else "👤 ${item.ownerName}"
            holder.tvDetails.text = "Stock: ${item.totalQty} ${item.unit}"

            if (isLocked) {
                holder.itemView.alpha = 0.5f
                holder.tvOwner.setTextColor(Color.GRAY)
            } else {
                holder.itemView.alpha = 1.0f
                holder.tvOwner.setTextColor(Color.BLACK)
            }

            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}