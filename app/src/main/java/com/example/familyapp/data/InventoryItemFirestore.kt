package com.example.familyapp.data

import com.google.firebase.firestore.PropertyName

/**
 * Firestore 数据模型
 */
data class InventoryItemFirestore(
    var id: String = "",
    val familyId: String = "",
    val name: String = "",
    val category: String = "",
    val quantity: Int = 0,
    val minThreshold: Int? = null,
    val unit: String = "",
    val expiryDate: Long = 0L,
    val ownerId: String = "PUBLIC",
    val ownerName: String = "Public",
    val notes: String = "",
    val location: String = "",
    var pendingSetup: Boolean = false
)