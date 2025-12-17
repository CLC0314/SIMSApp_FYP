package com.example.familyapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.familyapp.adapter.InventoryAdapter
import com.example.familyapp.data.Family
import com.example.familyapp.data.InventoryItemFirestore
import com.example.familyapp.databinding.ActivityInventoryBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class InventoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInventoryBinding
    private lateinit var inventoryAdapter: InventoryAdapter
    private val auth = FirebaseAuth.getInstance()
    private val db = Firebase.firestore

    private var familyListener: ListenerRegistration? = null
    private var inventoryListener: ListenerRegistration? = null

    private var currentFamilyId: String? = null
    private var currentUserName: String? = null
    private var memberNameMap = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInventoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        inventoryAdapter = InventoryAdapter(
            this,
            mutableListOf(),
            onItemClick = { /* 实现点击跳转详情 */ },
            onItemLongClick = { /* 实现长按 */ },
            onQuantityAdd = { item -> adjustItemQuantity(item, 1) },
            onQuantitySubtract = { item -> adjustItemQuantity(item, -1) }
        )

        binding.recyclerViewInventory.apply {
            layoutManager = LinearLayoutManager(this@InventoryActivity)
            adapter = inventoryAdapter
        }

        loadUserDataAndInventory(currentUser.uid)

        binding.fabAddItem.setOnClickListener {
            if (currentFamilyId.isNullOrEmpty()) {
                Toast.makeText(this, "Family data is still loading...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, AddItemActivity::class.java).apply {
                putExtra("FAMILY_ID", currentFamilyId)
            }
            startActivity(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        currentFamilyId?.let {
            listenForInventoryChanges(it)
            listenForFamilyChanges(it)
        }
    }

    private fun loadUserDataAndInventory(userId: String) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                currentFamilyId = document.getString("familyId")
                currentUserName = document.getString("name")

                if (currentFamilyId.isNullOrEmpty()) {
                    startActivity(Intent(this, FamilySelectionActivity::class.java))
                    finish()
                } else {
                    listenForFamilyChanges(currentFamilyId!!)
                    fetchMemberNames(currentFamilyId!!)
                }
            }
    }

    private fun fetchMemberNames(familyId: String) {
        db.collection("families").document(familyId).get()
            .addOnSuccessListener { familyDoc ->
                val memberUids = familyDoc.get("members") as? List<String> ?: emptyList()
                if (memberUids.isEmpty()) {
                    listenForInventoryChanges(familyId)
                    return@addOnSuccessListener
                }

                db.collection("users")
                    .whereIn(FieldPath.documentId(), memberUids)
                    .get()
                    .addOnSuccessListener { userSnapshots ->
                        memberNameMap.clear()
                        for (doc in userSnapshots) {
                            doc.getString("name")?.let { memberNameMap[doc.id] = it }
                        }
                        listenForInventoryChanges(familyId)
                    }
            }
    }

    private fun listenForFamilyChanges(familyId: String) {
        familyListener?.remove()
        familyListener = db.collection("families").document(familyId)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val family = snapshot.toObject(Family::class.java)
                    supportActionBar?.title = family?.name
                    binding.toolbar.subtitle = "User: $currentUserName"
                }
            }
    }

    private fun listenForInventoryChanges(familyId: String) {
        inventoryListener?.remove()
        inventoryListener = db.collection("inventory")
            .whereEqualTo("familyId", familyId)
            .orderBy("category")
            .addSnapshotListener { snapshots, _ ->
                if (snapshots != null) {
                    val rawInventoryList = snapshots.map { doc ->
                        val item = doc.toObject(InventoryItemFirestore::class.java)
                        item.id = doc.id
                        item
                    }
                    inventoryAdapter.updateData(processInventoryData(rawInventoryList))
                }
            }
    }

    private fun processInventoryData(rawList: List<InventoryItemFirestore>): List<InventoryListItem> {
        val groupedList = mutableListOf<InventoryListItem>()
        var currentCategory: String? = null

        val processedList = rawList.map { item ->
            item.copy(ownerName = memberNameMap[item.ownerId] ?: item.ownerName)
        }

        for (item in processedList) {
            if (item.category != currentCategory) {
                currentCategory = item.category
                groupedList.add(InventoryListItem.Header(item.category.ifEmpty { "Uncategorized" }.uppercase()))
            }
            groupedList.add(InventoryListItem.Item(item))
        }
        return groupedList
    }

    private fun adjustItemQuantity(item: InventoryItemFirestore, change: Int) {
        if (item.id.isEmpty()) return
        val itemRef = db.collection("inventory").document(item.id)

        db.runTransaction { transaction ->
            val snapshot = transaction.get(itemRef)
            if (!snapshot.exists()) return@runTransaction null

            val currentQuantity = snapshot.getLong("quantity")?.toInt() ?: 0
            val newQuantity = currentQuantity + change

            if (newQuantity <= 0) {
                transaction.delete(itemRef)
            } else {
                transaction.update(itemRef, "quantity", newQuantity)
            }
            null
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.inventory_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                logout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun logout() {
        auth.signOut()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        familyListener?.remove()
        inventoryListener?.remove()
    }
}