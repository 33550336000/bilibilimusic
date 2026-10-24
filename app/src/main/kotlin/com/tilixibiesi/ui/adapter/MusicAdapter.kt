package com.tilixibiesi.ui.adapter
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.util.ViewUtils
import com.tilixibiesi.R

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.TextView
import java.util.IdentityHashMap

class MusicAdapter(
    private val context: Context,
    private val musicList: MutableList<MusicBean>
) : BaseAdapter() {
    var showMoveButton: Boolean = false
    var onMoveClickListener: OnMoveClickListener? = null
    var onItemLongClickListener: OnItemLongClickListener? = null
    var onAddToPlaylistClickListener: OnAddToPlaylistClickListener? = null
    var onDeleteClickListener: OnDeleteClickListener? = null
    var originalIndexProvider: ((MusicBean) -> Int)? = null
    var showAddButton: Boolean = true
    var showDeleteButton: Boolean = false

    // ---- 原列表序号缓存 ----
    // 调用方传进来的 originalIndexProvider 通常是 allMusicList.indexOf(...)，
    // 逐行调用会退化成 O(n²)。这里按 bean 身份做一次记忆化，notifyDataSetChanged 时失效。
    private val indexCache = IdentityHashMap<MusicBean, Int>()

    override fun notifyDataSetChanged() {
        indexCache.clear()
        // 设置页可能刚改过字体颜色/字号，下一次绑定重新读一次即可
        themeLoaded = false
        super.notifyDataSetChanged()
    }

    private fun originalIndex(bean: MusicBean): Int {
        val provider = originalIndexProvider ?: return -1
        indexCache[bean]?.let { return it }
        val index = provider(bean)
        indexCache[bean] = index
        return index
    }

    // ---- 主题相关取值缓存 ----
    // 每次 getView 都读 SharedPreferences + parseColor 是列表滚动/每秒重绑时的主要开销，
    // 这里缓存一份；设置页改动颜色字号后调用 invalidateTheme() 失效。
    private var themeFontColor: Int = Color.WHITE
    private var themeFontSize: Int = 15
    private var themeLoaded = false

    private val playingColor: Int by lazy { Color.HSVToColor(floatArrayOf(210f, 1f, 1f)) }
    private val authorColor: Int by lazy { Color.parseColor("#AAAAAA") }
    private val deleteColor: Int by lazy { Color.parseColor("#FF0000") }

    fun invalidateTheme() {
        themeLoaded = false
    }

    private fun ensureTheme() {
        if (themeLoaded) return
        themeLoaded = true
        themeFontSize = SpUtils.getFontSize(context)
        themeFontColor = try {
            Color.parseColor(SpUtils.getFontColor(context))
        } catch (e: Exception) {
            Color.WHITE
        }
    }

    override fun getCount(): Int = musicList.size
    override fun getItem(position: Int): Any = musicList[position]
    override fun getItemId(position: Int): Long = position.toLong()

    @SuppressLint("SetTextI18n")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var view = convertView
        val holder: ViewHolder
        if (view == null) {
            view = LayoutInflater.from(context).inflate(R.layout.item_music, parent, false)
            holder = ViewHolder(
                tvIndex = view.findViewById(R.id.tv_index),
                tvMusicName = view.findViewById(R.id.tv_music_name),
                tvAuthor = view.findViewById(R.id.tv_author),
                btnAddToPlaylist = view.findViewById(R.id.btn_add_to_playlist),
                btnDelete = view.findViewById(R.id.btn_delete),
                btnMove = view.findViewById(R.id.btn_move)
            )
            view.tag = holder
            (view as ViewGroup).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

            ViewUtils.applyScaleToItemView(view, positionProvider = {
                (view.parent as? android.widget.ListView)?.getPositionForView(view) ?: -1
            })
            ViewUtils.applyScaleToButton(holder.btnAddToPlaylist)
            ViewUtils.applyScaleToButton(holder.btnDelete)
            ViewUtils.applyScaleToButton(holder.btnMove)

            holder.btnDelete.text = "+"
            holder.btnDelete.rotation = 45f
            holder.btnDelete.setTextColor(deleteColor)
            holder.btnMove.setTextColor(Color.WHITE)
        } else {
            holder = view.tag as ViewHolder
        }

        ensureTheme()

        val musicBean = musicList[position]
        holder.tvIndex.text = if (originalIndexProvider != null) {
            (originalIndex(musicBean) + 1).toString()
        } else {
            (position + 1).toString()
        }
        val displayName = DataFileUtils.getDisplayName(musicBean.musicName)
        holder.tvMusicName.text = displayName

        if (musicBean.isBilibili && !musicBean.author.isNullOrEmpty()) {
            holder.tvAuthor.visibility = View.VISIBLE
            holder.tvAuthor.text = musicBean.author
        } else {
            holder.tvAuthor.visibility = View.GONE
        }

        holder.tvMusicName.setTextColor(if (musicBean.isPlaying) playingColor else themeFontColor)
        holder.tvIndex.setTextColor(themeFontColor)
        holder.tvAuthor.setTextColor(authorColor)

        holder.tvMusicName.textSize = themeFontSize.toFloat()
        holder.tvIndex.textSize = (themeFontSize - 1).coerceAtLeast(1).toFloat()
        holder.tvAuthor.textSize = (themeFontSize - 3).coerceAtLeast(1).toFloat()

        holder.btnAddToPlaylist.visibility =
            if (showAddButton && onAddToPlaylistClickListener != null) View.VISIBLE else View.GONE

        if (showDeleteButton && onDeleteClickListener != null) {
            holder.btnDelete.visibility = View.VISIBLE
            holder.btnDelete.setOnClickListener {
                onDeleteClickListener?.onDeleteClick(position, musicBean)
            }
        } else {
            holder.btnDelete.visibility = View.GONE
            holder.btnDelete.setOnClickListener(null)
        }

        if (showMoveButton && onMoveClickListener != null) {
            holder.btnMove.visibility = View.VISIBLE
            holder.btnMove.setOnClickListener {
                onMoveClickListener?.onMoveClick(position, musicBean)
            }
        } else {
            holder.btnMove.visibility = View.GONE
            holder.btnMove.setOnClickListener(null)
        }

        holder.btnAddToPlaylist.setOnClickListener {
            onAddToPlaylistClickListener?.onAddToPlaylistClick(position, musicBean)
        }

        return view
    }

    data class ViewHolder(
        val tvIndex: TextView,
        val tvMusicName: TextView,
        val tvAuthor: TextView,
        val btnAddToPlaylist: ImageButton,
        val btnDelete: TextView,
        val btnMove: TextView
    )

    interface OnMoveClickListener {
        fun onMoveClick(position: Int, musicBean: MusicBean)
    }

    interface OnItemLongClickListener {
        fun onItemLongClick(position: Int, musicBean: MusicBean)
    }

    interface OnAddToPlaylistClickListener {
        fun onAddToPlaylistClick(position: Int, musicBean: MusicBean)
    }

    interface OnDeleteClickListener {
        fun onDeleteClick(position: Int, musicBean: MusicBean)
    }
}
