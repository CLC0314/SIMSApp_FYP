package com.example.familyapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.familyapp.data.InventoryItemFirestore
import com.example.familyapp.databinding.ActivityItemDetailBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.*

class ItemDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityItemDetailBinding
    private val db = Firebase.firestore
    private val auth = FirebaseAuth.getInstance()
    private var itemListener: ListenerRegistration? = null
    private var batchesListener: ListenerRegistration? = null

    private var currentItem: InventoryItemFirestore? = null
    private var currentFamilyId: String? = null
    private var currentItemName: String? = null
    private var currentOwnerId: String? = null
    private var myBatchAdapter: BatchAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityItemDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEdit.visibility = View.GONE

        currentFamilyId = intent.getStringExtra("FAMILY_ID")
        currentItemName = intent.getStringExtra("ITEM_NAME")
        currentOwnerId = intent.getStringExtra("OWNER_ID") ?: "PUBLIC"
        val itemId = intent.getStringExtra("ITEM_ID")

        if (currentFamilyId == null) {
            finish()
            return
        }

        if (itemId != null) {
            setupButtons()
            observeItem(itemId)
        } else if (currentItemName != null) {
            findRepresentativeId(currentItemName!!, currentOwnerId!!)
        } else {
            finish()
        }
    }

    private fun observeItem(itemId: String) {
        itemListener = db.collection("inventory").document(itemId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

                val item = snapshot.toObject(InventoryItemFirestore::class.java)
                item?.id = snapshot.id
                currentItem = item

                item?.let {
                    loadAllBatches(it.name, it.ownerId)
                    updateUI(it)
                    listenForItemAlert(it.name, it.ownerId)
                }
            }
    }

    private fun findRepresentativeId(itemName: String, ownerId: String) {
        val fid = currentFamilyId ?: return
        db.collection("inventory")
            .whereEqualTo("familyId", fid)
            .whereEqualTo("name", itemName)
            .whereEqualTo("ownerId", ownerId)
            // 🟢 先删掉这行测试： .orderBy("pendingSetup", ...)
            .limit(1)
            .get()
            .addOnSuccessListener { snapshots ->
                if (!snapshots.isEmpty) {
                    val foundId = snapshots.documents[0].id
                    setupButtons()
                    observeItem(foundId)
                } else {
                    // 🟢 增加提示，确认是因为没搜到还是逻辑没跑
                    Toast.makeText(this, "No record found for $itemName under $ownerId", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                // 🟢 如果是索引问题，这里会打印错误
                android.util.Log.e("FIRESTORE_ERROR", "Query failed: ${e.message}")
            }
    }

    private fun updateUI(item: InventoryItemFirestore?) {
        item?.let { it ->
            binding.apply {
                tvDetailName.text = it.name
                tvDetailCategory.text = "Category: ${it.category.uppercase()}"

                val currentUid = auth.currentUser?.uid
                val isOwner = it.ownerId == currentUid || it.ownerId == "PUBLIC"
                val canManage = it.ownerId == currentUid || it.ownerId == "PUBLIC"
                layoutActions.visibility = if (isOwner) View.VISIBLE else View.GONE
                btnTransfer.visibility = if (isOwner) View.VISIBLE else View.GONE
                btnDelete.visibility = if (isOwner) View.VISIBLE else View.GONE

                if (!canManage) {
                    rowOwner.root.findViewById<TextView>(R.id.tvValue).setTextColor(Color.RED)
                } else {
                    rowOwner.root.findViewById<TextView>(R.id.tvValue).setTextColor(Color.BLACK)
                }
                db.collection("inventory")
                    .whereEqualTo("familyId", currentFamilyId)
                    .whereEqualTo("name", it.name)
                    .whereEqualTo("ownerId", it.ownerId)
                    .get()
                    .addOnSuccessListener { snapshots ->
                        val batchList = snapshots.toObjects(InventoryItemFirestore::class.java)
                        val totalQty = batchList.sumOf { b -> b.quantity }

                        val qtyValue = rowQuantity.root.findViewById<TextView>(R.id.tvValue)
                        rowQuantity.root.findViewById<TextView>(R.id.tvLabel).text = "Total Quantity"

                        val closestExpiry = batchList
                            .filter { b -> b.expiryDate > 0 }
                            .minByOrNull { b -> b.expiryDate }?.expiryDate ?: 0L

                        val expiryValue = rowExpiry.root.findViewById<TextView>(R.id.tvValue)
                        rowExpiry.root.findViewById<TextView>(R.id.tvLabel).text = "Closest Expiry"

                        if (closestExpiry > 0) {
                            expiryValue.text = formatDate(closestExpiry)
                        } else {
                            expiryValue.text = "No expiry date"
                        }

                        if (it.minThreshold != null && totalQty <= it.minThreshold!!) {
                            qtyValue.setTextColor(Color.RED)
                            qtyValue.text = "$totalQty ${it.unit} (Low Stock! ⚠️)"
                        } else {
                            qtyValue.setTextColor(Color.BLACK)
                            qtyValue.text = "$totalQty ${it.unit}"
                        }
                    }

                setupThresholdRow(it)

                rowLocation.root.findViewById<TextView>(R.id.tvLabel).text = "Location"
                rowLocation.root.findViewById<TextView>(R.id.tvValue).text = it.location.ifEmpty { "Not specified" }

                rowOwner.root.findViewById<TextView>(R.id.tvLabel).text = "Owner"
                rowOwner.root.findViewById<TextView>(R.id.tvValue).text = it.ownerName

                cardPendingAlert.visibility = if (it.pendingSetup) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupThresholdRow(item: InventoryItemFirestore) {
        val row = binding.rowThreshold.root
        val tvLabel = row.findViewById<TextView>(R.id.tvLabel)
        val tvValue = row.findViewById<TextView>(R.id.tvValue)

        tvLabel.text = "Low Stock Alert"
        if (item.minThreshold != null) {
            tvValue.text = "At or below ${item.minThreshold} ${item.unit}"
            tvValue.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            tvValue.text = "Not Set (Tap to set)"
            tvValue.setTextColor(Color.GRAY)
        }

        row.setOnClickListener {
            showThresholdDialog(item.name, item.ownerId, item.minThreshold)
        }
    }

    private fun showThresholdDialog(itemName: String, ownerId: String, currentThreshold: Int?) {
        val editText = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Min quantity"
            if (currentThreshold != null) setText(currentThreshold.toString())
        }

        AlertDialog.Builder(this)
            .setTitle("Set Global Low Stock Alert")
            .setMessage("Notify family when TOTAL '$itemName' is at or below:")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val newThreshold = editText.text.toString().toIntOrNull()
                updateGroupThreshold(itemName, ownerId, newThreshold)
            }
            .setNegativeButton("Turn Off") { _, _ ->
                updateGroupThreshold(itemName, ownerId, null)
            }
            .show()
    }

    private fun updateGroupThreshold(itemName: String, ownerId: String, newThreshold: Int?) {
        val fid = currentFamilyId ?: return
        db.collection("inventory")
            .whereEqualTo("familyId", fid)
            .whereEqualTo("name", itemName)
            .whereEqualTo("ownerId", ownerId)
            .get()
            .addOnSuccessListener { snapshots ->
                val batch = db.batch()
                for (doc in snapshots) {
                    batch.update(doc.reference, "minThreshold", newThreshold)
                }
                batch.commit().addOnSuccessListener {
                    Toast.makeText(this, "Global threshold updated.", Toast.LENGTH_SHORT).show()
                    checkOwnerTotalAndAlert(itemName, ownerId)
                }
            }
    }

    private fun checkOwnerTotalAndAlert(itemName: String, ownerId: String) {
        val fid = currentFamilyId ?: return
        db.collection("inventory")
            .whereEqualTo("familyId", fid)
            .whereEqualTo("name", itemName)
            .whereEqualTo("ownerId", ownerId)
            .get()
            .addOnSuccessListener { snapshots ->
                val batches = snapshots.toObjects(InventoryItemFirestore::class.java)
                val totalQty = batches.sumOf { it.quantity }
                val threshold = batches.firstOrNull()?.minThreshold

                val alertDocId = "${itemName.lowercase().trim()}_$ownerId"
                val alertRef = db.collection("alerts").document(alertDocId)

                if (threshold != null && totalQty <= threshold && totalQty > 0) {
                    val alertData = hashMapOf(
                        "itemName" to itemName,
                        "ownerId" to ownerId,
                        "familyId" to fid,
                        "status" to "PENDING",
                        "currentTotal" to totalQty,
                        "threshold" to threshold,
                        "unit" to (batches.firstOrNull()?.unit ?: ""),
                        "createdAt" to com.google.firebase.Timestamp.now()
                    )
                    alertRef.set(alertData)
                } else {
                    alertRef.delete()
                }
            }
    }

    private fun loadAllBatches(itemName: String, ownerId: String) {
        val fid = currentFamilyId ?: return
        batchesListener?.remove()

        batchesListener = db.collection("inventory")
            .whereEqualTo("familyId", fid)
            .whereEqualTo("name", itemName)
            .whereEqualTo("ownerId", ownerId)
            .addSnapshotListener { snapshots, e ->
                if (e != null) return@addSnapshotListener

                val batches = snapshots?.documents?.mapNotNull { doc ->
                    doc.toObject(InventoryItemFirestore::class.java)?.apply { id = doc.id }
                }?.sortedByDescending { it.pendingSetup } ?: emptyList()

                if (myBatchAdapter == null) {
                    myBatchAdapter = BatchAdapter(
                        batches,
                        onBatchClick = { selectedBatch ->
                            val intent = Intent(this, AddItemActivity::class.java).apply {
                                putExtra("ITEM_ID", selectedBatch.id)
                                putExtra("FAMILY_ID", currentFamilyId)
                            }
                            startActivity(intent)
                        },
                        onDeleteClick = { targetBatch -> showBatchDeleteConfirmation(targetBatch) }
                    )
                    binding.rvBatches.layoutManager = LinearLayoutManager(this)
                    binding.rvBatches.adapter = myBatchAdapter
                } else {
                    myBatchAdapter?.updateData(batches)
                }

                binding.tvBatchesTitle.visibility = if (batches.isNotEmpty()) View.VISIBLE else View.GONE
                binding.rvBatches.visibility = if (batches.isNotEmpty()) View.VISIBLE else View.GONE
            }
    }

    private fun setupButtons() {
        binding.btnDismissPending.setOnClickListener {
            currentItem?.let { completePendingSetup(it) }
        }
        binding.btnTransfer.setOnClickListener { showTransferDialog() }
        binding.btnDelete.setOnClickListener { showDeleteConfirmation() }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddBatch.setOnClickListener {
            currentItem?.let { item ->
                val intent = Intent(this, AddItemActivity::class.java).apply {
                    putExtra("NAME_HINT", item.name)
                    putExtra("CATEGORY_HINT", item.category)
                    putExtra("UNIT_HINT", item.unit)
                    putExtra("OWNER_ID_HINT", item.ownerId)
                    putExtra("OWNER_NAME_HINT", item.ownerName)
                    putExtra("FAMILY_ID", currentFamilyId)
                }
                startActivity(intent)
            }
        }
    }

    private fun completePendingSetup(item: InventoryItemFirestore) {
        db.collection("inventory").document(item.id)
            .update("pendingSetup", false)
            .addOnSuccessListener {
                Toast.makeText(this, "Item activated!", Toast.LENGTH_SHORT).show()
                checkOwnerTotalAndAlert(item.name, item.ownerId)
            }
    }

    private fun showBatchDeleteConfirmation(batch: InventoryItemFirestore) {
        AlertDialog.Builder(this)
            .setTitle("Delete This Batch?")
            .setMessage("Are you sure you want to remove this specific stock?\n\nQuantity: ${batch.quantity} ${batch.unit}")
            .setPositiveButton("Delete") { _, _ ->
                db.collection("inventory").document(batch.id).delete()
                    .addOnSuccessListener {
                        checkOwnerTotalAndAlert(batch.name, batch.ownerId)
                        Toast.makeText(this, "Batch removed", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatDate(time: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(time))
    }

    private fun showDeleteConfirmation() {
        currentItem?.let { item ->
            AlertDialog.Builder(this)
                .setTitle("Delete Entire Item?")
                .setMessage("This will remove ALL stock batches for '${item.name}' belonging to you.")
                .setPositiveButton("Delete All") { _, _ ->
                    val fid = currentFamilyId ?: return@setPositiveButton
                    db.collection("inventory")
                        .whereEqualTo("familyId", fid)
                        .whereEqualTo("name", item.name)
                        .whereEqualTo("ownerId", item.ownerId)
                        .get()
                        .addOnSuccessListener { snapshots ->
                            val batch = db.batch()
                            for (doc in snapshots) batch.delete(doc.reference)
                            batch.commit().addOnSuccessListener {
                                db.collection("alerts").document("${item.name.lowercase().trim()}_${item.ownerId}").delete()
                                Toast.makeText(this, "Item removed completely", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showTransferDialog() {
        val fid = currentFamilyId ?: return
        val itemName = currentItem?.name ?: return
        val currentUid = auth.currentUser?.uid ?: return

        db.collection("inventory")
            .whereEqualTo("familyId", fid)
            .whereEqualTo("name", itemName)
            .whereEqualTo("ownerId", currentUid)
            .get()
            .addOnSuccessListener { snapshots ->
                val myBatches = snapshots.documents.mapNotNull { doc ->
                    doc.toObject(InventoryItemFirestore::class.java)?.apply { id = doc.id }
                }

                if (myBatches.isEmpty()) {
                    Toast.makeText(this, "No personal stock to transfer.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val batchStrings = myBatches.map {
                    "Qty: ${it.quantity} ${it.unit} | Exp: ${if (it.expiryDate > 0) formatDate(it.expiryDate) else "No Expiry"}"
                }.toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle("Step 1: Select a Batch")
                    .setItems(batchStrings) { _, which ->
                        showTransferAmountDialog(myBatches[which])
                    }
                    .show()
            }
    }

    private fun showTransferAmountDialog(batch: InventoryItemFirestore) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Max: ${batch.quantity}"
        }

        AlertDialog.Builder(this)
            .setTitle("Step 2: Enter Quantity")
            .setMessage("How many ${batch.unit} would you like to transfer?")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val amount = input.text.toString().toIntOrNull() ?: 0
                if (amount in 1..batch.quantity) {
                    showTargetMemberPicker(batch, amount)
                } else {
                    Toast.makeText(this, "Invalid quantity", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showTargetMemberPicker(batch: InventoryItemFirestore, amount: Int) {
        val fid = currentFamilyId ?: return
        db.collection("families").document(fid).get().addOnSuccessListener { doc ->
            val memberUids = doc.get("members") as? List<String> ?: emptyList()
            db.collection("users").whereIn(FieldPath.documentId(), memberUids).get().addOnSuccessListener { userDocs ->
                val names = mutableListOf("Public (Everyone)")
                val uids = mutableListOf("PUBLIC")
                for (u in userDocs) {
                    names.add(u.getString("name") ?: "Unknown")
                    uids.add(u.id)
                }

                AlertDialog.Builder(this)
                    .setTitle("Step 3: Select Receiver")
                    .setItems(names.toTypedArray()) { _, which ->
                        executePreciseTransfer(batch, amount, uids[which], names[which])
                    }.show()
            }
        }
    }

    private fun executePreciseTransfer(sourceBatch: InventoryItemFirestore, amount: Int, targetId: String, targetName: String) {
        db.runTransaction { transaction ->
            val sourceRef = db.collection("inventory").document(sourceBatch.id)

            if (sourceBatch.quantity == amount) {
                transaction.delete(sourceRef)
            } else {
                transaction.update(sourceRef, "quantity", sourceBatch.quantity - amount)
            }

            val newRef = db.collection("inventory").document()
            val newData = sourceBatch.copy(
                id = "",
                quantity = amount,
                ownerId = targetId,
                ownerName = targetName,
                pendingSetup = false
            )
            transaction.set(newRef, newData)
            null
        }.addOnSuccessListener {
            Toast.makeText(this, "Transferred $amount to $targetName", Toast.LENGTH_SHORT).show()
            checkOwnerTotalAndAlert(sourceBatch.name, sourceBatch.ownerId)
            checkOwnerTotalAndAlert(sourceBatch.name, targetId)
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Transfer failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun listenForItemAlert(itemName: String, ownerId: String) {
        val alertId = "${itemName.lowercase().trim()}_$ownerId"
        db.collection("alerts").document(alertId)
            .addSnapshotListener { snapshot, _ ->
                val restockBtn = binding.btnEdit
                if (snapshot != null && snapshot.exists() && snapshot.getString("status") == "PENDING") {
                    val threshold = snapshot.getLong("threshold") ?: 0L
                    val currentTotal = snapshot.getLong("currentTotal") ?: 0L
                    val neededQty = (threshold - currentTotal + 1).toInt().coerceAtLeast(1)

                    restockBtn.apply {
                        visibility = View.VISIBLE
                        text = "Restock (+$neededQty)"
                        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
                        setOnClickListener {
                            quickAddToShoppingList(itemName, ownerId, neededQty)
                            snapshot.reference.update("status", "ADDED")
                            Toast.makeText(this@ItemDetailActivity, "Added $neededQty to list!", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    restockBtn.apply {
                        visibility = View.VISIBLE
                        text = "Add +1"
                        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#3F51B5"))
                        setOnClickListener {
                            quickAddToShoppingList(itemName, ownerId, 1)
                            Toast.makeText(this@ItemDetailActivity, "Added 1 to list!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
    }

    private fun quickAddToShoppingList(itemName: String, ownerId: String, qty: Int) {
        val unit = currentItem?.unit ?: "Pcs"
        val category = currentItem?.category ?: "General"
        val ownerName = currentItem?.ownerName ?: "Public"

        db.collection("shopping_lists")
            .whereEqualTo("familyId", currentFamilyId)
            .whereEqualTo("name", itemName)
            .whereEqualTo("ownerId", ownerId)
            .whereEqualTo("isChecked", false)
            .get()
            .addOnSuccessListener { snaps ->
                if (!snaps.isEmpty) {
                    snaps.documents[0].reference.update("quantity", com.google.firebase.firestore.FieldValue.increment(qty.toLong()))
                } else {
                    val shoppingItemData = hashMapOf(
                        "name" to itemName, "quantity" to qty, "unit" to unit,
                        "category" to category, "familyId" to (currentFamilyId ?: ""),
                        "ownerId" to ownerId, "ownerName" to ownerName, "isChecked" to false
                    )
                    db.collection("shopping_lists").add(shoppingItemData)
                }
            }
    }

    inner class BatchAdapter(
        private var batchList: List<InventoryItemFirestore>,
        private val onBatchClick: (InventoryItemFirestore) -> Unit,
        private val onDeleteClick: (InventoryItemFirestore) -> Unit
    ) : RecyclerView.Adapter<BatchAdapter.BatchViewHolder>() {

        fun updateData(newList: List<InventoryItemFirestore>) {
            this.batchList = newList
            notifyDataSetChanged()
        }

        inner class BatchViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvQty: TextView = view.findViewById(R.id.tvBatchQty)
            val tvExpiry: TextView = view.findViewById(R.id.tvBatchExpiry)
            val btnDelete: View = view.findViewById(R.id.btnDeleteBatch)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BatchViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_batch_row, parent, false)
            return BatchViewHolder(view)
        }

        override fun onBindViewHolder(holder: BatchViewHolder, position: Int) {
            val batch = batchList[position]
            val currentUid = auth.currentUser?.uid
            // 🔒 权限判定：只有属于自己或公共的，才允许点击编辑
            val isMine = batch.ownerId == currentUid || batch.ownerId == "PUBLIC"

            holder.tvQty.text = "${batch.quantity} ${batch.unit}"

            // 日期显示逻辑
            if (batch.pendingSetup) {
                holder.tvExpiry.text = "📝 Setup Required"
                holder.tvExpiry.setTextColor(Color.parseColor("#2E7D32"))
            } else if (batch.expiryDate > 0) {
                holder.tvExpiry.text = "Exp: ${formatDate(batch.expiryDate)}"
                holder.tvExpiry.setTextColor(Color.BLACK)
            } else {
                holder.tvExpiry.text = "No Expiry"
                holder.tvExpiry.setTextColor(Color.parseColor("#FF9800"))
            }

            // 🗑️ 删除按钮权限：只有拥有者可见
            holder.btnDelete.visibility = if (isMine) View.VISIBLE else View.GONE
            holder.btnDelete.setOnClickListener {
                if (isMine) onDeleteClick(batch)
            }

            // ✏️ 整行点击逻辑：只要是自己的货，无论新旧，点击都是编辑
            holder.itemView.setOnClickListener {
                if (isMine) {
                    onBatchClick(batch)
                } else {
                    Toast.makeText(this@ItemDetailActivity, "Access Denied: This belongs to ${batch.ownerName}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        override fun getItemCount() = batchList.size
    }

    override fun onDestroy() {
        super.onDestroy()
        itemListener?.remove()
        batchesListener?.remove()
    }
}