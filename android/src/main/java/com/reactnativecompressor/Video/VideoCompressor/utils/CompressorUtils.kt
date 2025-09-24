package com.reactnativecompressor.Video.VideoCompressor.utils

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import com.reactnativecompressor.Video.VideoCompressor.video.Mp4Movie
import java.io.File

object CompressorUtils {

  // Minimum height and width for videos
  private const val MIN_HEIGHT = 640.0
  private const val MIN_WIDTH = 368.0

  // Interval between I-frames (keyframes) in seconds
  private const val I_FRAME_INTERVAL = 1

  /**
   * Get the width of the video from metadata or use a default value if not available.
   */
  fun prepareVideoWidth(
    mediaMetadataRetriever: MediaMetadataRetriever,
  ): Double {
    val widthData =
      mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
    return if (widthData.isNullOrEmpty()) {
      MIN_WIDTH
    } else {
      widthData.toDouble()
    }
  }

  /**
   * Get the height of the video from metadata or use a default value if not available.
   */
  fun prepareVideoHeight(
    mediaMetadataRetriever: MediaMetadataRetriever,
  ): Double {
    val heightData =
      mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
    return if (heightData.isNullOrEmpty()) {
      MIN_HEIGHT
    } else {
      heightData.toDouble()
    }
  }

  /**
   * Set up an Mp4Movie with rotation and cache file.
   */
  fun setUpMP4Movie(
    rotation: Int,
    cacheFile: File,
  ): Mp4Movie {
    val movie = Mp4Movie()
    movie.apply {
      setCacheFile(cacheFile)
      setRotation(rotation)
    }
    return movie
  }

  /**
   * Set output parameters like bitrate and frame rate for video encoding.
   */
  fun setOutputFileParameters(
    inputFormat: MediaFormat,
    outputFormat: MediaFormat,
    codec: MediaCodec,
    newBitrate: Int? = null // optional override
  ) {
    val codecInfo = codec.codecInfo
    val mime = outputFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
    val caps = codecInfo.getCapabilitiesForType(mime)
    val videoCaps = caps.videoCapabilities

    fun safeGetInt(format: MediaFormat, key: String, defaultValue: Int): Int {
      return if (format.containsKey(key)) format.getInteger(key) else defaultValue
    }

    // Pull from outputFormat, fallback to defaults
    val rawWidth = safeGetInt(outputFormat, MediaFormat.KEY_WIDTH, 1280)
    val rawHeight = safeGetInt(outputFormat, MediaFormat.KEY_HEIGHT, 720)

    // Frame rate
    val inputFps = if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
      inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
    } else {
      30
    }
    val rawFps = safeGetInt(outputFormat, MediaFormat.KEY_FRAME_RATE, inputFps)

    // --- Bitrate scaling logic ---
    val inputWidth = safeGetInt(inputFormat, MediaFormat.KEY_WIDTH, rawWidth)
    val inputHeight = safeGetInt(inputFormat, MediaFormat.KEY_HEIGHT, rawHeight)
    val inputBitrate = safeGetInt(inputFormat, MediaFormat.KEY_BIT_RATE, 2_000_000)

    // Scale based on pixel ratio
    val scale = (rawWidth * rawHeight).toDouble() / (inputWidth * inputHeight).toDouble()
    val adjustedBitrate = (inputBitrate * scale).toInt()

    // Final bitrate: caller override > scaled > outputFormat > default
    val rawBitrate = newBitrate
      ?: safeGetInt(outputFormat, MediaFormat.KEY_BIT_RATE, adjustedBitrate)

    // --- Maintain aspect ratio using original video dimensions ---
    val aspectRatio = inputWidth.toDouble() / inputHeight.toDouble()

    // Clamp target size within codec supported ranges
    val clampedWidth = rawWidth.coerceIn(videoCaps.supportedWidths.lower, videoCaps.supportedWidths.upper)
    val clampedHeight = rawHeight.coerceIn(videoCaps.supportedHeights.lower, videoCaps.supportedHeights.upper)

    // Try aligning width first while preserving aspect ratio
    // Align to codec requirements (e.g., multiple of 2, 8, or 16)
    var alignedWidth = clampedWidth - (clampedWidth % videoCaps.widthAlignment)
    var alignedHeight = (alignedWidth / aspectRatio).toInt()
    alignedHeight -= alignedHeight % videoCaps.heightAlignment

    // Fallback: if height is out of supported range, align height instead
    if (alignedHeight < videoCaps.supportedHeights.lower || alignedHeight > videoCaps.supportedHeights.upper) {
      // Align to codec requirements (e.g., multiple of 2, 8, or 16)
      alignedHeight = clampedHeight - (clampedHeight % videoCaps.heightAlignment)
      alignedWidth = (alignedHeight * aspectRatio).toInt()
      alignedWidth -= alignedWidth % videoCaps.widthAlignment
    }

    val clampedFps = rawFps.coerceIn(
      videoCaps.supportedFrameRates.lower.toInt(),
      videoCaps.supportedFrameRates.upper.toInt()
    )
    val clampedBitrate = rawBitrate.coerceIn(
      videoCaps.bitrateRange.lower,
      videoCaps.bitrateRange.upper
    )

    // Apply values back to outputFormat
    outputFormat.setInteger(MediaFormat.KEY_WIDTH, alignedWidth)
    outputFormat.setInteger(MediaFormat.KEY_HEIGHT, alignedHeight)
    outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, clampedFps)
    outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, clampedBitrate)
    outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, getIFrameIntervalRate(inputFormat))

    // Always required for surface input
    outputFormat.setInteger(
      MediaFormat.KEY_COLOR_FORMAT,
      MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
    )

    // --- Propagate color info if present ---
    getColorStandard(inputFormat)?.let {
      outputFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, it)
    }
    getColorTransfer(inputFormat)?.let {
      outputFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, it)
    }
    getColorRange(inputFormat)?.let {
      outputFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, it)
    }

    Log.i(
      "EncoderSelect",
      "Configured format for ${codecInfo.name}: " +
              "raw=${rawWidth}x${rawHeight}@${rawFps} ${rawBitrate}bps → " +
              "aligned=${alignedWidth}x${alignedHeight}@${clampedFps} ${clampedBitrate}bps"
    )
  }

  // Get the frame rate from the input format or use a default value
  private fun getFrameRate(format: MediaFormat): Int {
    return if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE)
    else 30
  }

  // Get the I-frame (keyframe) interval from the input format or use a default value
  private fun getIFrameIntervalRate(format: MediaFormat): Int {
    return if (format.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL)) format.getInteger(
      MediaFormat.KEY_I_FRAME_INTERVAL
    )
    else I_FRAME_INTERVAL
  }

  // Get the color standard from the input format or null if not available
  private fun getColorStandard(format: MediaFormat): Int? {
    return if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) format.getInteger(
      MediaFormat.KEY_COLOR_STANDARD
    )
    else null
  }

  // Get the color transfer from the input format or null if not available
  private fun getColorTransfer(format: MediaFormat): Int? {
    return if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(
      MediaFormat.KEY_COLOR_TRANSFER
    )
    else null
  }

  // Get the color range from the input format or null if not available
  private fun getColorRange(format: MediaFormat): Int? {
    return if (format.containsKey(MediaFormat.KEY_COLOR_RANGE)) format.getInteger(
      MediaFormat.KEY_COLOR_RANGE
    )
    else null
  }

  /**
   * Find the track index for video or audio in the media extractor.
   *
   * @param extractor MediaExtractor used to extract data from the media source.
   * @param isVideo Determines whether we are looking for a video or audio track.
   * @return Index of the requested track, or -5 if not found.
   */
  fun findTrack(
    extractor: MediaExtractor,
    isVideo: Boolean,
  ): Int {
    val numTracks = extractor.trackCount
    for (i in 0 until numTracks) {
      val format = extractor.getTrackFormat(i)
      val mime = format.getString(MediaFormat.KEY_MIME)
      if (isVideo) {
        if (mime?.startsWith("video/")!!) return i
      } else {
        if (mime?.startsWith("audio/")!!) return i
      }
    }
    return -5
  }

  /**
   * Log an exception with a meaningful message.
   */
  fun printException(exception: Exception) {
    var message = "An error has occurred!"
    exception.localizedMessage?.let {
      message = it
    }
    Log.e("Compressor", message, exception)
  }

  /**
   * Check if the device has QTI (Qualcomm Technologies, Inc.) codecs.
   */
  fun hasQTI(): Boolean {
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    for (codec in list) {
      Log.i("CODECS: ", codec.name)
      if (codec.name.contains("qti.avc")) {
        return true
      }
    }
    return false
  }
}
