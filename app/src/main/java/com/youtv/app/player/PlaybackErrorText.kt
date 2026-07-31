package com.youtv.app.player

import androidx.media3.common.PlaybackException

internal object PlaybackErrorText {
    fun from(errorCode: Int): String = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT -> "网络连接超时"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "播放地址返回错误"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "播放地址不存在"
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "播放地址内容无效"
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "系统禁止访问此地址"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_PERMISSION_DENIED -> "没有权限访问播放地址"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "视频数据格式异常"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "暂不支持此视频格式"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> "设备无法解码该视频"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED -> "音频输出失败"
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "直播进度已失效"
        in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> "视频授权验证失败"
        else -> "播放源暂时不可用"
    }
}
