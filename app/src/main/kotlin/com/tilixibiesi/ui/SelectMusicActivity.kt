package com.tilixibiesi.ui
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.util.BackgroundHelper
import com.tilixibiesi.util.WindowUtils
import com.tilixibiesi.R

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*

class SelectMusicActivity : BaseActivity() {
    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val EXTRA_SELECTED_MUSIC = "selected_music_list"
    }

    private lateinit var allMusicList: MutableList<MusicBean>
    private lateinit var filteredList: MutableList<MusicBean>
    private lateinit var lvSelect: ListView
    private lateinit var adapter: SelectAdapter
    private lateinit var btnCancel: Button
    private lateinit var btnConfirm: Button
    private val selectedPositions = HashSet<Int>()
    /** 背景宿主（外层 FrameLayout），承载与内容层叠的背景层 */
    private lateinit var bgHost: View
    private var playlistId: String? = null
    private lateinit var etSearch: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_music)
        setFullScreen()

        bgHost = findViewById(R.id.select_bg_host)
        lvSelect = findViewById(R.id.lv_select)
        btnCancel = findViewById(R.id.btn_cancel)
        btnConfirm = findViewById(R.id.btn_confirm)
        etSearch = findViewById(R.id.et_search)

        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)

        allMusicList = DataFileUtils.loadMusicList().toMutableList()
        val deleted = DataFileUtils.loadDeletedMusicNames()
        allMusicList.removeAll { deleted.contains(it.musicName) }
        val blockedWords = DataFileUtils.loadBlockedWords()
        if (blockedWords.isNotEmpty()) {
            allMusicList.removeAll { bean ->
                blockedWords.any { bean.musicName.contains(it, ignoreCase = true) }
            }
        }

        filteredList = allMusicList.toMutableList()

        adapter = SelectAdapter()
        lvSelect.adapter = adapter

        lvSelect.setOnItemClickListener { _, _, position, _ ->
            if (selectedPositions.contains(position)) selectedPositions.remove(position)
            else selectedPositions.add(position)
            adapter.notifyDataSetChanged()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterMusic(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnCancel.setOnClickListener { finish() }
        btnConfirm.setOnClickListener {
            if (selectedPositions.isEmpty()) {
                Toast.makeText(this, LanguageUtils.getString(this@SelectMusicActivity, R.string.no_song_selected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedList = selectedPositions.map { filteredList[it] }.toList()
            val resultIntent = Intent().apply {
                putExtra(EXTRA_SELECTED_MUSIC, ArrayList(selectedList))
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        applyBackground()
    }

    private fun filterMusic(keyword: String) {
        filteredList.clear()
        if (keyword.isEmpty()) {
            filteredList.addAll(allMusicList)
        } else {
            val lowerKeyword = keyword.lowercase()
            allMusicList.forEach { bean ->
                if (DataFileUtils.getDisplayName(bean.musicName).lowercase().contains(lowerKeyword)) {
                    filteredList.add(bean)
                }
            }
        }
        selectedPositions.clear()
        adapter.notifyDataSetChanged()
    }

private fun setFullScreen() {
        WindowUtils.setFullScreen(this)
    }

    private fun applyBackground() {
        val alphaPercent = SpUtils.getBackgroundAlpha(this)
        BackgroundHelper.applyBackground(this, bgHost, alphaPercent)
    }

    inner class SelectAdapter : BaseAdapter() {
        override fun getCount(): Int = filteredList.size
        override fun getItem(pos: Int): Any = filteredList[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_music_select, parent, false)
            val tvName = view.findViewById<TextView>(R.id.tv_music_name)
            val bean = filteredList[position]
            tvName.text = DataFileUtils.getDisplayName(bean.musicName)

            val selected = selectedPositions.contains(position)
            view.setBackgroundColor(if (selected) Color.WHITE else Color.TRANSPARENT)
            tvName.setTextColor(if (selected) Color.BLACK else Color.parseColor(SpUtils.getFontColor(this@SelectMusicActivity)))
            tvName.textSize = SpUtils.getFontSize(this@SelectMusicActivity).toFloat()
            return view
        }
    }
}
