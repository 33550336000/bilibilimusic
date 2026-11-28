package com.tilixibiesi.bili

import com.tilixibiesi.R

enum class BiliAudioQuality(
    val id: Int,
    val rank: Int,
    val labelRes: Int
) {
    Q64(30216, 0, R.string.audio_quality_64k),
    Q132(30232, 1, R.string.audio_quality_132k),
    Q192(30280, 2, R.string.audio_quality_192k);

    companion object {

        val DEFAULT: BiliAudioQuality = Q192

        fun fromId(id: Int): BiliAudioQuality? = entries.firstOrNull { it.id == id }

        fun fromSetting(rawId: Int): BiliAudioQuality = fromId(rawId) ?: DEFAULT

        private fun rankOf(id: Int): Int = fromId(id)?.rank ?: Int.MAX_VALUE

        fun fallbackOrder(availableIds: List<Int>, preferredId: Int): List<Int> {
            val preferredRank = fromId(preferredId)?.rank ?: DEFAULT.rank
            val distinct = availableIds.distinct()
            if (distinct.isEmpty()) return emptyList()

            val ordered = distinct.sortedBy { rankOf(it) }
            val lower = ordered.filter { rankOf(it) <= preferredRank }.reversed()
            val higher = ordered.filter { rankOf(it) > preferredRank }
            return lower + higher
        }
    }
}
