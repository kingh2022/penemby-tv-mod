package com.xxxx.emby_tv.ui.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.xxxx.emby_tv.data.model.MediaStreamDto

/**
 * 播放器轨道管理器
 * 
 * 负责处理字幕和音频轨道的选择逻辑
 */
object PlayerTrackManager {
    private const val TAG = "PlayerTrackManager"

    /**
     * 选择字幕轨道
     * 
     * @param player ExoPlayer 实例
     * @param subtitleTracks Emby 返回的字幕轨道列表
     * @param selectedIndex 要选择的字幕 index
     * @param currentTracks 当前轨道信息
     */
    fun selectSubtitle(
        player: ExoPlayer,
        subtitleTracks: List<MediaStreamDto>,
        selectedIndex: Int,
        currentTracks: Tracks?
    ) {
        if (selectedIndex == -1) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            Log.d(TAG, "Subtitle disabled")
            return
        }

        val targetTrack = subtitleTracks.find { it.index == selectedIndex }
            ?: run {
                Log.w(TAG, "Target subtitle index $selectedIndex not found in Emby metadata")
                return
            }

        val targetIndex = targetTrack.index?.toString().orEmpty()
        val targetLabel = targetTrack.displayTitle?.trim().orEmpty()
        val targetLanguage = targetTrack.language?.trim()?.lowercase().orEmpty()
        val targetCodec = targetTrack.codec?.trim()?.lowercase().orEmpty()

        val groups = currentTracks?.groups ?: player.currentTracks.groups
        val trackGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (trackGroups.isEmpty()) {
            Log.w(TAG, "No Media3 text track groups available yet")
            return
        }

        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)

        data class Candidate(
            val group: Tracks.Group,
            val index: Int,
            val score: Int,
            val reason: String
        )

        fun normalize(value: String?): String =
            value.orEmpty().trim().lowercase()
                .replace("_", "-")
                .replace(Regex("\\s+"), " ")

        fun codecMatches(format: androidx.media3.common.Format): Boolean {
            val mime = format.sampleMimeType?.lowercase().orEmpty()
            return when {
                targetCodec.contains("ass") || targetCodec.contains("ssa") ->
                    mime == MimeTypes.TEXT_SSA
                targetCodec.contains("vtt") || targetCodec.contains("webvtt") ->
                    mime == MimeTypes.TEXT_VTT
                targetCodec.contains("srt") || targetCodec.contains("subrip") ->
                    mime == MimeTypes.APPLICATION_SUBRIP
                targetCodec.contains("pgs") || targetCodec.contains("hdmv_pgs") ->
                    mime == MimeTypes.APPLICATION_PGS
                targetCodec.contains("dvb") || targetCodec.contains("dvbsub") ->
                    mime == MimeTypes.APPLICATION_DVBSUBS
                else -> false
            }
        }

        val candidates = mutableListOf<Candidate>()
        for (group in trackGroups) {
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val id = format.id?.toString().orEmpty()
                val label = format.label?.trim().orEmpty()
                val language = format.language?.trim()?.lowercase().orEmpty()
                var score = 0
                val reasons = mutableListOf<String>()

                if (id == targetIndex || id.endsWith(":$targetIndex") || id.endsWith("/$targetIndex")) {
                    score += 1000
                    reasons += "id"
                }
                if (label == "$targetLabel [$targetIndex]") {
                    score += 900
                    reasons += "unique-label"
                }
                if (targetLabel.isNotEmpty() && normalize(label) == normalize(targetLabel)) {
                    score += 300
                    reasons += "label"
                }
                if (targetLanguage.isNotEmpty() && language == targetLanguage) {
                    score += 200
                    reasons += "language"
                }
                if (codecMatches(format)) {
                    score += 100
                    reasons += "codec"
                }
                if (format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 &&
                    targetTrack.isDefault == true
                ) {
                    score += 40
                    reasons += "default"
                }
                if (targetTrack.isForced == true &&
                    format.selectionFlags and C.SELECTION_FLAG_FORCED != 0
                ) {
                    score += 40
                    reasons += "forced"
                }

                if (score > 0) {
                    candidates += Candidate(group, i, score, reasons.joinToString("+"))
                }
            }
        }

        // 关键修复：绝不再把 Emby subtitle index 直接当成 Media3 全部字幕轨道的 ordinal。
        // 一个容器里可能同时存在内嵌字幕、外置字幕和多个 TrackGroup，这种顺序并不稳定。
        val best = candidates.maxByOrNull { it.score }

        if (best != null) {
            builder.addOverride(
                TrackSelectionOverride(best.group.mediaTrackGroup, best.index)
            )
            player.trackSelectionParameters = builder.build()

            val format = best.group.getTrackFormat(best.index)
            Log.d(
                TAG,
                "Subtitle selected: EmbyIndex=$targetIndex, label=${format.label}, " +
                    "id=${format.id}, language=${format.language}, mime=${format.sampleMimeType}, " +
                    "matchedBy=${best.reason}, score=${best.score}"
            )
        } else {
            Log.w(
                TAG,
                "Unable to match subtitle: EmbyIndex=$targetIndex, " +
                    "Label=$targetLabel, Language=$targetLanguage, Codec=$targetCodec"
            )
            for (group in trackGroups) {
                for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    Log.d(
                        TAG,
                        "Available subtitle: group=${group.mediaTrackGroup.id}, track=$i, " +
                            "id=${f.id}, label=${f.label}, language=${f.language}, mime=${f.sampleMimeType}"
                    )
                }
            }
        }
    }

    /**
     * 选择音频轨道
     * 
     * @param player ExoPlayer 实例
     * @param audioTracks Emby 返回的音频轨道列表
     * @param selectedIndex 要选择的音频 index
     * @param currentTracks 当前轨道信息
     */
    fun selectAudio(
        player: ExoPlayer,
        audioTracks: List<MediaStreamDto>,
        selectedIndex: Int,
        currentTracks: Tracks?
    ) {
        if (audioTracks.isEmpty()) return
        if (selectedIndex <= -1) return

        val targetTrack = audioTracks.find { it.index == selectedIndex }
            ?: run {
                Log.w(TAG, "Target audio index $selectedIndex not found in metadata")
                return
            }

        val targetOrdinalIndex = audioTracks.indexOf(targetTrack)
        val targetLabel = targetTrack.displayTitle
        val targetIndex = targetTrack.index?.toString() ?: ""

        Log.d(TAG, "Audio Selection: Target [EmbyIndex:$targetIndex, Label:$targetLabel, OrdinalIndex:$targetOrdinalIndex]")

        var parametersBuilder = player.trackSelectionParameters.buildUpon()

        val groups = currentTracks?.groups ?: player.currentTracks.groups
        val trackGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        var isMatched = false

        // 打印可用音频轨道
        // Log.d(TAG, "--- Available Audio Tracks ---")
        var totalTracks = 0
        trackGroups.forEachIndexed { gi, group ->
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                // Log.d(TAG, "Group[$gi] Track[$i]: ID=${f.id}, Label=${f.label}, Lang=${f.language}")
                totalTracks++
            }
        }
        Log.d(TAG, "Total audio tracks: $totalTracks")

        // 使用顺序匹配：音频列表的第一个对应轨道顺序0
        if (targetOrdinalIndex >= 0) {
            var trackCounter = 0
            outerOrdinal@ for (group in trackGroups) {
                for (i in 0 until group.length) {
                    if (trackCounter == targetOrdinalIndex) {
                        parametersBuilder.setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, i)
                        )
                        isMatched = true
                        val f = group.getTrackFormat(i)
//                        Log.d(TAG, "Matched audio by Ordinal: OrdinalIndex=$targetOrdinalIndex -> Track (ID:${f.id}, Label:${f.label})")
                        break@outerOrdinal
                    }
                    trackCounter++
                }
            }
        }

        if (isMatched) {
            player.trackSelectionParameters = parametersBuilder.build()
        } else {
            Log.w(TAG, "Unable to match audio: OrdinalIndex=$targetOrdinalIndex, Label=$targetLabel")
        }
    }
}
