package com.example.familyapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.familyapp.databinding.ActivityFamilyMemberBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class FamilyMemberActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFamilyMemberBinding
    private val db = Firebase.firestore
    private val auth = FirebaseAuth.getInstance()
    private var selectedColorHex: String? = null

    private val colorOptions = listOf(
        "#2196F3", "#F44336", "#4CAF50", "#FF9800", "#9C27B0", "#00BCD4", "#E91E63"
    )

    private var currentFamilyId: String? = null
    private var currentFamilyName: String? = null
    private var currentMemberLimit: Int = 5
    private val occupiedColors = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFamilyMemberBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Family Management"
        binding.toolbar.setTitleTextColor(android.graphics.Color.WHITE)
        // 1. 家庭设置：原地展开编辑区
        binding.btnEditFamily.setOnClickListener {
            if (binding.llFamilyEditArea.visibility == View.GONE) {
                binding.llFamilyEditArea.visibility = View.VISIBLE
                binding.etEditFamilyName.setText(currentFamilyName)
                binding.etEditMemberLimit.setText(currentMemberLimit.toString())
            } else {
                binding.llFamilyEditArea.visibility = View.GONE
            }
        }

        binding.btnSaveFamily.setOnClickListener {
            saveFamilySettings()
        }
        binding.btnLogOut.setOnClickListener {
            performLogout()
        }
        setupBottomNav()
        loadFamilyData()
    }

    private fun loadFamilyData() {
        val uid = auth.currentUser?.uid ?: return

        db.collection("users").document(uid).get()
            .addOnSuccessListener { userDoc ->
                currentFamilyId = userDoc.getString("familyId") ?: ""
                selectedColorHex = userDoc.getString("userColor") ?: "#2196F3"

                if (currentFamilyId!!.isNotEmpty()) {
                    db.collection("families").document(currentFamilyId!!).get().addOnSuccessListener { famDoc ->
                        val code = famDoc.getString("code") ?: "N/A"
                        currentFamilyName = famDoc.getString("name") ?: "My Family"
                        currentMemberLimit = famDoc.getLong("memberLimit")?.toInt() ?: 5

                        binding.tvMyName.text = currentFamilyName
                        binding.tvFamilyCode.text = code

                        setupColorGrid(currentFamilyId!!)
                        fetchFamilyMembers(currentFamilyId!!)
                    }
                }

                binding.btnCopyCode.setOnClickListener {
                    val codeToCopy = binding.tvFamilyCode.text.toString()
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Family Code", codeToCopy)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Code Copied!", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun saveFamilySettings() {
        val newName = binding.etEditFamilyName.text.toString().trim()
        val newLimit = binding.etEditMemberLimit.text.toString().toIntOrNull() ?: currentMemberLimit

        if (currentFamilyId != null && newName.isNotEmpty()) {
            db.collection("families").document(currentFamilyId!!).update(mapOf(
                "name" to newName,
                "memberLimit" to newLimit
            )).addOnSuccessListener {
                binding.llFamilyEditArea.visibility = View.GONE
                binding.tvMyName.text = newName
                currentFamilyName = newName
                Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchFamilyMembers(familyId: String) {
        db.collection("families").document(familyId).addSnapshotListener { doc, _ ->
            val memberUids = doc?.get("members") as? List<String> ?: emptyList()

            if (memberUids.isNotEmpty()) {
                db.collection("users").whereIn(FieldPath.documentId(), memberUids)
                    .addSnapshotListener { userSnaps, _ ->
                        binding.llMembersContainer.removeAllViews()
                        userSnaps?.forEach { userDoc ->
                            val name = userDoc.getString("name") ?: "Unknown"
                            val colorHex = userDoc.getString("userColor") ?: "#2196F3"
                            val isMe = userDoc.id == auth.currentUser?.uid

                            val itemContainer = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(0, 16, 0, 16)
                            }

                            val row = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                            }

                            val avatar = View(this).apply {
                                layoutParams = LinearLayout.LayoutParams(24.toPx(), 24.toPx())
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(Color.parseColor(colorHex))
                                }
                            }

                            val nameTv = TextView(this).apply {
                                text = if (isMe) "$name (You)" else name
                                textSize = 16f
                                // 🟢 其他家人名字显示他们的代表色
                                setTextColor(Color.parseColor(colorHex))
                                setTypeface(null, if (isMe) Typeface.BOLD else Typeface.NORMAL)
                                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = 16.toPx() }
                            }

                            row.addView(avatar)
                            row.addView(nameTv)
                            itemContainer.addView(row)

                            // 🟢 原地修改名字逻辑：点击自己那一行
                            if (isMe) {
                                row.setOnClickListener {
                                    showInlineNameEdit(itemContainer, name)
                                }
                            }

                            binding.llMembersContainer.addView(itemContainer)
                        }
                    }
            }
        }
    }

    private fun showInlineNameEdit(container: LinearLayout, oldName: String) {
        container.removeAllViews()
        val editLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 🟢 增加一个浅灰色背景，让编辑区明显
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            setPadding(12.toPx(), 8.toPx(), 12.toPx(), 8.toPx())
        }

        val et = EditText(this).apply {
            setText(oldName)
            // 🟢 强制设置文字颜色和背景
            setTextColor(Color.parseColor("#212121"))
            setHintTextColor(Color.parseColor("#9E9E9E"))
            setBackgroundColor(Color.WHITE) // 输入区域用纯白
            setPadding(12.toPx(), 8.toPx(), 12.toPx(), 8.toPx())
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            requestFocus()
        }

        val btn = ImageButton(this).apply {
            // 使用系统自带的保存（勾选）图标
            setImageResource(android.R.drawable.checkbox_on_background)
            background = null
            setColorFilter(Color.parseColor("#2E7D32")) // 绿色代表保存
            layoutParams = LinearLayout.LayoutParams(40.toPx(), 40.toPx())
            setOnClickListener {
                val newN = et.text.toString().trim()
                if (newN.isNotEmpty()) {
                    db.collection("users").document(auth.currentUser!!.uid).update("name", newN)
                }
            }
        }

        editLayout.addView(et)
        editLayout.addView(btn)
        container.addView(editLayout)
    }

    private fun setupColorGrid(familyId: String) {
        db.collection("users").whereEqualTo("familyId", familyId).addSnapshotListener { snaps, _ ->
            occupiedColors.clear()
            snaps?.forEach { doc ->
                if (doc.id != auth.currentUser?.uid) {
                    doc.getString("userColor")?.let { occupiedColors.add(it) }
                } else {
                    selectedColorHex = doc.getString("userColor")
                }
            }

            binding.colorGrid.removeAllViews()
            colorOptions.forEach { hex ->
                val isOccupied = occupiedColors.contains(hex)
                val container = FrameLayout(this).apply {
                    val size = 45
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = size.toPx(); height = size.toPx(); setMargins(16, 16, 16, 16)
                    }
                }

                val colorView = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(-1, -1)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(hex))
                        alpha = if (isOccupied) 45 else 255
                        if (hex == selectedColorHex) setStroke(8, Color.BLACK)
                    }
                }
                container.addView(colorView)

                if (isOccupied) {
                    val skipIcon = ImageView(this).apply {
                        layoutParams = FrameLayout.LayoutParams(-1, -1).apply {
                            setPadding(10.toPx(), 10.toPx(), 10.toPx(), 10.toPx())
                        }
                        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                        setColorFilter(Color.DKGRAY)
                        imageAlpha = 200
                    }
                    container.addView(skipIcon)
                }

                container.setOnClickListener {
                    if (isOccupied) {
                        Toast.makeText(this, "🚫 Color Occupied", Toast.LENGTH_SHORT).show()
                    } else {
                        updateUserColor(hex)
                    }
                }
                binding.colorGrid.addView(container)
            }
        }
    }

    private fun updateUserColor(hex: String) {
        db.collection("users").document(auth.currentUser!!.uid).update("userColor", hex)
    }

    private fun setupBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_family
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_inventory -> {
                    // 返回主页，保持 Family ID 传递逻辑一致
                    finish()
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_shopping -> {
                    // 跳转到购物清单，必须传递 currentFamilyId
                    val intent = Intent(this, ShoppingListActivity::class.java)
                    intent.putExtra("FAMILY_ID", currentFamilyId)
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_family -> true
                else -> false
            }
        }
    }
    private fun performLogout() {
        // 1. 执行 Firebase 登出
        auth.signOut()

        // 2. 显示提示
        Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()

        // 3. 跳转到 LoginActivity 并清空之前的页面栈
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)

        // 4. 结束当前 Activity
        finish()
    }
    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()
}