package com.tilixibiesi.bili

import com.tilixibiesi.R

/**
 * B 站 DASH 音频音质。
 *
 * [id] 与 playurl 接口返回的 `dash.audio[].id` 一致。
 *
 * 注意：B 站的音频 id 并不与音质高低单调对应——
 * 杜比全景声(30250) 与 Hi-Res(30251) 的 id 都小于 192K(30280)。
 * 因此排序一律以 [rank] 为准，绝不能用 id 大小比较音质。
 *
 * 枚举声明顺序即音质从低到高，设置页列表直接沿用该顺序。
 */
enum class BiliAudioQuality(
    val id: Int,
    val rank: Int,
    val labelRes: Int
) {
    Q64(30216, 0, R.string.audio_quality_64k),
    Q132(30232, 1, R.string.audio_quality_132k),
    Q192(30280, 2, R.string.audio_quality_192k),
    DOLBY(30250, 3, R.string.audio_quality_dolby),
    HIRES(30251, 4, R.string.audio_quality_hires);

    companion object {

        /** 默认音质：仍为最低音质。 */
        val DEFAULT: BiliAudioQuality = Q64

        fun fromId(id: Int): BiliAudioQuality? = entries.firstOrNull { it.id == id }

        /** 读取设置值；未设置（0）或值非法时回退到默认的最低音质。 */
        fun fromSetting(rawId: Int): BiliAudioQuality = fromId(rawId) ?: DEFAULT

        private fun rankOf(id: Int): Int = fromId(id)?.rank ?: Int.MAX_VALUE

        /**
         * 按音质回退顺序排列可用音质。
         *
         * 规则：所选音质优先；所选音质不可用时，先退到更高的音质（由低到高，
         * 即先用最接近的更高品质），再退到更低的音质（由高到低）作为兜底。
         */
        fun fallbackOrder(availableIds: List<Int>, preferredId: Int): List<Int> {
            val preferredRank = fromId(preferredId)?.rank ?: DEFAULT.rank
            val distinct = availableIds.distinct()
            if (distinct.isEmpty()) return emptyList()

            val others = distinct.filter { it != preferredId }
            val higher = others.filter { rankOf(it) > preferredRank }.sortedBy { rankOf(it) }
            val lower = others.filter { rankOf(it) <= preferredRank }.sortedByDescending { rankOf(it) }
            val head = if (distinct.contains(preferredId)) listOf(preferredId) else emptyList()
            return head + higher + lower
        }
    }
}
