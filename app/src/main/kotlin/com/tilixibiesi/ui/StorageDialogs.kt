package com.tilixibiesi.ui

import com.tilixibiesi.R
import com.tilixibiesi.bili.BiliCoverLoader
import com.tilixibiesi.bili.BiliLyricStore
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.StoragePaths
import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.util.CacheManager
import com.tilixibiesi.util.FontUtils
import com.tilixibiesi.util.ToastUtils

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView

class StorageDialogs(
    private val context: Context,
    private val inflater: LayoutInflater,
    private val fontColor: Int,
    private val showDialog: (AlertDialog.Builder) -> AlertDialog
) {

    fun showCacheManager() {
        val files = CacheManager.listAudioFiles().toMutableList()
        if (files.isEmpty()) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_dir_empty))
            return
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = files.size
            override fun getItem(pos: Int) = files[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
                val file = files[pos]
                view.findViewById<TextView>(android.R.id.text1).apply {
                    val name = DataFileUtils.getDisplayName(CacheManager.nameOf(file))
                    text = if (CacheManager.isBiliAudioFile(file)) {
                        LanguageUtils.getString(context, R.string.saved_music_bili_format, name)
                    } else {
                        name
                    }
                    setTextColor(fontColor)
                }
                view.setBackgroundColor(Color.TRANSPARENT)
                return view
            }
        }
        val listView = ListView(context).apply {
            this.adapter = adapter
            setBackgroundColor(Color.TRANSPARENT)
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
        }

        listView.setOnItemClickListener { _, _, pos, _ ->
            val file = files[pos]
            if (!file.exists()) {
                ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_file_invalid))
                return@setOnItemClickListener
            }
            val actualName = CacheManager.nameOf(file)
            val intent = android.content.Intent(context, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_PLAY_FILE
                putExtra(MusicPlayerService.EXTRA_FILE_PATH, file.absolutePath)
                putExtra(MusicPlayerService.EXTRA_DISPLAY_NAME, actualName)
            }
            context.startForegroundService(intent)
            ToastUtils.show(
                context,
                LanguageUtils.getString(
                    context,
                    R.string.toast_playing_format,
                    DataFileUtils.getDisplayName(actualName)
                )
            )
        }

        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val file = files[pos]
            val actualName = CacheManager.nameOf(file)
            val displayName = DataFileUtils.getDisplayName(actualName)
            showDialog(
                AlertDialog.Builder(context)
                    .setTitle(LanguageUtils.getString(context, R.string.dialog_title_delete_music))
                    .setMessage(
                        LanguageUtils.getString(context, R.string.dialog_msg_delete_music_format, displayName)
                    )
                    .setPositiveButton(LanguageUtils.getString(context, R.string.btn_delete)) { _, _ ->
                        file.delete()
                        BiliLyricStore.delete(actualName)
                        BiliCoverLoader.localCoverFile(actualName).delete()
                        files.removeAt(pos)
                        adapter.notifyDataSetChanged()
                        if (files.isEmpty()) {
                            ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_dir_empty))
                        }
                    }
                    .setNegativeButton(LanguageUtils.getString(context, R.string.btn_cancel), null)
            )
            true
        }

        showDialog(
            AlertDialog.Builder(context)
                .setTitle(LanguageUtils.getString(context, R.string.dialog_title_saved_music))
                .setView(listView)
                .setPositiveButton(LanguageUtils.getString(context, R.string.btn_close), null)
                .setNegativeButton(LanguageUtils.getString(context, R.string.btn_clear_all_music)) { _, _ ->
                    showDialog(
                        AlertDialog.Builder(context)
                            .setTitle(LanguageUtils.getString(context, R.string.dialog_title_clear_all_music))
                            .setMessage(LanguageUtils.getString(context, R.string.dialog_msg_clear_all_music))
                            .setPositiveButton(LanguageUtils.getString(context, R.string.btn_ok)) { _, _ ->
                                listOf(
                                    StoragePaths.resolveWrite(CACHE_DIR_REL),
                                    StoragePaths.resolveWrite(BILI_CACHE_DIR_REL),
                                    StoragePaths.resolveWrite(COVER_DIR_REL),
                                    StoragePaths.resolveWrite(LYRIC_DIR_REL)
                                ).forEach { dir ->
                                    dir.listFiles()?.forEach { if (it.isFile) it.delete() }
                                }
                                files.clear()
                                adapter.notifyDataSetChanged()
                                ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_dir_empty))
                            }
                            .setNegativeButton(LanguageUtils.getString(context, R.string.btn_cancel), null)
                    )
                }
        )
    }

    fun showBlockedWordsManager() {
        val dialogView = inflater.inflate(R.layout.dialog_blocked_words, null)
        val etInput = dialogView.findViewById<EditText>(R.id.et_block_word)
        val lvWords = dialogView.findViewById<ListView>(R.id.lv_blocked_words)

        etInput.setTextColor(fontColor)
        etInput.setHintTextColor(FontUtils.dimmed(context))
        dialogView.findViewById<TextView>(R.id.tv_blocked_words_title)?.setTextColor(fontColor)
        dialogView.findViewById<TextView>(R.id.tv_blocked_words_current)?.setTextColor(fontColor)

        val blockedWords = DataFileUtils.loadBlockedWords().toMutableList()
        val adapter = object : BaseAdapter() {
            override fun getCount() = blockedWords.size
            override fun getItem(pos: Int) = blockedWords[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
                view.findViewById<TextView>(android.R.id.text1).apply {
                    text = blockedWords[pos]
                    setTextColor(fontColor)
                }
                view.setBackgroundColor(Color.TRANSPARENT)
                return view
            }
        }
        lvWords.adapter = adapter
        lvWords.setBackgroundColor(Color.TRANSPARENT)
        lvWords.divider = null
        lvWords.dividerHeight = 0
        lvWords.selector = ColorDrawable(Color.TRANSPARENT)

        lvWords.setOnItemLongClickListener { _, _, pos, _ ->
            showDialog(
                AlertDialog.Builder(context)
                    .setTitle(LanguageUtils.getString(context, R.string.dialog_title_delete_word))
                    .setMessage(
                        LanguageUtils.getString(
                            context, R.string.dialog_msg_delete_word_format, blockedWords[pos]
                        )
                    )
                    .setPositiveButton(LanguageUtils.getString(context, R.string.btn_delete)) { _, _ ->
                        DataFileUtils.removeBlockedWord(blockedWords[pos])
                        blockedWords.removeAt(pos)
                        adapter.notifyDataSetChanged()
                    }
                    .setNegativeButton(LanguageUtils.getString(context, R.string.btn_cancel), null)
            )
            true
        }

        dialogView.findViewById<Button>(R.id.btn_add_word).setOnClickListener {
            val word = etInput.text.toString().trim()
            when {
                word.isEmpty() ->
                    ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_empty_input))
                blockedWords.contains(word) ->
                    ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_word_exists))
                else -> {
                    DataFileUtils.addBlockedWord(word)
                    blockedWords.add(word)
                    adapter.notifyDataSetChanged()
                    etInput.text.clear()
                    ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_word_added))
                }
            }
        }

        showDialog(
            AlertDialog.Builder(context)
                .setTitle(LanguageUtils.getString(context, R.string.dialog_title_blocked_words))
                .setView(dialogView)
                .setPositiveButton(LanguageUtils.getString(context, R.string.btn_complete), null)
        )
    }

    fun showFootprintDialog(title: String, content: String, onClear: () -> Unit) {
        val scrollView = android.widget.ScrollView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(16, 16, 16, 16)
        }
        scrollView.addView(TextView(context).apply {
            text = content
            textSize = 14f
            setTextColor(fontColor)
            setBackgroundColor(Color.TRANSPARENT)
        })
        showDialog(
            AlertDialog.Builder(context)
                .setTitle(title)
                .setView(scrollView)
                .setPositiveButton(LanguageUtils.getString(context, R.string.btn_close), null)
                .setNegativeButton(LanguageUtils.getString(context, R.string.btn_clear_all)) { _, _ ->
                    showDialog(
                        AlertDialog.Builder(context)
                            .setTitle(LanguageUtils.getString(context, R.string.dialog_title_confirm_clear))
                            .setMessage(LanguageUtils.getString(context, R.string.dialog_msg_confirm_clear_records))
                            .setPositiveButton(LanguageUtils.getString(context, R.string.btn_ok)) { _, _ -> onClear() }
                            .setNegativeButton(LanguageUtils.getString(context, R.string.btn_cancel), null)
                    )
                }
        )
    }

    private companion object {
        const val CACHE_DIR_REL = "system/axeron/long/Android/cache"
        const val BILI_CACHE_DIR_REL = "system/axeron/long/Android/cache/bilibili"
        const val COVER_DIR_REL = "system/axeron/long/Android/cache/Videocover"
        const val LYRIC_DIR_REL = "system/axeron/long/Android/cache/Lyric"
    }
}
