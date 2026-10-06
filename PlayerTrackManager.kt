package com.xxxx.emby_tv.ui.player

import android.util.Log
import androidx.media3.common.C
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
        // 关闭字幕
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
        val targetOrdinalIndex = subtitleTracks.indexOf(targetTrack)

        val groups = currentTracks?.groups ?: player.currentTracks.groups
        val trackGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }

        if (trackGroups.isEmpty()) {
            Log.w(TAG, "No Media3 text track groups available yet")
            return
        }

        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)

        var matchedGroup: Tracks.Group? = null
        var matchedIndex = -1
        var matchedBy = ""

        fun tryMatch(predicate: (androidx.media3.common.Format) -> Boolean, reason: String): Boolean {
            for (group in trackGroups) {
                for (i in 0 until group.length) {
                    if (predicate(group.getTrackFormat(i))) {
                        matchedGroup = group
                        matchedIndex = i
                        matchedBy = reason
                        return true
                    }
                }
            }
            return false
        }

        // 1. 外置字幕配置：SubtitleConfigBuilder 使用 Emby index 作为 Media3 Format.id。
        // 2. 内嵌字幕：不同容器/解码器生成的 id 格式可能是 "3"、"text:3" 等。
        val matched = tryMatch(
            predicate = { format ->
                val id = format.id?.toString().orEmpty()
                id == targetIndex ||
                    id.endsWith(":$targetIndex") ||
                    id.endsWith("/$targetIndex")
            },
            reason = "id"
        ) ||
            tryMatch(
                predicate = { format ->
                    val label = format.label?.trim().orEmpty()
                    label == "$targetLabel [$targetIndex]"
                },
                reason = "unique-label"
            ) ||
            tryMatch(
                predicate = { format ->
                    val label = format.label?.trim().orEmpty()
                    val language = format.language?.trim()?.lowercase().orEmpty()
                    targetLabel.isNotEmpty() &&
                        label == targetLabel &&
                        (targetLanguage.isEmpty() || language == targetLanguage)
                },
                reason = "label+language"
            ) ||
            tryMatch(
                predicate = { format ->
                    targetLanguage.isNotEmpty() &&
                        format.language?.trim()?.lowercase() == targetLanguage
                },
                reason = "language"
            )

        // 最后才使用顺序匹配。这里不再要求 isExternal/supportsExternalStream，
        // 因为内嵌字幕同样需要通过 TrackSelectionOverride 切换。
        if (!matched && targetOrdinalIndex >= 0) {
            var counter = 0
            outer@ for (group in trackGroups) {
                for (i in 0 until group.length) {
                    if (counter == targetOrdinalIndex) {
                        matchedGroup = group
                        matchedIndex = i
                        matchedBy = "ordinal"
                        break@outer
                    }
                    counter++
                }
            }
        }

        if (matchedGroup != null && matchedIndex >= 0) {
            builder.addOverride(
                TrackSelectionOverride(matchedGroup!!.mediaTrackGroup, matchedIndex)
            )
            player.trackSelectionParameters = builder.build()

            val format = matchedGroup!!.getTrackFormat(matchedIndex)
            Log.d(
                TAG,
                "Subtitle selected: EmbyIndex=$targetIndex, " +
                    "label=${format.label}, id=${format.id}, language=${format.language}, " +
                    "matchedBy=$matchedBy"
            )
        } else {
            Log.w(
                TAG,
                "Unable to match subtitle: EmbyIndex=$targetIndex, " +
                    "OrdinalIndex=$targetOrdinalIndex, Label=$targetLabel, Language=$targetLanguage"
            )
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
