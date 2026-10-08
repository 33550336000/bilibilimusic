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
    var showDeleteButton: Boolean = false

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
        } else {
            holder = view.tag as ViewHolder
        }

        val musicBean = musicList[position]
        holder.tvIndex.text = if (originalIndexProvider != null) {
            (originalIndexProvider!!(musicBean) + 1).toString()
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

        val fontColor = SpUtils.getFontColor(context)
        val fontSize = SpUtils.getFontSize(context)
        try {
            val color = Color.parseColor(fontColor)
            holder.tvMusicName.setTextColor(
                if (musicBean.isPlaying) Color.HSVToColor(floatArrayOf(210f, 1f, 1f)) else color
            )
            holder.tvIndex.setTextColor(color)
            holder.tvAuthor.setTextColor(Color.parseColor("#AAAAAA"))
        } catch (e: Exception) {
            holder.tvMusicName.setTextColor(
                if (musicBean.isPlaying) Color.HSVToColor(floatArrayOf(210f, 1f, 1f))
                else 0xFFFFFFFF.toInt()
            )
            holder.tvIndex.setTextColor(0xFF888888.toInt())
            holder.tvAuthor.setTextColor(0xFF888888.toInt())
        }

        holder.tvMusicName.textSize = fontSize.toFloat()
        holder.tvIndex.textSize = (fontSize - 1).toFloat()
        holder.tvAuthor.textSize = (fontSize - 3).toFloat()

        holder.btnAddToPlaylist.visibility =
            if (onAddToPlaylistClickListener != null) View.VISIBLE else View.GONE

        if (showDeleteButton && onDeleteClickListener != null) {
            holder.btnDelete.visibility = View.VISIBLE
            holder.btnDelete.text = "+"
            holder.btnDelete.rotation = 45f
            holder.btnDelete.setTextColor(Color.parseColor("#FF0000"))
            holder.btnDelete.setOnClickListener {
                onDeleteClickListener?.onDeleteClick(position, musicBean)
            }
        } else {
            holder.btnDelete.visibility = View.GONE
        }

        if (showMoveButton && onMoveClickListener != null) {
            holder.btnMove.visibility = View.VISIBLE
            holder.btnMove.setTextColor(Color.WHITE)
            holder.btnMove.setOnClickListener {
                onMoveClickListener?.onMoveClick(position, musicBean)
            }
        } else {
            holder.btnMove.visibility = View.GONE
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
