package com.grinya.voicesampleprep

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.grinya.voicesampleprep.model.Bank
import com.grinya.voicesampleprep.model.BankManager

class MainActivity : AppCompatActivity() {

    private lateinit var bankManager: BankManager
    private lateinit var rv: RecyclerView
    private lateinit var txtEmpty: TextView
    private lateinit var adapter: BankAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bankManager = BankManager(this)
        rv = findViewById(R.id.rvBanks)
        txtEmpty = findViewById(R.id.txtEmpty)
        adapter = BankAdapter()
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<View>(R.id.fabAdd).setOnClickListener { showCreateBankDialog() }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** E10: DiffUtil вместо notifyDataSetChanged. */
    private fun reload() {
        val banks = bankManager.listBanks()
        adapter.submit(banks)
        txtEmpty.visibility = if (banks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showCreateBankDialog() {
        val input = EditText(this)
        input.hint = "Имя банка"
        input.inputType = InputType.TYPE_CLASS_TEXT
        AlertDialog.Builder(this).setTitle("Новый банк").setView(input)
        .setPositiveButton("Создать") { _, _ ->
            var name = input.text.toString().trim()
            if (name.isEmpty()) name = "bank"
            val b = bankManager.createBank(name)
            reload()
            openBank(b)
        }.setNegativeButton("Отмена", null).show()
    }

    /** E11: передаём имя банка через Intent extra. */
    private fun openBank(b: Bank) {
        val i = Intent(this, BankActivity::class.java)
        i.putExtra("bankName", b.name)
        startActivity(i)
    }

    /** R: переименование банка. */
    private fun showRenameBankDialog(b: Bank) {
        val input = EditText(this)
        input.setText(b.name)
        input.setSelection(input.text.length)
        input.inputType = InputType.TYPE_CLASS_TEXT

        AlertDialog.Builder(this).setTitle("Переименовать банк").setView(input)
        .setPositiveButton("OK") { _, _ ->
            val newName = input.text.toString().trim()
            if (newName.isEmpty()) return@setPositiveButton
            if (bankManager.renameBank(b, newName)) {
                adapter.setDataDirect(bankManager.listBanks())
            } else {
                Toast.makeText(this, "Не удалось (занято?)", Toast.LENGTH_SHORT).show()
            }
        }.setNegativeButton("Отмена", null).show()
    }

    // ---------- adapter ----------

    private inner class BankAdapter : RecyclerView.Adapter<BankAdapter.VH>() {
        private var data: List<Bank> = emptyList()

        fun submit(newData: List<Bank>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int = data.size
                    override fun getNewListSize(): Int = newData.size

                    override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    data[oldPos].name == newData[newPos].name

                    override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                        val a = data[oldPos]
                        val b = newData[newPos]
                        return a.name == b.name && a.clips.size == b.clips.size
                    }
            })
            data = newData
            diff.dispatchUpdatesTo(this)
        }

        fun setDataDirect(newData: List<Bank>) {
            data = newData
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bank, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val b = data[pos]
            val prefix = if (b.isProject) "📁 [PROJECT] " else ""
            h.name.text = prefix + b.name
            h.info.text = "${b.clips.size} клипов"

            h.itemView.setOnClickListener { openBank(b) }
            h.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle(b.name)
                .setItems(arrayOf("Переименовать", "Удалить")) { _, which ->
                    if (which == 0) {
                        showRenameBankDialog(b)
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                        .setTitle("Удалить банк?").setMessage(b.name)
                        .setPositiveButton("Удалить") { _, _ ->
                            bankManager.deleteBank(b)
                            reload()
                        }.setNegativeButton("Отмена", null).show()
                    }
                }.show()
                true
            }
        }

        override fun getItemCount(): Int = data.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.txtBankName)
            val info: TextView = v.findViewById(R.id.txtBankInfo)
        }
    }
}
