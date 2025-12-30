package com.example.familyapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView // 👈 必须添加这一行，否则 findViewById 会报错
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.familyapp.data.InventoryItemFirestore
import com.example.familyapp.databinding.ActivityItemDetailBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import androidx.appcompat.app.AlertDialog
import com.google.firebase.firestore.FieldPath
import java.util.*
import java.util.Date

class ItemDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityItemDetailBinding
    private val db = Firebase.firestore
    private val auth = FirebaseAuth.getInstance()
    private var itemListener: ListenerRegistration? = null
    private var currentItem: InventoryItemFirestore? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityItemDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val itemId = intent.getStringExtra("ITEM_ID")
        if (itemId == null) {
            finish()
            return
        }

        setupButtons()
        observeItem(itemId)
    }

    private fun observeItem(itemId: String) {
        itemListener = db.collection("inventory").document(itemId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Toast.makeText(this, "Error loading item", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val item = snapshot.toObject(InventoryItemFirestore::class.java)
                    item?.id = snapshot.id
                    currentItem = item
                    updateUI(item)
                } else {
                    Toast.makeText(this, "Item no longer exists", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
    }

    private fun updateUI(item: InventoryItemFirestore?) {
        item?.let { it ->
            binding.apply {
                // 顶部基本信息
                tvDetailName.text = it.name
                tvDetailCategory.text = "Category: ${it.category.uppercase()}"

                // 1. Quantity
                rowQuantity.root.findViewById<TextView>(R.id.tvLabel)?.text = "Quantity"
                rowQuantity.root.findViewById<TextView>(R.id.tvValue)?.text = "${it.quantity} ${it.unit}"

                // 2. Location
                rowLocation.root.findViewById<TextView>(R.id.tvLabel)?.text = "Location"
                rowLocation.root.findViewById<TextView>(R.id.tvValue)?.text = it.location.ifEmpty { "Not specified" }

                // 3. Owner
                rowOwner.root.findViewById<TextView>(R.id.tvLabel)?.text = "Owner"
                rowOwner.root.findViewById<TextView>(R.id.tvValue)?.text = it.ownerName.takeIf { !it.isNullOrEmpty() } ?: "Public"

                // 4. Expiry
                rowExpiry.root.findViewById<TextView>(R.id.tvLabel)?.text = "Expiry"
                rowExpiry.root.findViewById<TextView>(R.id.tvValue)?.let { textView ->
                    if (it.expiryDate > 0) {
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        textView.text = sdf.format(Date(it.expiryDate))
                    } else {
                        textView.text = "No expiry date"
                    }
                }

                // 5. 权限判断逻辑 (合并后的逻辑)
                val currentUid = auth.currentUser?.uid
                // 如果是主人，或者是公共物品 (ownerId 是 "PUBLIC" 或为空)，则允许操作
                val canManage = it.ownerId == currentUid || it.ownerId == "PUBLIC" || it.ownerId.isNullOrEmpty()

                layoutActions.visibility = if (canManage) View.VISIBLE else View.GONE
            }
        }
    }
    private fun showTransferDialog() {
        val familyId = currentItem?.familyId ?: return

        // 1. 先抓取家庭里的所有成员 UID
        db.collection("families").document(familyId).get()
            .addOnSuccessListener { doc ->
                val memberUids = doc.get("members") as? List<String> ?: emptyList()

                // 2. 根据 UID 抓取名字
                db.collection("users").whereIn(FieldPath.documentId(), memberUids).get()
                    .addOnSuccessListener { userDocs ->
                        val names = mutableListOf<String>()
                        val uids = mutableListOf<String>()

                        // 加上一个 "Public" 选项，允许把私人物品转回公共
                        names.add("Public (Everyone)")
                        uids.add("PUBLIC")

                        for (userDoc in userDocs) {
                            val name = userDoc.getString("name") ?: "Unknown"
                            val uid = userDoc.id
                            // 排除掉现在的 Owner 自己（可选）
                            names.add(name)
                            uids.add(uid)
                        }

                        // 3. 弹出对话框
                        AlertDialog.Builder(this)
                            .setTitle("Transfer ownership to:")
                            .setItems(names.toTypedArray()) { _, which ->
                                performTransfer(uids[which], names[which])
                            }
                            .show()
                    }
            }
    }
    
    private fun performTransfer(newOwnerId: String, newOwnerName: String) {
        val itemId = currentItem?.id ?: return

        val updates = mapOf(
            "ownerId" to newOwnerId,
            "ownerName" to newOwnerName
        )

        db.collection("inventory").document(itemId).update(updates)
            .addOnSuccessListener {
                Toast.makeText(this, "Transferred to $newOwnerName", Toast.LENGTH_SHORT).show()
                // 实时监听会自动更新 UI，所以这里不需要额外操作
            }
    }
    private fun setupButtons() {
        binding.btnEdit.setOnClickListener {
            val intent = Intent(this, AddItemActivity::class.java).apply {
                putExtra("ITEM_ID", currentItem?.id)
                putExtra("FAMILY_ID", currentItem?.familyId)
            }
            startActivity(intent)
            Toast.makeText(this, "Edit: ${currentItem?.name}", Toast.LENGTH_SHORT).show()
        }

        binding.btnTransfer.setOnClickListener {
            showTransferDialog()
            Toast.makeText(this, "Transfer: ${currentItem?.name}", Toast.LENGTH_SHORT).show()
        }
        binding.btnDelete.setOnClickListener {
            showDeleteConfirmation()
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun showDeleteConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete Item")
            .setMessage("Are you sure you want to permanently remove this item from your inventory?")
            .setPositiveButton("Delete") { _, _ ->
                val itemId = currentItem?.id ?: return@setPositiveButton

                // 执行删除操作
                db.collection("inventory").document(itemId).delete()
                    .addOnSuccessListener {
                        Toast.makeText(this, "Item deleted successfully", Toast.LENGTH_SHORT).show()
                        finish() // 删除成功后关闭详情页，主页会自动更新
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        itemListener?.remove()
    }
}