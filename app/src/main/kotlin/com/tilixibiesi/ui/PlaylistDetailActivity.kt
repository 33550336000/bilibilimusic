package com.tilixibiesi.ui
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.model.PlaylistBean
import com.tilixibiesi.data.PlaylistManager
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.data.StoragePaths
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.ProtectedWords
import com.tilixibiesi.util.BackgroundHelper
import com.tilixibiesi.util.DialogHelper
import com.tilixibiesi.util.WindowUtils
import com.tilixibiesi.ui.adapter.MusicAdapter
import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.ui.page.SongsPage
import com.tilixibiesi.ui.SelectMusicActivity
import com.tilixibiesi.R

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import java.io.File
import java.util.Collections
import android.view.GestureDetector
import android.view.MotionEvent
class PlaylistDetailActivity : BaseActivity() {
    private val MUSIC_DIR_REL = "system/axeron/long/Android/TilixiBiesiMusic"
    private lateinit var lvMusic: ListView
    private lateinit var musicAdapter: MusicAdapter
    private lateinit var musicList: MutableList<MusicBean>
    private var playlistId: String? = null
    private lateinit var btnBack: ImageButton
    /** 背景宿主（外层 FrameLayout），承载与内容层叠的背景层 */
    private lateinit var bgHost: View
    private var gestureDetector: GestureDetector? = null
    private var allMusicList: List<MusicBean> = emptyList()
    private lateinit var etSearch: EditText
    private val filteredMusicList = mutableListOf<MusicBean>()

    private var pendingSubPlaylistId: String? = null

    // 拖拽相关变量
    private var isDragging = false
    private var dragImageView: ImageView? = null
    private var dragPosition = -1
    private var dragItemView: View? = null
    private var itemHeight = 0
    private var lastSwappedPosition = -1

    companion object {
        private const val REQUEST_SELECT_MUSIC = 1001
    }

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_playlist_detail)   // 先设置布局
    setFullScreen()                                    // 再全屏

    bgHost = findViewById(R.id.detail_bg_host)
    lvMusic = findViewById(R.id.lv_music_detail)
    btnBack = findViewById(R.id.btn_back)
    etSearch = findViewById(R.id.et_search)
    val lvSubPlaylist = findViewById<ListView>(R.id.lv_sub_playlist)
    val btnAddPlaylist = findViewById<ImageButton>(R.id.btn_add_playlist)
    btnAddPlaylist.setOnClickListener { showCreateSubPlaylistDialog() }

    playlistId = intent.getStringExtra("playlist_id")
    refreshMusicList()

    applyBackground()

    musicAdapter = MusicAdapter(this, filteredMusicList).apply {
        showDeleteButton = true
        originalIndexProvider = { bean -> SongsPage.musicList.indexOf(bean) }
        showMoveButton = true
        onMoveClickListener = object : MusicAdapter.OnMoveClickListener {
            override fun onMoveClick(position: Int, musicBean: MusicBean) {
                showLongPressActionDialog(musicBean)
            }
        }
    }
    lvMusic.adapter = musicAdapter

    etSearch.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            filterMusicInPlaylist(s.toString())
        }
        override fun afterTextChanged(s: Editable?) {}
    })

lvMusic.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
    val musicBean = filteredMusicList.getOrNull(position) ?: return@OnItemClickListener
    val realPos = musicList.indexOf(musicBean)
    if (realPos == -1) return@OnItemClickListener
    checkLocalFileForBean(musicBean)
    MusicPlayerService.musicList = musicList
    SpUtils.saveMusicList(this@PlaylistDetailActivity, musicList)
    val intent = Intent(this@PlaylistDetailActivity, MusicPlayerService::class.java).apply {
        action = MusicPlayerService.ACTION_PLAY
        putExtra(MusicPlayerService.EXTRA_POSITION, realPos)
    }
    startService(intent)
}

    lvMusic.setOnItemLongClickListener { _, view, position, _ ->
        if (position >= 0 && position < filteredMusicList.size) {
            startDrag(view, position)
        }
        true
    }

    musicAdapter.onDeleteClickListener = object : MusicAdapter.OnDeleteClickListener {
        override fun onDeleteClick(position: Int, musicBean: MusicBean) {
            confirmRemoveFromPlaylist(musicBean)
        }
    }

    btnBack.setOnClickListener { finish() }
    initSwipeGesture()
}

    // ---------- 拖拽排序 ----------
    private fun startDrag(itemView: View, position: Int) {
        if (etSearch.text.isNotEmpty()) {
            etSearch.text.clear()
            filterMusicInPlaylist("")
            val bean = filteredMusicList.getOrNull(position) ?: return
            val newPos = filteredMusicList.indexOf(bean)
            if (newPos == -1) return
            val newView = getViewByPosition(newPos) ?: return
            startDrag(newView, newPos)
            return
        }

        // 使用 Canvas 绘制代替废弃的 drawingCache
        val bitmap = Bitmap.createBitmap(itemView.width, itemView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        itemView.draw(canvas)

        val decorView = window.decorView as FrameLayout
        dragImageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            alpha = 0.5f
            val lp = FrameLayout.LayoutParams(itemView.width, itemView.height)
            val loc = IntArray(2)
            itemView.getLocationOnScreen(loc)
            lp.leftMargin = loc[0]
            lp.topMargin = loc[1]
            decorView.addView(this, lp)
        }

        dragItemView = itemView
        itemView.visibility = View.INVISIBLE
        dragPosition = position
        lastSwappedPosition = position
        isDragging = true
        itemHeight = itemView.height
    }

    private fun getViewByPosition(pos: Int): View? {
        val firstPos = lvMusic.firstVisiblePosition
        val index = pos - firstPos
        return if (index in 0 until lvMusic.childCount) lvMusic.getChildAt(index) else null
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isDragging) {
            when (ev.action) {
                MotionEvent.ACTION_MOVE -> {
                    moveDrag(ev.rawX, ev.rawY)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopDrag()
                    return true
                }
            }
        }
        gestureDetector?.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun moveDrag(rawX: Float, rawY: Float) {
        dragImageView?.let { img ->
            val lp = img.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = 0
            lp.topMargin = (rawY - itemHeight / 2).toInt()
            img.layoutParams = lp
        }

        val listLoc = IntArray(2)
        lvMusic.getLocationOnScreen(listLoc)
        val listTop = listLoc[1]
        val listBottom = listTop + lvMusic.height
        val scrollThreshold = 200
        val scrollSpeed = 30

        if (rawY < listTop + scrollThreshold) {
            if (lvMusic.firstVisiblePosition > 0) {
                lvMusic.smoothScrollBy(-scrollSpeed, 0)
            }
        } else if (rawY > listBottom - scrollThreshold) {
            if (lvMusic.lastVisiblePosition < filteredMusicList.size - 1) {
                lvMusic.smoothScrollBy(scrollSpeed, 0)
            }
        }

        val targetPos = lvMusic.pointToPosition(0, (rawY - listTop).toInt())
        if (targetPos != -1 && targetPos != lastSwappedPosition && targetPos != dragPosition) {
            Collections.swap(filteredMusicList, dragPosition, targetPos)
            Collections.swap(musicList, dragPosition, targetPos)
            musicAdapter.notifyDataSetChanged()
            lastSwappedPosition = targetPos
            dragPosition = targetPos
            dragItemView?.visibility = View.VISIBLE
            dragItemView = getViewByPosition(dragPosition)
            dragItemView?.visibility = View.INVISIBLE
        }
    }

    private fun stopDrag() {
        dragImageView?.let { (window.decorView as FrameLayout).removeView(it) }
        dragImageView = null
        dragItemView?.visibility = View.VISIBLE
        dragItemView = null
        isDragging = false
        dragPosition = -1
        lastSwappedPosition = -1

        val currentPlaylistId = playlistId ?: return
        val allPlaylists = PlaylistManager.getPlaylists(this)
        val targetPlaylist = findPlaylistById(allPlaylists, currentPlaylistId)
        targetPlaylist?.let { pl ->
            pl.musicList.clear()
            pl.musicList.addAll(musicList)
            PlaylistManager.savePlaylists(this, allPlaylists)
        }
        allMusicList = musicList.toList()
        musicAdapter.notifyDataSetChanged()
    }

    private fun findPlaylistById(list: List<PlaylistBean>, id: String): PlaylistBean? {
        for (pl in list) {
            if (pl.id == id) return pl
            val found = findPlaylistById(pl.subPlaylists, id)
            if (found != null) return found
        }
        return null
    }
    private fun filterMusicInPlaylist(keyword: String) {
        filteredMusicList.clear()
        if (keyword.isEmpty()) {
            filteredMusicList.addAll(allMusicList)
        } else {
            val lowerKeyword = keyword.lowercase()
            for (bean in allMusicList) {
                if (DataFileUtils.getDisplayName(bean.musicName).lowercase().contains(lowerKeyword)) {
                    filteredMusicList.add(bean)
                }
            }
        }
        musicAdapter.notifyDataSetChanged()
    }

private fun showLongPressActionDialog(musicBean: MusicBean) {
    showMaterialDialog(
        AlertDialog.Builder(this)
            .setTitle(DataFileUtils.getDisplayName(musicBean.musicName))
            .setNegativeButton(R.string.rename_button) { _, _ -> showRenameDialog(musicBean) }
            .setPositiveButton(R.string.remove) { _, _ -> confirmRemoveFromPlaylist(musicBean) }
    )
}

    private fun refreshMusicList() {
        val deletedNames = DataFileUtils.loadDeletedMusicNames()
        val blockedWords = DataFileUtils.loadBlockedWords()
        val currentPlaylist = playlistId?.let { PlaylistManager.getPlaylistById(this, it) }

        musicList = currentPlaylist?.musicList
            ?.filter { !deletedNames.contains(it.musicName) }
            ?.toMutableList() ?: mutableListOf()

        if (blockedWords.isNotEmpty()) {
            musicList.removeAll { bean ->
                blockedWords.any { word -> bean.musicName.contains(word, ignoreCase = true) }
            }
        }

        allMusicList = musicList.toList()
        filteredMusicList.clear()
        filteredMusicList.addAll(musicList)
        if (::musicAdapter.isInitialized) {
            musicAdapter.notifyDataSetChanged()
        }
        refreshSubPlaylists()
    }

    private fun refreshSubPlaylists() {
        val lvSubPlaylist = findViewById<ListView>(R.id.lv_sub_playlist)
        val subPlaylists = playlistId?.let { PlaylistManager.getSubPlaylists(this, it) } ?: emptyList()
        if (subPlaylists.isEmpty()) {
            lvSubPlaylist.visibility = View.GONE
        } else {
            lvSubPlaylist.visibility = View.VISIBLE
            val adapter = SubPlaylistAdapter(this, subPlaylists)
            lvSubPlaylist.adapter = adapter
        }
    }

private fun confirmRemoveFromPlaylist(musicBean: MusicBean) {
    showMaterialDialog(
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_song_title)
            .setMessage(LanguageUtils.getString(this@PlaylistDetailActivity, R.string.remove_from_playlist_message, DataFileUtils.getDisplayName(musicBean.musicName)))
            .setPositiveButton(R.string.delete) { _, _ ->
                PlaylistManager.removeMusicFromPlaylist(this, playlistId!!, musicBean)
                refreshMusicList()
                Toast.makeText(this, R.string.removed_from_playlist, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
    )
}

private fun showRenameDialog(musicBean: MusicBean) {
    val original = musicBean.musicName
    val currentDisplay = DataFileUtils.getDisplayName(original)
    showMaterialDialog(
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_song_title)
            .setMessage(LanguageUtils.getString(this@PlaylistDetailActivity, R.string.rename_current_name, currentDisplay))
            .setPositiveButton(R.string.rename_button) { _, _ -> showRenameInputDialog(original) }
            .setNegativeButton(R.string.cancel, null)
    )
}

private fun showRenameInputDialog(originalName: String) {
    val input = EditText(this).apply {
        setText(DataFileUtils.getDisplayName(originalName))
        setSelection(text.length)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        background = resources.getDrawable(R.drawable.edittext_bg, null)
    }
    showMaterialDialog(
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_input_title)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, R.string.rename_empty_error, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                for (keyword in ProtectedWords.KEYWORDS) {
                    if (originalName.contains(keyword)) {
                        showMaterialDialog(
                            AlertDialog.Builder(this)
                                .setTitle(R.string.rename_restricted_title)
                                .setMessage(LanguageUtils.getString(this@PlaylistDetailActivity, R.string.rename_restricted_message, DataFileUtils.getDisplayName(originalName)))
                                .setPositiveButton(R.string.confirm, null)
                        )
                        return@setPositiveButton
                    }
                }
                DataFileUtils.saveRenameEntry(originalName, newName)
                musicAdapter.notifyDataSetChanged()
                Toast.makeText(this, R.string.rename_success, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
    )
}
private fun showCreateSubPlaylistDialog() {
    val input = EditText(this).apply {
        hint = LanguageUtils.getString(this@PlaylistDetailActivity, R.string.sub_playlist_name_hint)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        background = resources.getDrawable(R.drawable.edittext_bg, null)
    }
    showMaterialDialog(
        AlertDialog.Builder(this)
            .setTitle(R.string.create_sub_playlist_title)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.rename_empty_error, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val parentId = playlistId ?: run {
                    Toast.makeText(this, R.string.playlist_info_error, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                PlaylistManager.addSubPlaylist(this, parentId, name)
                Toast.makeText(this, LanguageUtils.getString(this@PlaylistDetailActivity, R.string.sub_playlist_created, name), Toast.LENGTH_SHORT).show()
                refreshSubPlaylists()
            }
            .setNegativeButton(R.string.cancel, null)
    )
}
private fun setFullScreen() {
        WindowUtils.setFullScreen(this)
    }

private fun initSwipeGesture() {
    gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD = 100
        private val SWIPE_VELOCITY_THRESHOLD = 100
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null) return false
            val diffX = e2.x - e1.x
            val diffY = e2.y - e1.y
            if (Math.abs(diffX) > Math.abs(diffY) * 1.5 &&
                Math.abs(diffX) > SWIPE_THRESHOLD &&
                Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
            ) {
                finish()
                if (diffX > 0) {
                    overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
                } else {
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                }
                return true
            }
            return false
        }
    })
}
    private fun checkLocalFileForBean(bean: MusicBean) {
        val musicDir = StoragePaths.resolveWrite(MUSIC_DIR_REL)
        if (!musicDir.exists()) return
        val localFile = File(musicDir, bean.musicName)
        if (localFile.exists()) {
            bean.isDownloaded = true
            bean.localPath = localFile.absolutePath
        }
    }

    private fun applyBackground() {
        val alphaPercent = SpUtils.getBackgroundAlpha(this)
        BackgroundHelper.applyBackground(this, bgHost, alphaPercent)
    }

@Deprecated("Deprecated in Java")
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQUEST_SELECT_MUSIC && resultCode == RESULT_OK && data != null) {
        @Suppress("UNCHECKED_CAST")
        val selectedMusic = data.getSerializableExtra(SelectMusicActivity.EXTRA_SELECTED_MUSIC) as? List<MusicBean>
        if (selectedMusic != null && selectedMusic.isNotEmpty() && pendingSubPlaylistId != null) {
            for (music in selectedMusic) {
                PlaylistManager.addMusicToPlaylist(this, pendingSubPlaylistId!!, music)
            }
            refreshMusicList()
            Toast.makeText(this, LanguageUtils.getString(this@PlaylistDetailActivity, R.string.songs_added_format, selectedMusic.size), Toast.LENGTH_SHORT).show()
        }
        pendingSubPlaylistId = null
    }
}
    override fun onResume() {
        super.onResume()
        refreshMusicList()
        applyBackground()
        // 本 Activity 可见：背景视频播放并出声
        BackgroundHelper.setActive(bgHost, true)
    }

    override fun onPause() {
        super.onPause()
        // 不可见（含被别的 Activity 覆盖）：暂停解码并静音
        BackgroundHelper.setActive(bgHost, false)
    }

    override fun onDestroy() {
        // 释放背景视频解码器（VideoView 脱离视图树不会自动 release）
        BackgroundHelper.release(bgHost)
        super.onDestroy()
    }

    // ---------- 对话框样式 ----------
    private fun showMaterialDialog(builder: AlertDialog.Builder): AlertDialog {
        return DialogHelper.createStyledDialog(this, builder)
    }

    // ---------- 子歌单适配器 ----------
class ViewHolder(val tvName: TextView, val btnAdd: ImageButton)
inner class SubPlaylistAdapter(
    private val context: android.content.Context,
    private val list: List<PlaylistBean>
) : BaseAdapter() {

    // 1. 添加 ViewHolder 定义

    private val inflater = LayoutInflater.from(context)

    override fun getCount(): Int = list.size
    override fun getItem(pos: Int): Any = list[pos]
    override fun getItemId(pos: Int): Long = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        var view = convertView
        val holder: ViewHolder
        if (view == null) {
            view = inflater.inflate(R.layout.item_playlist, parent, false)
            holder = ViewHolder(
                tvName = view.findViewById(R.id.tv_playlist_name),
                btnAdd = view.findViewById(R.id.btn_add_to_playlist)
            )
            view.tag = holder
        } else {
            holder = view.tag as ViewHolder
        }

        val sub = list[pos]
        holder.tvName.text = LanguageUtils.getString(context, R.string.sub_playlist_item_format, sub.name, sub.musicList.size)

        try {
            holder.tvName.setTextColor(Color.parseColor(SpUtils.getFontColor(context)))
        } catch (e: Exception) {
            holder.tvName.setTextColor(Color.WHITE)
        }
        holder.tvName.textSize = (SpUtils.getFontSize(context) + 2).toFloat()

        view!!.setOnClickListener {
            val intent = Intent(context, PlaylistDetailActivity::class.java)
            intent.putExtra("playlist_id", sub.id)
            startActivity(intent)
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        view.setOnLongClickListener {
            showSubPlaylistOptionsDialog(sub)
            true
        }

        holder.btnAdd.setOnClickListener {
            pendingSubPlaylistId = sub.id
            val intent = Intent(context, SelectMusicActivity::class.java)
            intent.putExtra(SelectMusicActivity.EXTRA_PLAYLIST_ID, sub.id)
            startActivityForResult(intent, REQUEST_SELECT_MUSIC)
        }

        return view
    }

    private fun showSubPlaylistOptionsDialog(sub: PlaylistBean) {
        // 2. 使用 context 构造对话框，调用外部类的 showMaterialDialog
        this@PlaylistDetailActivity.showMaterialDialog(
            AlertDialog.Builder(context)
                .setTitle(sub.name)
                .setItems(arrayOf(
                    LanguageUtils.getString(this@PlaylistDetailActivity, R.string.sub_playlist_delete),
                    LanguageUtils.getString(this@PlaylistDetailActivity, R.string.rename_button)
                )) { _, which ->
                    if (which == 0) {
                        showSubPlaylistDeleteConfirm(sub)
                    } else {
                        showSubPlaylistRenameDialog(sub)
                    }
                }
        )
    }

private fun showSubPlaylistDeleteConfirm(sub: PlaylistBean) {
    this@PlaylistDetailActivity.showMaterialDialog(
        AlertDialog.Builder(context)
            .setTitle(R.string.sub_playlist_delete_title)
            .setMessage(LanguageUtils.getString(this@PlaylistDetailActivity, R.string.sub_playlist_delete_message, sub.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                val parentId = playlistId ?: return@setPositiveButton
                val allPlaylists = PlaylistManager.getPlaylists(context)
                val parent = findPlaylistById(allPlaylists, parentId)  // 外部类已有此方法
                parent?.subPlaylists?.removeAll { it.id == sub.id }

                // 保存的是刚刚修改过的同一个列表
                PlaylistManager.savePlaylists(context, allPlaylists)

                refreshSubPlaylists()
                Toast.makeText(context, R.string.sub_playlist_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
    )
}

private fun showSubPlaylistRenameDialog(sub: PlaylistBean) {
    val input = EditText(this@PlaylistDetailActivity).apply {
        setText(sub.name)
        setSelection(text.length)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        background = resources.getDrawable(R.drawable.edittext_bg, null)
    }
    this@PlaylistDetailActivity.showMaterialDialog(
        AlertDialog.Builder(context)
            .setTitle(R.string.rename_playlist_title)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(context, R.string.rename_empty_error, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val parentId = playlistId ?: return@setPositiveButton
                val allPlaylists = PlaylistManager.getPlaylists(context)
                val parent = findPlaylistById(allPlaylists, parentId)
                parent?.subPlaylists?.find { it.id == sub.id }?.name = newName

                PlaylistManager.savePlaylists(context, allPlaylists)

                refreshSubPlaylists()
                Toast.makeText(context, R.string.rename_success, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
    )
}
}
}
