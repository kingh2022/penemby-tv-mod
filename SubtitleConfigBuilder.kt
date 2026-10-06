package com.xxxx.emby_tv.ui.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.xxxx.emby_tv.data.model.MediaStreamDto

/**
 * 字幕配置构建器
 * 
 * 负责构建 ExoPlayer 的字幕配置
 */
object SubtitleConfigBuilder {
    private const val TAG = "SubtitleConfigBuilder"

    /**
     * 构建字幕配置列表
     * 
     * @param subtitleTracks Emby 返回的字幕轨道列表
     * @param serverUrl 服务器地址
     * @param mediaId 媒体ID
     * @param mediaSourceId 媒体源ID
     * @param apiKey API密钥
     * @param selectedSubtitleIndex 当前选中的字幕索引
     * @return 字幕配置列表
     */
    fun buildSubtitleConfigs(
        subtitleTracks: List<MediaStreamDto>,
        serverUrl: String,
        mediaId: String,
        mediaSourceId: String,
        apiKey: String,
        selectedSubtitleIndex: Int
    ): List<MediaItem.SubtitleConfiguration> {
        val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()

//        Log.d(TAG, "=== Building Subtitle Configurations ===")
//        Log.d(TAG, "Total subtitleTracks: ${subtitleTracks.size}")

        subtitleTracks.forEach { track ->
            val index = track.index ?: 0
            val codec = track.codec?.lowercase() ?: "srt"
            val label = track.displayTitle ?: "Subtitle"
            val lang = track.language ?: "und"

            // 判断是否可以通过URL加载（外置或支持外部流）
            val isLoadableSubtitle = track.isExternal == true || track.supportsExternalStream == true

//            Log.d(TAG, "Track[$index]: Codec=$codec, IsExternal=${track.isExternal}, SupportsExternal=${track.supportsExternalStream}, Loadable=$isLoadableSubtitle")

            if (isLoadableSubtitle) {
                // 不再把所有未知字幕都强制请求为 SRT。
                // SRT/VTT/ASS 等文本字幕可以由 Emby 转成兼容格式；PGS/DVB 等图像字幕
                // 则必须保留对应格式，否则 Media3 根本拿不到可渲染的 Bitmap Cue。
                val requestFormat = when {
                    codec.contains("ass") || codec.contains("ssa") -> "srt"
                    codec.contains("subrip") || codec.contains("srt") -> "srt"
                    codec.contains("vtt") || codec.contains("webvtt") -> "vtt"
                    codec.contains("pgs") || codec.contains("hdmv_pgs") -> "pgssub"
                    codec.contains("dvb") || codec.contains("dvbsub") -> "dvbsub"
                    else -> "srt"
                }

                val subUrl = "${serverUrl}/emby/Videos/$mediaId/$mediaSourceId/Subtitles/$index/Stream.$requestFormat?api_key=$apiKey"

                val mimeType = when {
                    requestFormat == "vtt" -> MimeTypes.TEXT_VTT
                    requestFormat == "pgssub" -> MimeTypes.APPLICATION_PGS
                    requestFormat == "dvbsub" -> MimeTypes.APPLICATION_DVBSUBS
                    else -> MimeTypes.APPLICATION_SUBRIP
                }

                // 使用 "Label [Index]" 格式作为唯一标识，防止 ExoPlayer 自动去重
                val uniqueLabel = "$label [$index]"

                val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                    .setId(index.toString())
                    .setMimeType(mimeType)
                    .setLanguage(lang)
                    .setLabel(uniqueLabel)
                    .setSelectionFlags(if (index == selectedSubtitleIndex) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()

                subtitleConfigs.add(config)
//                Log.d(TAG, "Added subtitle config: Index=$index, URL=$subUrl, UniqueLabel=$uniqueLabel")
            }
        }

//        Log.d(TAG, "Total subtitle configs added: ${subtitleConfigs.size}")
        return subtitleConfigs
    }

    /**
     * 获取字幕的 MIME 类型
     */
    fun getMimeType(codec: String): String {
        return when {
            codec.contains("ass") || codec.contains("ssa") -> MimeTypes.TEXT_SSA
            codec.contains("vtt") || codec.contains("webvtt") -> MimeTypes.TEXT_VTT
            codec.contains("srt") || codec.contains("subrip") -> MimeTypes.APPLICATION_SUBRIP
            codec.contains("pgs") || codec.contains("hdmv_pgs") -> MimeTypes.APPLICATION_PGS
            codec.contains("dvb") || codec.contains("dvbsub") -> MimeTypes.APPLICATION_DVBSUBS
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}
