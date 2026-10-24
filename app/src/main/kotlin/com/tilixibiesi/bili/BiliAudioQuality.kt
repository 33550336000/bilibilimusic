package com.tilixibiesi.bili

import com.tilixibiesi.R

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

        val DEFAULT: BiliAudioQuality = Q64

        fun fromId(id: Int): BiliAudioQuality? = entries.firstOrNull { it.id == id }

        fun fromSetting(rawId: Int): BiliAudioQuality = fromId(rawId) ?: DEFAULT

        private fun rankOf(id: Int): Int = fromId(id)?.rank ?: Int.MAX_VALUE

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
