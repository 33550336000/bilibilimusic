package com.tilixibiesi.ui.page

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.tilixibiesi.R
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.PlaylistManager
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.model.PlaylistBean
import com.tilixibiesi.ui.PlaylistDetailActivity
import com.tilixibiesi.ui.SelectMusicActivity
import com.tilixibiesi.util.BackgroundHelper
import com.tilixibiesi.util.DialogHelper

/**
 * 播放列表页（原 PlaylistActivity）。
 *
 * 与原先的差异：
 *  - 继承 [BasePage] 而非 Activity，作为 MainPagerActivity 内的一页存在；
 *  - 原本的 onFling 侧滑手势已交由 HorizontalPager 统一处理，页面本身不再拦截触摸；
 *  - 跳转其它 Activity 改为宿主转发 startActivityForResult，避免依赖 result 语义。
 */
class PlaylistPage(base: Context) : BasePage(base) {

    private lateinit var lvPlaylists: ListView
    private lateinit var playlistList: MutableList<PlaylistBean>
    private lateinit var adapter: PlaylistAdapter
    private lateinit var btnAddPlaylist: ImageButton
    private lateinit var tvPlaylistHint: TextView
    /** 背景宿主（外层 FrameLayout），承载与内容层叠的背景层 */
    private lateinit var bgHost: View
    private var pendingPlaylistId: String? = null

    companion object {
        private const val REQUEST_SELECT_MUSIC = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_playlist)

        bgHost = findViewById(R.id.playlist_bg_host)!!
        lvPlaylists = findViewById(R.id.lv_playlists)!!
        btnAddPlaylist = findViewById(R.id.btn_add_playlist)!!
        tvPlaylistHint = findViewById(R.id.tv_playlist_hint)!!

        applyBackground()
        applyTitleStyle()
        loadPlaylists()

        adapter = PlaylistAdapter(this, playlistList)
        lvPlaylists.adapter = adapter

        btnAddPlaylist.setOnClickListener { showCreatePlaylistDialog() }
    }

    override fun onResume() {
        applyBackground()
        applyTitleStyle()
        loadPlaylists()
        adapter.notifyDataSetChanged()
    }

    private fun showMaterialDialog(builder: AlertDialog.Builder): AlertDialog =
        DialogHelper.createStyledDialog(this, builder)

    private fun applyBackground() {
        val alphaPercent = SpUtils.getBackgroundAlpha(this)
        BackgroundHelper.applyBackground(this, bgHost, alphaPercent)
    }

    private fun applyTitleStyle() {
        val fontColor = try {
            Color.parseColor(SpUtils.getFontColor(this))
        } catch (_: Exception) {
            Color.WHITE
        }
        val fontSize = SpUtils.getFontSize(this).toFloat()
        tvPlaylistHint.setTextColor(fontColor)
        tvPlaylistHint.textSize = fontSize
    }

    private fun loadPlaylists() {
        val loaded = PlaylistManager.getPlaylists(this).toMutableList()
        val deletedNames = DataFileUtils.loadDeletedMusicNames()
        val blockedWords = DataFileUtils.loadBlockedWords()

        for (pl in loaded) {
            pl.musicList.removeAll { deletedNames.contains(it.musicName) }
            if (blockedWords.isNotEmpty()) {
                pl.musicList.removeAll { bean ->
                    blockedWords.any { word -> bean.musicName.contains(word, ignoreCase = true) }
                }
            }
        }

        // 复用同一个 list 引用，原地清空再填充，使 adapter 感知变化
        if (!::playlistList.isInitialized) {
            playlistList = loaded
        } else {
            playlistList.clear()
            playlistList.addAll(loaded)
        }
    }

    private fun showCreatePlaylistDialog() {
        val fontColor = try {
            val dialogFont = SpUtils.getDialogFontColor(this)
            if (dialogFont.isEmpty()) Color.parseColor(SpUtils.getFontColor(this))
            else Color.parseColor(dialogFont)
        } catch (_: Exception) {
            Color.WHITE
        }

        val input = EditText(this).apply {
            hint = LanguageUtils.getString(this@PlaylistPage, R.string.playlist_name_hint)
            setTextColor(fontColor)
            setHintTextColor(Color.GRAY)
        }
        showMaterialDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.new_playlist_title)
                .setView(input)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) {
                        Toast.makeText(this, R.string.rename_empty_error, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    PlaylistManager.createPlaylist(this, name)
                    loadPlaylists()
                    adapter.notifyDataSetChanged()
                }
                .setNegativeButton(R.string.cancel, null)
        )
    }

    private fun showPlaylistOptionsDialog(playlist: PlaylistBean) {
        showMaterialDialog(
            AlertDialog.Builder(this)
                .setTitle(
                    LanguageUtils.getString(
                        this@PlaylistPage, R.string.playlist_options_title, playlist.name
                    )
                )
                .setItems(
                    arrayOf(
                        LanguageUtils.getString(this@PlaylistPage, R.string.rename_button),
                        LanguageUtils.getString(this@PlaylistPage, R.string.delete)
                    )
                ) { _, which ->
                    if (which == 0) showRenameDialog(playlist)
                    else showDeletePlaylistConfirm(playlist)
                }
        )
    }

    private fun showRenameDialog(playlist: PlaylistBean) {
        val fontColor = try {
            val dialogFont = SpUtils.getDialogFontColor(this)
            if (dialogFont.isEmpty()) Color.parseColor(SpUtils.getFontColor(this))
            else Color.parseColor(dialogFont)
        } catch (_: Exception) {
            Color.WHITE
        }

        val input = EditText(this).apply {
            setText(playlist.name)
            setSelection(text.length)
            setTextColor(fontColor)
            setHintTextColor(Color.GRAY)
        }
        showMaterialDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.rename_playlist_title)
                .setView(input)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isEmpty()) {
                        Toast.makeText(this, R.string.rename_empty_error, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    showMaterialDialog(
                        AlertDialog.Builder(this)
                            .setTitle(R.string.confirm_rename_title)
                            .setMessage(
                                LanguageUtils.getString(
                                    this@PlaylistPage, R.string.confirm_rename_message,
                                    playlist.name, newName
                                )
                            )
                            .setPositiveButton(R.string.confirm) { _, _ ->
                                PlaylistManager.renamePlaylist(this, playlist.id, newName)
                                loadPlaylists()
                                adapter.notifyDataSetChanged()
                                Toast.makeText(this, R.string.rename_success, Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(R.string.cancel, null)
                    )
                }
                .setNegativeButton(R.string.cancel, null)
        )
    }

    private fun showDeletePlaylistConfirm(playlist: PlaylistBean) {
        showMaterialDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_playlist_title)
                .setMessage(
                    LanguageUtils.getString(
                        this@PlaylistPage, R.string.delete_playlist_message, playlist.name
                    )
                )
                .setPositiveButton(R.string.delete) { _, _ ->
                    PlaylistManager.deletePlaylist(this, playlist.id)
                    loadPlaylists()
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, R.string.playlist_deleted, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_SELECT_MUSIC && resultCode == android.app.Activity.RESULT_OK && data != null) {
            @Suppress("UNCHECKED_CAST")
            val selectedMusic =
                data.getSerializableExtra(SelectMusicActivity.EXTRA_SELECTED_MUSIC) as? List<MusicBean>
            if (selectedMusic != null && selectedMusic.isNotEmpty() && pendingPlaylistId != null) {
                for (music in selectedMusic) {
                    PlaylistManager.addMusicToPlaylist(this, pendingPlaylistId!!, music)
                }
                loadPlaylists()
                adapter.notifyDataSetChanged()
                Toast.makeText(
                    this,
                    LanguageUtils.getString(
                        this@PlaylistPage, R.string.songs_added_format, selectedMusic.size
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private inner class PlaylistAdapter(
        private val ctx: Context,
        private val list: MutableList<PlaylistBean>
    ) : BaseAdapter() {

        private val inflater = LayoutInflater.from(ctx)

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

            val pl = list[pos]
            holder.tvName.text = LanguageUtils.getString(
                ctx, R.string.playlist_item_format, pl.name, pl.musicList.size
            )

            val fontColor = SpUtils.getFontColor(ctx)
            val fontSize = SpUtils.getFontSize(ctx)
            try {
                holder.tvName.setTextColor(Color.parseColor(fontColor))
            } catch (_: Exception) {
                holder.tvName.setTextColor(Color.WHITE)
            }
            holder.tvName.textSize = (fontSize + 2).toFloat()

            view!!.setOnClickListener {
                val intent = Intent(ctx, PlaylistDetailActivity::class.java)
                intent.putExtra("playlist_id", pl.id)
                ctx.startActivity(intent)
            }

            view.setOnLongClickListener {
                showPlaylistOptionsDialog(pl)
                true
            }

            holder.btnAdd.setOnClickListener {
                pendingPlaylistId = pl.id
                val intent = Intent(ctx, SelectMusicActivity::class.java)
                intent.putExtra(SelectMusicActivity.EXTRA_PLAYLIST_ID, pl.id)
                // 由宿主转发，结果会回到本页的 onActivityResult
                startActivityForResult(intent, REQUEST_SELECT_MUSIC)
            }

            return view
        }

        private inner class ViewHolder(
            val tvName: TextView,
            val btnAdd: ImageButton
        )
    }
}
