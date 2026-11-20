package com.reactnativecompressor.Video.VideoCompressor.compressor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.reactnativecompressor.Video.VideoCompressor.CompressionProgressListener
import com.reactnativecompressor.Video.VideoCompressor.utils.CompressorUtils.findTrack
import com.reactnativecompressor.Video.VideoCompressor.utils.CompressorUtils.prepareVideoHeight
import com.reactnativecompressor.Video.VideoCompressor.utils.CompressorUtils.prepareVideoWidth
import com.reactnativecompressor.Video.VideoCompressor.utils.CompressorUtils.printException
import com.reactnativecompressor.Video.VideoCompressor.utils.CompressorUtils.setOutputFileParameters
import com.reactnativecompressor.Video.VideoCompressor.utils.CompressorUtils.setUpMP4Movie
import com.reactnativecompressor.Video.VideoCompressor.utils.StreamableVideo
import com.reactnativecompressor.Video.VideoCompressor.video.InputSurface
import com.reactnativecompressor.Video.VideoCompressor.video.MP4Builder
import com.reactnativecompressor.Video.VideoCompressor.video.OutputSurface
import com.reactnativecompressor.Video.VideoCompressor.video.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

object Compressor {

  // Minimum bitrate for compression (2Mbps)
  private const val MIN_BITRATE = 2000000

  // MIME type for H.264 Advanced Video Coding
  private const val MIME_TYPE = "video/avc"

  // Default timeout for MediaCodec operations
  private const val MEDIACODEC_TIMEOUT_DEFAULT = 100L

  // Error message for invalid bitrate
  private const val INVALID_BITRATE =
    "The provided bitrate is smaller than what is needed for compression, " +
      "try to set isMinBitRateEnabled to false"

  // Flag to check if compression is running
  var isRunning = true

  // Pending mandatory metadata — set before transcoding, injected into MP4 after
  @Volatile var pendingGpsLatitude: Double? = null
  @Volatile var pendingGpsLongitude: Double? = null
  @Volatile var pendingGpsAltitude: Double? = null
  @Volatile var pendingGpsLatitudeRef: String? = null
  @Volatile var pendingGpsLongitudeRef: String? = null
  @Volatile var pendingDeviceMake: String? = null
  @Volatile var pendingDeviceModel: String? = null
  @Volatile var pendingCreationDate: String? = null

  suspend fun compressVideo(
    index: Int,
    context: Context,
    srcUri: Uri,
    destination: String,
    streamableFile: String?,
    outputWidth: Int,
    outputHeight: Int,
    outputBitrate: Int,
    startTime: Long = 0L,
    endTime: Long = -1L,
    // Mandatory metadata — extracted from original asset before transcoding
    gpsLatitude: Double? = null,
    gpsLongitude: Double? = null,
    gpsAltitude: Double? = null,
    gpsLatitudeRef: String? = null,
    gpsLongitudeRef: String? = null,
    deviceMake: String? = null,
    deviceModel: String? = null,
    creationDate: String? = null,
    listener: CompressionProgressListener,
  ): Result = withContext(Dispatchers.Default) {

    // Store metadata for injection after transcoding
    pendingGpsLatitude = gpsLatitude
    pendingGpsLongitude = gpsLongitude
    pendingGpsAltitude = gpsAltitude
    pendingGpsLatitudeRef = gpsLatitudeRef
    pendingGpsLongitudeRef = gpsLongitudeRef
    pendingDeviceMake = deviceMake
    pendingDeviceModel = deviceModel
    pendingCreationDate = creationDate

    // Initialize MediaExtractor and MediaMetadataRetriever
    val extractor = MediaExtractor()
    val mediaMetadataRetriever = MediaMetadataRetriever()

    try {
      // Set the data source for the MediaMetadataRetriever
      mediaMetadataRetriever.setDataSource(context, srcUri)
    } catch (exception: IllegalArgumentException) {
      printException(exception)
      return@withContext Result(
        index,
        success = false,
        failureMessage = "${exception.message}"
      )
    }

    runCatching {
      // Set the data source for the MediaExtractor
      extractor.setDataSource(context, srcUri, null)
    }

    // Retrieve video metadata
    val height: Double = prepareVideoHeight(mediaMetadataRetriever)
    val width: Double = prepareVideoWidth(mediaMetadataRetriever)
    val rotationData = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
    val bitrateData = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
    val durationData = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)

    // Check if any metadata is missing
    if (rotationData.isNullOrEmpty() || bitrateData.isNullOrEmpty() || durationData.isNullOrEmpty()) {
      // Exit execution
      return@withContext Result(
        index,
        success = false,
        failureMessage = "Failed to extract video metadata, please try again"
      )
    }

    var rotation = rotationData.toInt()
    val totalDuration = durationData.toLong() * 1000 // Convert to microseconds

    // Validate and adjust trim times
    val adjustedStartTime = startTime.coerceAtLeast(0L) * 1000 // Convert to microseconds
    val adjustedEndTime = if (endTime == -1L) totalDuration else endTime.coerceAtMost(totalDuration / 1000) * 1000 // Convert to microseconds
    
    if (adjustedStartTime >= adjustedEndTime) {
        return@withContext Result(
            index,
            success = false,
            failureMessage = "Start time must be before end time"
        )
    }

    val duration = adjustedEndTime - adjustedStartTime // Duration of the trimmed segment

    // Handle new bitrate value
    val newBitrate: Int = outputBitrate

    // Handle new width and height values
    var (newWidth, newHeight) = Pair(outputWidth, outputHeight)

    // Handle rotation values and swapping height and width if needed
    rotation = when (rotation) {
      90, 270 -> {
        val tempHeight = newHeight
        newHeight = newWidth
        newWidth = tempHeight
        0
      }
      180 -> 0
      else -> rotation
    }

    // Start video compression with trim parameters
    return@withContext start(
      index,
      newWidth!!,
      newHeight!!,
      destination,
      newBitrate,
      streamableFile,
      false,
      extractor,
      listener,
      duration,
      rotation,
      adjustedStartTime,
      adjustedEndTime
    )
  }

  // Function to start video compression with trim functionality
  @Suppress("DEPRECATION")
  private fun start(
    id: Int,
    newWidth: Int,
    newHeight: Int,
    destination: String,
    newBitrate: Int,
    streamableFile: String?,
    disableAudio: Boolean,
    extractor: MediaExtractor,
    compressionProgressListener: CompressionProgressListener,
    duration: Long,
    rotation: Int,
    startTime: Long = 0L,
    endTime: Long = -1L
  ): Result {
    // Check if newWidth and newHeight are valid
    if (newWidth != 0 && newHeight != 0) {
      // Create a cache file for the compressed video
      val cacheFile = File(destination)

      try {
        // MediaCodec accesses encoder and decoder components and processes the new video
        // input to generate a compressed/smaller size video
        val bufferInfo = MediaCodec.BufferInfo()

        // Setup mp4 movie
        val movie = setUpMP4Movie(rotation, cacheFile)

        // MediaMuxer outputs MP4 in this app
        val mediaMuxer = MP4Builder().createMovie(movie)

        // Start with the video track
        val videoIndex = findTrack(extractor, isVideo = true)

        extractor.selectTrack(videoIndex)
        extractor.seekTo(startTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val inputFormat = extractor.getTrackFormat(videoIndex)

        val outputFormat: MediaFormat =
          MediaFormat.createVideoFormat(MIME_TYPE, newWidth, newHeight)

        val decoder: MediaCodec

        // Prepare the video encoder
        val encoder = prepareEncoder(inputFormat, outputFormat, newBitrate)

        val inputSurface: InputSurface
        val outputSurface: OutputSurface

        try {
          var inputDone = false
          var outputDone = false

          var videoTrackIndex = -5

          inputSurface = InputSurface(encoder.createInputSurface())
          inputSurface.makeCurrent()
          // Move to executing state
          encoder.start()

          outputSurface = OutputSurface()

          decoder = prepareDecoder(inputFormat, outputSurface)

          // Move to executing state
          decoder.start()

          while (!outputDone) {
            if (!inputDone) {

                            val index = extractor.sampleTrackIndex

                            if (index == videoIndex) {
                            val currentSampleTime = extractor.sampleTime
                            
                            // Check if we've reached the end time
                            if (endTime != -1L && currentSampleTime >= endTime) {
                                val inputBufferIndex = decoder.dequeueInputBuffer(MEDIACODEC_TIMEOUT_DEFAULT)
                                if (inputBufferIndex >= 0) {
                                    decoder.queueInputBuffer(
                                        inputBufferIndex,
                                        0,
                                        0,
                                        0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                }
                            } else {
                                val inputBufferIndex = decoder.dequeueInputBuffer(MEDIACODEC_TIMEOUT_DEFAULT)
                                if (inputBufferIndex >= 0) {
                                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                                    val chunkSize = extractor.readSampleData(inputBuffer!!, 0)
                                    when {
                                        chunkSize < 0 -> {

                                            decoder.queueInputBuffer(
                                                inputBufferIndex,
                                                0,
                                                0,
                                                0L,
                                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                            )
                                            inputDone = true
                                        }
                                        else -> {
                                            decoder.queueInputBuffer(
                                                inputBufferIndex,
                                                0,
                                                chunkSize,
                                                currentSampleTime - startTime, // Adjust timestamp relative to start
                                                0
                                            )
                                            extractor.advance()
                                        }
                                        }
                                    }
                                }

                            } else if (index == -1) { //end of file
                                val inputBufferIndex = decoder.dequeueInputBuffer(MEDIACODEC_TIMEOUT_DEFAULT)
                                if (inputBufferIndex >= 0) {
                                    decoder.queueInputBuffer(
                                        inputBufferIndex,
                                        0,
                                        0,
                                        0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                }
                            }
                        }

                        var decoderOutputAvailable = true
                        var encoderOutputAvailable = true

                        loop@ while (decoderOutputAvailable || encoderOutputAvailable) {

                            if (!isRunning) {
                                dispose(
                                    videoIndex,
                                    decoder,
                                    encoder,
                                    inputSurface,
                                    outputSurface,
                                    extractor
                                )

                                compressionProgressListener.onProgressCancelled(id)
                                return Result(
                                    id,
                                    success = false,
                                    failureMessage = "The compression has stopped!"
                                )
                            }

                            //Encoder
                            val encoderStatus =
                                encoder.dequeueOutputBuffer(bufferInfo, MEDIACODEC_TIMEOUT_DEFAULT)

                            when {
                                encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> encoderOutputAvailable =
                                    false
                                encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    val newFormat = encoder.outputFormat
                                    if (videoTrackIndex == -5)
                                        videoTrackIndex = mediaMuxer.addTrack(newFormat, false)
                                }
                                encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                                    // ignore this status
                                }
                                encoderStatus < 0 -> throw RuntimeException("unexpected result from encoder.dequeueOutputBuffer: $encoderStatus")
                                else -> {
                                    val encodedData = encoder.getOutputBuffer(encoderStatus)
                                        ?: throw RuntimeException("encoderOutputBuffer $encoderStatus was null")

                                    if (bufferInfo.size > 1) {
                                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                            mediaMuxer.writeSampleData(
                                                videoTrackIndex,
                                                encodedData, bufferInfo, false
                                            )
                                        }

                                    }

                                    outputDone =
                                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    encoder.releaseOutputBuffer(encoderStatus, false)
                                }
                            }
                            if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) continue@loop

                            //Decoder
                            val decoderStatus =
                                decoder.dequeueOutputBuffer(bufferInfo, MEDIACODEC_TIMEOUT_DEFAULT)
                            when {
                                decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> decoderOutputAvailable =
                                    false
                                decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                                    // ignore this status
                                }
                                decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    // ignore this status
                                }
                                decoderStatus < 0 -> throw RuntimeException("unexpected result from decoder.dequeueOutputBuffer: $decoderStatus")
                                else -> {
                                    val doRender = bufferInfo.size != 0

                                    decoder.releaseOutputBuffer(decoderStatus, doRender)
                                    if (doRender) {
                                        var errorWait = false
                                        try {
                                            outputSurface.awaitNewImage()
                                        } catch (e: Exception) {
                                            errorWait = true
                                            Log.e(
                                                "Compressor",
                                                e.message ?: "Compression failed at swapping buffer"
                                            )
                                        }

                                        if (!errorWait) {
                                            outputSurface.drawImage()

                                            inputSurface.setPresentationTime(bufferInfo.presentationTimeUs * 1000)

                                            compressionProgressListener.onProgressChanged(
                                                id,
                                                bufferInfo.presentationTimeUs.toFloat() / duration.toFloat() * 100
                                            )

                                            inputSurface.swapBuffers()
                                        }
                                    }
                                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        decoderOutputAvailable = false
                                        encoder.signalEndOfInputStream()
                                    }
                                }
                            }
                        }
          }

        } catch (exception: Exception) {
          printException(exception)
          return Result(id, success = false, failureMessage = exception.message)
        }

        // Release resources
        dispose(
          videoIndex,
          decoder,
          encoder,
          inputSurface,
          outputSurface,
          extractor
        )

        // Process audio if necessary
        processAudio(
          mediaMuxer = mediaMuxer,
          bufferInfo = bufferInfo,
          disableAudio = disableAudio,
          extractor = extractor,
          startTime = startTime,
          endTime = endTime
        )

        extractor.release()
        try {
          mediaMuxer.finishMovie()
        } catch (e: Exception) {
          printException(e)
        }

        // Inject mandatory metadata (GPS, device info, creation date) into the output MP4
        // at the binary level by appending udta atoms after the moov box.
        try {
          injectMetadataIntoMp4(cacheFile)
        } catch (e: Exception) {
          Log.w("Compressor", "Metadata injection failed (non-fatal): ${e.message}")
        }

      } catch (exception: Exception) {
        printException(exception)
      }

      var resultFile = cacheFile

      // Process the result and create a streamable video if requested
      streamableFile?.let {
        try {
          val result = StreamableVideo.start(`in` = cacheFile, out = File(it))
          resultFile = File(it)
          if (result && cacheFile.exists()) {
            cacheFile.delete()
          }

        } catch (e: Exception) {
          printException(e)
        }
      }
      return Result(
        id,
        success = true,
        failureMessage = null,
        size = resultFile.length(),
        resultFile.path
      )
    }

    return Result(
      id,
      success = false,
      failureMessage = "Something went wrong, please try again"
    )
  }

  // Function to process audio
    private fun processAudio(
        mediaMuxer: MP4Builder,
        bufferInfo: MediaCodec.BufferInfo,
        disableAudio: Boolean,
        extractor: MediaExtractor,
        startTime: Long = 0L, // Start time in microseconds
        endTime: Long = -1L // End time in microseconds (-1 means end of video)
    ) {
        val audioIndex = findTrack(extractor, isVideo = false)
        if (audioIndex >= 0 && !disableAudio) {
            extractor.selectTrack(audioIndex)
            val audioFormat = extractor.getTrackFormat(audioIndex)
            val muxerTrackIndex = mediaMuxer.addTrack(audioFormat, true)
            var maxBufferSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)

            if (maxBufferSize <= 0) {
                maxBufferSize = 64 * 1024
            }

            var buffer: ByteBuffer = ByteBuffer.allocateDirect(maxBufferSize)
            if (Build.VERSION.SDK_INT >= 28) {
                val size = extractor.sampleSize
                if (size > maxBufferSize) {
                    maxBufferSize = (size + 1024).toInt()
                    buffer = ByteBuffer.allocateDirect(maxBufferSize)
                }
            }
            var inputDone = false
            extractor.seekTo(startTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            while (!inputDone) {
                val index = extractor.sampleTrackIndex
                if (index == audioIndex) {
                    val currentSampleTime = extractor.sampleTime
                    
                    // Check if we've reached the end time
                    if (endTime != -1L && currentSampleTime >= endTime) {
                        bufferInfo.size = 0
                        inputDone = true
                        continue
                    }
                    
                    bufferInfo.size = extractor.readSampleData(buffer, 0)

                    if (bufferInfo.size >= 0) {
                        bufferInfo.apply {
                            presentationTimeUs = currentSampleTime - startTime // Adjust timestamp relative to start
                            offset = 0
                            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                        }
                        mediaMuxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo, true)
                        extractor.advance()

                    } else {
                        bufferInfo.size = 0
                        inputDone = true
                    }
                } else if (index == -1) {
                    inputDone = true
                }
            }
            extractor.unselectTrack(audioIndex)
        }
    }

  // Function to prepare the video encoder
    private fun prepareEncoder(
        inputFormat: MediaFormat,
        outputFormat: MediaFormat,
        newBitrate: Int? = null // optional bitrate override
    ): MediaCodec {
        val mime = outputFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"

        // Priority order for encoders
        val preferredEncoders = when (mime) {
            "video/avc" -> listOf(
                "c2.android.avc.encoder",   // AOSP hardware AVC
                "OMX.google.h264.encoder"   // Software AVC fallback
            )
            "video/hevc" -> listOf(
                "c2.android.hevc.encoder",  // AOSP hardware HEVC
                "OMX.google.hevc.encoder"   // Software HEVC fallback
            )
            else -> emptyList()
        }

        for (name in preferredEncoders) {
            try {
                val codec = MediaCodec.createByCodecName(name)

                // Configure output parameters before configure()
                setOutputFileParameters(inputFormat, outputFormat, codec, newBitrate)

                codec.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i("EncoderSelect", "Using encoder: $name for $mime")
                return codec
            } catch (e: Exception) {
                Log.w("EncoderSelect", "Failed with $name, trying next. Error: ${e.message}")
            }
        }

        // Fallback: let Android pick the default encoder
        val codec = MediaCodec.createEncoderByType(mime)
        setOutputFileParameters(inputFormat, outputFormat, codec, newBitrate)
        codec.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        Log.i("EncoderSelect", "Using system default encoder for $mime")
        return codec
    }

  // Function to prepare the video decoder with fallback
    private fun prepareDecoder(
        inputFormat: MediaFormat,
        outputSurface: OutputSurface,
    ): MediaCodec {
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"

        // Priority order for decoders, especially for Snapdragon 8 Gen 3 (S24 Ultra) HEVC
        val preferredDecoders = when (mime) {
            "video/hevc" -> listOf(
                "c2.qti.hevc.decoder",       // Qualcomm hardware HEVC decoder
                "c2.android.hevc.decoder",   // AOSP hardware/software HEVC decoder
                "OMX.google.hevc.decoder"    // Google software HEVC fallback
            )
            "video/avc" -> listOf(
                "c2.qti.avc.decoder",        // Qualcomm hardware AVC decoder
                "c2.android.avc.decoder",    // AOSP hardware AVC decoder
                "OMX.google.h264.decoder"    // Google software AVC fallback
            )
            else -> emptyList()
        }

        for (name in preferredDecoders) {
            try {
                val codec = MediaCodec.createByCodecName(name)
                codec.configure(inputFormat, outputSurface.getSurface(), null, 0)
                Log.i("DecoderSelect", "Using decoder: $name for $mime")
                return codec
            } catch (e: Exception) {
                Log.w("DecoderSelect", "Failed with $name for $mime, trying next. Error: ${e.message}")
            }
        }

        // Fallback: let Android select the default decoder for this MIME type
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, outputSurface.getSurface(), null, 0)
        Log.i("DecoderSelect", "Using system default decoder for $mime")
        return decoder
    }

  // Function to release resources
    private fun dispose(
        videoIndex: Int,
        decoder: MediaCodec,
        encoder: MediaCodec,
        inputSurface: InputSurface,
        outputSurface: OutputSurface,
        extractor: MediaExtractor
    ) {
        extractor.unselectTrack(videoIndex)

        decoder.stop()
        decoder.release()

        encoder.stop()
        encoder.release()

        inputSurface.release()
        outputSurface.release()
    }

  /**
   * Injects mandatory metadata (GPS, device info, creation date) into an MP4 file
   * by appending udta atoms directly to the file's moov box.
   *
   * Atoms written:
   *   ©xyz  — GPS coordinates in ISO 6709 format (e.g. "+37.4456-122.1585/")
   *   ©day  — Creation date ISO 8601 (e.g. "2026-06-04T08:34:47Z")
   *   ©mak  — Device manufacturer (e.g. "Xiaomi")
   *   ©mod  — Device model (e.g. "Redmi Note 10 Pro")
   *
   * This uses RandomAccessFile to append atoms after the moov box so that
   * no existing structure is damaged. All atoms follow the standard
   * ISO 14496-12 / QuickTime atom layout: [size:4][type:4][locale:4][data:n].
   */
  private fun injectMetadataIntoMp4(file: File) {
    val lat = pendingGpsLatitude ?: return // GPS is the minimum requirement; skip if absent
    val lon = pendingGpsLongitude ?: return

    // Build the atoms to inject
    val atoms = mutableListOf<ByteArray>()

    // --- GPS atom: ©xyz ---
    // Value: ISO 6709 string e.g. "+37.4456-122.1585/" (trailing slash required by spec)
    val latSign = if (lat >= 0) "+" else "-"
    val lonSign = if (lon >= 0) "+" else "-"
    val altStr = pendingGpsAltitude?.let { alt ->
      val altSign = if (alt >= 0) "+" else "-"
      "$altSign${"%05.0f".format(Math.abs(alt))}"
    } ?: ""
    val gpsStr = "$latSign${"%08.4f".format(Math.abs(lat))}$lonSign${"%09.4f".format(Math.abs(lon))}${altStr}/"
    atoms.add(buildQuickTimeAtom("\u00a9xyz", gpsStr))

    // --- Creation date atom: ©day ---
    pendingCreationDate?.takeIf { it.isNotBlank() }?.let {
      atoms.add(buildQuickTimeAtom("\u00a9day", it))
    }

    // --- Device make atom: ©mak ---
    pendingDeviceMake?.takeIf { it.isNotBlank() }?.let {
      atoms.add(buildQuickTimeAtom("\u00a9mak", it))
    }

    // --- Device model atom: ©mod ---
    pendingDeviceModel?.takeIf { it.isNotBlank() }?.let {
      atoms.add(buildQuickTimeAtom("\u00a9mod", it))
    }

    if (atoms.isEmpty()) return

    // Build a udta box wrapping all atoms
    val udtaPayload = atoms.fold(ByteArray(0)) { acc, a -> acc + a }
    val udtaSize = udtaPayload.size + 8
    val udtaBox = ByteArray(udtaSize)
    udtaBox[0] = ((udtaSize shr 24) and 0xFF).toByte()
    udtaBox[1] = ((udtaSize shr 16) and 0xFF).toByte()
    udtaBox[2] = ((udtaSize shr 8) and 0xFF).toByte()
    udtaBox[3] = (udtaSize and 0xFF).toByte()
    System.arraycopy("udta".toByteArray(Charsets.ISO_8859_1), 0, udtaBox, 4, 4)
    System.arraycopy(udtaPayload, 0, udtaBox, 8, udtaPayload.size)

    // Append the udta box to the end of the file
    java.io.RandomAccessFile(file, "rw").use { raf ->
      raf.seek(raf.length())
      raf.write(udtaBox)
    }
  }

  /**
   * Builds a single QuickTime metadata atom.
   * Layout: [4 bytes total size][4 bytes type][4 bytes locale=0x0001][2 bytes data length][data bytes]
   * The locale header (4 bytes 0x00 0x00 0x01 0x00) marks the atom as carrying a UTF-8 string.
   */
  private fun buildQuickTimeAtom(type: String, value: String): ByteArray {
    val valueBytes = value.toByteArray(Charsets.UTF_8)
    // Each QuickTime text atom has an inner data section: 2 bytes (length of string) + string bytes
    val innerSize = 2 + valueBytes.size
    // Total atom: 4 (size) + 4 (type) + 4 (locale) + innerSize
    val totalSize = 12 + innerSize
    val buf = ByteArray(totalSize)
    // Total size
    buf[0] = ((totalSize shr 24) and 0xFF).toByte()
    buf[1] = ((totalSize shr 16) and 0xFF).toByte()
    buf[2] = ((totalSize shr 8) and 0xFF).toByte()
    buf[3] = (totalSize and 0xFF).toByte()
    // 4-byte type code (padded to 4 bytes if needed, e.g. "©xyz")
    val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
    for (i in 0 until minOf(4, typeBytes.size)) buf[4 + i] = typeBytes[i]
    // Locale: 0x00 0x00 0x01 0x00 (English)
    buf[8]  = 0x00; buf[9]  = 0x00; buf[10] = 0x01; buf[11] = 0x00
    // Inner: 2-byte big-endian string length
    buf[12] = ((valueBytes.size shr 8) and 0xFF).toByte()
    buf[13] = (valueBytes.size and 0xFF).toByte()
    // Inner: string bytes
    System.arraycopy(valueBytes, 0, buf, 14, valueBytes.size)
    return buf
  }
}
