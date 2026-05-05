package com.reactnativecompressor.Video

import android.media.MediaMetadataRetriever
import android.net.Uri
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.reactnativecompressor.Utils.Utils.compressVideo
import com.reactnativecompressor.Utils.Utils.generateCacheFilePath
import java.io.File

object AutoVideoCompression {
    fun createCompressionSettings(fileUrl: String?, options: VideoCompressorHelper, promise: Promise, reactContext: ReactApplicationContext?) {
        val maxSize = options.maxSize
        val minimumFileSizeForCompress = options.minimumFileSizeForCompress
        try {
            val uri = Uri.parse(fileUrl)
            val srcPath = uri.path
            val metaRetriever = MediaMetadataRetriever()
            metaRetriever.setDataSource(srcPath)
            val file = File(srcPath)
            val sizeInBytes = file.length().toFloat()
            var sizeInMb = sizeInBytes / (1024 * 1024)
            val hasTrimming = options.startTime > 0 || options.endTime != -1L
            
            if (hasTrimming) {
                val durationString = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                if (durationString != null) {
                    val originalDurationMs = durationString.toDouble()
                    if (originalDurationMs > 0) {
                        val startMs = options.startTime.toDouble()
                        val endMs = if (options.endTime != -1L) options.endTime.toDouble() else originalDurationMs
                        val trimmedDurationMs = Math.max(0.0, endMs - startMs)
                        val ratio = trimmedDurationMs / originalDurationMs
                        sizeInMb = sizeInMb * ratio.toFloat()
                    }
                }
            }

            if (sizeInMb > minimumFileSizeForCompress || hasTrimming) {
                val destinationPath = generateCacheFilePath("mp4", reactContext!!)
                val actualHeight = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                val actualWidth = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                val bitrate = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)!!.toInt()
                
                val resultWidth: Int
                val resultHeight: Int
                val videoBitRate: Float

                if (sizeInMb <= minimumFileSizeForCompress) {
                    // Trimming only: keep original dimensions and bitrate to avoid compression
                    resultWidth = actualWidth
                    resultHeight = actualHeight
                    videoBitRate = bitrate.toFloat()
                } else {
                    val scale = if (actualWidth > actualHeight) maxSize / actualWidth else maxSize / actualHeight
                    resultWidth = Math.round(actualWidth * Math.min(scale, 1f) / 2) * 2
                    resultHeight = Math.round(actualHeight * Math.min(scale, 1f) / 2) * 2
                    videoBitRate = makeVideoBitrate(
                      actualHeight, actualWidth,
                      bitrate,
                      resultHeight, resultWidth
                    ).toFloat()
                }

                compressVideo(srcPath!!, destinationPath, resultWidth, resultHeight, videoBitRate, options.uuid!!,options.progressDivider!!, options.startTime, options.endTime, promise, reactContext)
            } else {
                promise.resolve(fileUrl)
            }
        } catch (ex: Exception) {
            promise.reject(ex)
        }
    }

    fun makeVideoBitrate(originalHeight: Int, originalWidth: Int, originalBitrate: Int, height: Int, width: Int): Int {
        val compressFactor = 0.8f
        val minCompressFactor = 0.8f
        val maxBitrate = 1669000
        var remeasuredBitrate = (originalBitrate / Math.min(originalHeight / height.toFloat(), originalWidth / width.toFloat())).toInt()
        remeasuredBitrate = (remeasuredBitrate * compressFactor).toInt()
        val minBitrate = (getVideoBitrateWithFactor(minCompressFactor) / (1280f * 720f / (width * height))).toInt()
        if (originalBitrate < minBitrate) {
            return remeasuredBitrate
        }
        return if (remeasuredBitrate > maxBitrate) {
            maxBitrate
        } else Math.max(remeasuredBitrate, minBitrate)
    }

    private fun getVideoBitrateWithFactor(f: Float): Int {
        return (f * 2000f * 1000f * 1.13f).toInt()
    }
}
