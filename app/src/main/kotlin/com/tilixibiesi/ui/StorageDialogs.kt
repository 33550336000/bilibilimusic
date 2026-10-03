package com.tilixibiesi.ui

import com.tilixibiesi.R
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.StoragePaths
import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.util.CacheManager
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
import java.io.File

/**
 * 「数据与工具」卡片里的三类文件对话框：
 *  - 已缓存歌曲（[showCacheManager]）：试听 / 长按删除 / 清空
 *  - 屏蔽字管理（[showBlockedWordsManager]）：增删屏蔽词
 *  - 足迹查看（[showFootprintDialog]）：只读展示 + 清空
 *
 * 为什么独立成类：这些弹窗都在操作磁盘数据、与设置页的控件状态无关，
 * 原先让 `SettingsPage` 又长了 180 行。这里只依赖 Context / inflater 与
 * 页面传入的弹窗构建器，便于单独阅读。
 *
 * @param context 页面的 ContextWrapper 即可
 * @param inflater 用于加载对话框布局（与页面一致，已装本地化工厂）
 * @param fontColor 列表项文字颜色（跟随全局字体色设置）
 * @param showDialog 页面提供的统一弹窗构建器
 */
class StorageDialogs(
    private val context: Context,
    private val inflater: LayoutInflater,
    private val fontColor: Int,
    private val showDialog: (AlertDialog.Builder) -> AlertDialog
) {

    /** 已缓存歌曲列表：点击试听、长按删除、底部可一键清空 */
    fun showCacheManager() {
        val cacheDir = StoragePaths.resolveRead(CACHE_DIR_REL)
        val cacheManager = CacheManager(cacheDir.absolutePath)
        val map = cacheManager.loadCacheMap()
        if (map.isEmpty()) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_dir_empty))
            return
        }
        val musicNames = map.keys.toMutableList()
        val adapter = object : BaseAdapter() {
            override fun getCount() = musicNames.size
            override fun getItem(pos: Int) = musicNames[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
                view.findViewById<TextView>(android.R.id.text1).apply {
                    text = DataFileUtils.getDisplayName(musicNames[pos])
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
            val musicName = musicNames[pos]
            val md5 = map[musicName] ?: return@setOnItemClickListener
            val filePath = File(cacheDir, md5).absolutePath
            if (!File(filePath).exists()) {
                ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_file_invalid))
                return@setOnItemClickListener
            }
            val intent = android.content.Intent(context, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_PLAY_FILE
                putExtra(MusicPlayerService.EXTRA_FILE_PATH, filePath)
                putExtra(MusicPlayerService.EXTRA_DISPLAY_NAME, DataFileUtils.getDisplayName(musicName))
            }
            context.startForegroundService(intent)
            ToastUtils.show(
                context,
                LanguageUtils.getString(
                    context, R.string.toast_playing_format, DataFileUtils.getDisplayName(musicName)
                )
            )
        }

        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val name = musicNames[pos]
            showDialog(
                AlertDialog.Builder(context)
                    .setTitle(LanguageUtils.getString(context, R.string.dialog_title_delete_music))
                    .setMessage(
                        LanguageUtils.getString(
                            context, R.string.dialog_msg_delete_music_format, DataFileUtils.getDisplayName(name)
                        )
                    )
                    .setPositiveButton(LanguageUtils.getString(context, R.string.btn_delete)) { _, _ ->
                        val md5 = map[name] ?: return@setPositiveButton
                        File(cacheDir, md5).delete()
                        map.remove(name)
                        cacheManager.saveCacheMap(map)
                        musicNames.remove(name)
                        adapter.notifyDataSetChanged()
                        if (map.isEmpty()) {
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
                                cacheDir.listFiles()?.forEach { it.delete() }
                                cacheManager.saveCacheMap(emptyMap())
                                ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_dir_empty))
                            }
                            .setNegativeButton(LanguageUtils.getString(context, R.string.btn_cancel), null)
                    )
                }
        )
    }

    /** 屏蔽字管理：输入新增、长按删除 */
    fun showBlockedWordsManager() {
        val dialogView = inflater.inflate(R.layout.dialog_blocked_words, null)
        val etInput = dialogView.findViewById<EditText>(R.id.et_block_word)
        val lvWords = dialogView.findViewById<ListView>(R.id.lv_blocked_words)

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

    /** 足迹内容只读展示，可通过「清空」按钮触发 [onClear] */
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
    }
}
