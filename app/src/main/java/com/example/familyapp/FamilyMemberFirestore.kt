// com.example.familyapp/FamilyMemberFirestore.kt

package com.example.familyapp

import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class FamilyMemberFirestore(
    @get:Exclude var userId: String = "", // 文档ID (即 UID)

    var name: String = "",
    var email: String = "",
    var familyId: String? = null, // 链接到哪个家庭
    var role: String = "member", // 可以添加角色，例如 "owner", "admin"

    @ServerTimestamp
    var createdAt: Date? = null
)