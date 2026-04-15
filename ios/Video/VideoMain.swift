import Foundation
import AVFoundation
import Photos
import MobileCoreServices

struct CompressionError: Error {
  private let message: String

  var localizedDescription: String {
    return message
  }

  init(message: String) {
    self.message = message
  }
}

class VideoCompressor {
  var backgroundTaskId: UIBackgroundTaskIdentifier = .invalid;

    static var compressorExports: [String: NextLevelSessionExporter] = [:]
    static var compressorExportSessions: [String: AVAssetExportSession] = [:]

    let metadatas: [String] = [
        "albumName",
        "artist",
        "comment",
        "copyrights",
        "creationDate",
        "date",
        "encodedby",
        "genre",
        "language",
        "location",
        "lastModifiedDate",
        "performer",
        "publisher",
        "title"
    ]

  func activateBackgroundTask(options: [String: Any], resolve:@escaping RCTPromiseResolveBlock, reject:@escaping RCTPromiseRejectBlock) -> Void {
    guard backgroundTaskId == .invalid else {
      reject("failed", "There is a background task already", nil)
      return
    }
    backgroundTaskId = UIApplication.shared.beginBackgroundTask(
      withName: "video-upload",
      expirationHandler: {
        EventEmitterHandler.emitBackgroundTaskExpired(self.backgroundTaskId)
        UIApplication.shared.endBackgroundTask(self.backgroundTaskId)
        self.backgroundTaskId = .invalid
    })
    resolve(backgroundTaskId)
  }

  func deactivateBackgroundTask(options: [String: Any], resolve:@escaping RCTPromiseResolveBlock, reject:@escaping RCTPromiseRejectBlock) -> Void {
    guard backgroundTaskId != .invalid else {
      reject("failed", "There is no active background task", nil)
        return
    }
    UIApplication.shared.endBackgroundTask(backgroundTaskId)
    resolve(nil)
    backgroundTaskId = .invalid
  }

  func compress(fileUrl: String, options: [String: Any], resolve:@escaping RCTPromiseResolveBlock, reject:@escaping RCTPromiseRejectBlock) -> Void {
    let progressDivider=options["progressDivider"] as? Int ?? 0
    compressVideo(url: URL(string: fileUrl)!, options:options,
    onProgress: { progress in
        EventEmitterHandler.emitVideoCompressProgress(progress, uuid: options["uuid"] as! String)
    }, onCompletion: { newUrl in
      resolve("\(newUrl)");
    }, onFailure: { error in
      reject("failed", "Compression Failed", error)
    })
  }

    func cancelCompression(uuid: String) -> Void {
        VideoCompressor.compressorExports[uuid]?.cancelExport()
        VideoCompressor.compressorExportSessions[uuid]?.cancelExport()
    }

    func getfileSize(forURL url: Any) -> Double {
        var fileURL: URL?
        var fileSize: Double = 0.0
        if (url is URL) || (url is String)
        {
            if (url is URL) {
                fileURL = url as? URL
            }
            else {
                var cleanString = url as! String
                if cleanString.hasPrefix("file://") {
                    cleanString = cleanString.replacingOccurrences(of: "file://", with: "")
                }
                fileURL = URL(fileURLWithPath: cleanString)
            }
            var fileSizeValue = 0.0
            try? fileSizeValue = (fileURL?.resourceValues(forKeys: [URLResourceKey.fileSizeKey]).allValues.first?.value as! Double?)!
            if fileSizeValue > 0.0 {
                fileSize = (Double(fileSizeValue) / (1024 * 1024))
            }
        }
        return fileSize
    }


  func compressVideo(url: URL, options: [String: Any], onProgress: @escaping (Float) -> Void,  onCompletion: @escaping (URL) -> Void, onFailure: @escaping (Error) -> Void){
      let uuid:String = options["uuid"] as! String

VideoCompressor.fetchAVAsset(
    url.absoluteString, 
    options: options,
    completionHandler: { asset, audioMix in
        var minimumFileSizeForCompress:Double=0.0;
        let fileSize = self.getAssetSizeInMB(asset: asset)
        
        if let minSize = options["minimumFileSizeForCompress"] as? Double {
            minimumFileSizeForCompress = minSize
        }
        if(fileSize>minimumFileSizeForCompress)
        {
            if(options["compressionMethod"] as! String=="auto")
            {
                self.autoCompressionHelper(asset: asset, audioMix: audioMix, options:options) { progress in
                    onProgress(progress)
                } onCompletion: { outputURL in
                    onCompletion(outputURL)
                } onFailure: { error in
                    onFailure(error)
                }
            }
            else
            {
                self.manualCompressionHelper(asset: asset, audioMix: audioMix, options:options) { progress in
                    onProgress(progress)
                } onCompletion: { outputURL in
                    onCompletion(outputURL)
                } onFailure: { error in
                    onFailure(error)
                }
            }



        }
        else
        {
            onCompletion(url)
        }
    },
    errorHandler: { error in
        onFailure(error)
    }
)
}


    func  makeVideoBitrate(originalHeight:Int,originalWidth:Int,originalBitrate:Int,height:Int,width:Int)->Int {
        let compressFactor:Float = 0.8
        let  minCompressFactor:Float = 0.8
        let maxBitrate:Int = 1669000
        let minValue:Float=min(Float(originalHeight)/Float(height),Float(originalWidth)/Float(width))
        var remeasuredBitrate:Int = Int(Float(originalBitrate) / minValue)
        remeasuredBitrate = Int(Float(remeasuredBitrate)*compressFactor)
        let minBitrate:Int = Int(Float(self.getVideoBitrateWithFactor(f: minCompressFactor)) / (1280 * 720 / Float(width * height)))
        if (originalBitrate < minBitrate) {
          return remeasuredBitrate;
        }
        if (remeasuredBitrate > maxBitrate) {
          return maxBitrate;
        }
        return max(remeasuredBitrate, minBitrate);
      }
    func getVideoBitrateWithFactor(f:Float)->Int {
        return Int(f * 2000 * 1000 * 1.13);
      }

    func autoCompressionHelper(asset: AVAsset, audioMix: AVAudioMix?, options: [String: Any], onProgress: @escaping (Float) -> Void,  onCompletion: @escaping (URL) -> Void, onFailure: @escaping (Error) -> Void){
        let maxSize:Float = options["maxSize"] as! Float;
        let uuid:String = options["uuid"] as! String
        let progressDivider=options["progressDivider"] as? Int ?? 0

        guard asset.tracks.count >= 1 else {
          let error = CompressionError(message: "Invalid video asset, no track found")
          onFailure(error)
          return
        }
        let track = getVideoTrack(asset: asset);

        let videoSize = track.naturalSize.applying(track.preferredTransform);
        let actualWidth = Float(abs(videoSize.width))
        let actualHeight = Float(abs(videoSize.height))
        
        guard actualWidth > 0 && actualHeight > 0 else {
            onFailure(CompressionError(message: "Invalid video dimensions (0x0)"))
            return
        }

        let originalBitrate = Float(abs(track.estimatedDataRate))
        let bitrate = originalBitrate > 0 ? originalBitrate : (actualWidth * actualHeight * 2.0)
        
        let scale:Float = actualWidth > actualHeight ? (Float(maxSize) / actualWidth) : (Float(maxSize) / actualHeight);
        let resultWidth:Float = round(actualWidth * min(scale, 1) / 2) * 2;
        let resultHeight:Float = round(actualHeight * min(scale, 1) / 2) * 2;

        let videoBitRate:Int = self.makeVideoBitrate(
            originalHeight: Int(actualHeight), originalWidth: Int(actualWidth),
            originalBitrate: Int(bitrate),
            height: Int(resultHeight), width: Int(resultWidth)
        )

        exportVideoHelper(asset: asset, audioMix: audioMix, bitRate: videoBitRate, resultWidth: resultWidth, resultHeight: resultHeight,uuid: uuid,progressDivider: progressDivider, options: options) { progress in
            onProgress(progress)
        } onCompletion: { outputURL in
            onCompletion(outputURL)
        } onFailure: { error in
            onFailure(error)
        }
      }

    func manualCompressionHelper(asset: AVAsset, audioMix: AVAudioMix?, options: [String: Any], onProgress: @escaping (Float) -> Void,  onCompletion: @escaping (URL) -> Void, onFailure: @escaping (Error) -> Void){
        let uuid:String = options["uuid"] as! String
        let requestedBitRate = (options["bitrate"] as? NSNumber)?.floatValue
        let progressDivider=options["progressDivider"] as? Int ?? 0
        
        guard asset.tracks.count >= 1 else {
          onFailure(CompressionError(message: "Invalid video asset, no track found"))
          return
        }
        let track = getVideoTrack(asset: asset);

        let videoSize = track.naturalSize.applying(track.preferredTransform);
        var width = Float(abs(videoSize.width))
        var height = Float(abs(videoSize.height))
        guard width > 0 && height > 0 else {
            onFailure(CompressionError(message: "Invalid video dimensions (0x0)"))
            return
        }
        let isPortrait = height > width
        let maxSize = (options["maxSize"] as! Float?) ?? Float(1920);
        if(isPortrait && height > maxSize){
          width = (maxSize/height)*width
          height = maxSize
        }else if(width > maxSize){
          height = (maxSize/width)*height
          width = maxSize
        }
        
        // Ensure even dimensions (H.264 encoder requirement)
        width = Float(Int(width / 2) * 2)
        height = Float(Int(height / 2) * 2)

        // Calculate bitrate: use user-provided, or cap to 80% of original, or fall back to baseline
        let baselineBitrate = height * width * 1.5
        let originalBitrate = Float(abs(track.estimatedDataRate))
        
        let finalBitRate: Float
        if let userBitRate = requestedBitRate {
            finalBitRate = userBitRate
        } else if originalBitrate > 0 {
            finalBitRate = min(originalBitrate * 0.8, baselineBitrate)
        } else {
            finalBitRate = baselineBitrate
        }

        // Pass audioMix down
        exportVideoHelper(asset: asset, audioMix: audioMix, bitRate: Int(finalBitRate), resultWidth: width, resultHeight: height, uuid: uuid, progressDivider: progressDivider, options: options) { progress in
            onProgress(progress)
        } onCompletion: { outputURL in
            onCompletion(outputURL)
        } onFailure: { error in
            onFailure(error)
        }
    }

    func exportVideoHelper(asset: AVAsset, audioMix: AVAudioMix?, bitRate: Int,resultWidth:Float,resultHeight:Float,uuid:String,progressDivider: Int, options: [String: Any], onProgress: @escaping (Float) -> Void,  onCompletion: @escaping (URL) -> Void, onFailure: @escaping (Error) -> Void){
        var currentVideoCompression:Int=0

        var tmpURL = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
          .appendingPathComponent(ProcessInfo().globallyUniqueString)
          .appendingPathExtension("mp4")
        tmpURL = URL(string: Utils.makeValidUri(filePath: tmpURL.absoluteString))!

        let exporter = NextLevelSessionExporter(withAsset: asset)
        exporter.outputURL = tmpURL
        exporter.outputFileType = AVFileType.mp4
        exporter.audioMix = audioMix
        exporter.metadata = asset.metadata

        if let startMs = options["startTime"] as? Double, let endMs = options["endTime"] as? Double {
            let startTime = startMs / 1000.0
            let endTime = endMs / 1000.0
            let start = CMTime(seconds: startTime, preferredTimescale: 600)
            let end = CMTime(seconds: endTime, preferredTimescale: 600)
            let duration = CMTimeSubtract(end, start)
            
            exporter.timeRange = CMTimeRange(start: start, duration: duration)
        }

        let compressionDict: [String: Any] = [
          AVVideoAverageBitRateKey: bitRate,
          AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
        ]
        exporter.optimizeForNetworkUse = true;
        exporter.videoOutputConfiguration = [
          AVVideoCodecKey: AVVideoCodecType.h264,
          AVVideoWidthKey:  resultWidth,
          AVVideoHeightKey:  resultHeight,
          AVVideoScalingModeKey: AVVideoScalingModeResizeAspectFill,
          AVVideoCompressionPropertiesKey: compressionDict
        ]
        exporter.audioOutputConfiguration = [
          AVFormatIDKey: kAudioFormatMPEG4AAC,
          AVEncoderBitRateKey: NSNumber(integerLiteral: 128000),
          AVNumberOfChannelsKey: NSNumber(integerLiteral: 2),
          AVSampleRateKey: NSNumber(value: Float(44100))
        ]

        VideoCompressor.compressorExports[uuid] = exporter
        exporter.export(progressHandler: { (progress) in
            let roundProgress:Int=Int((progress*100).rounded());
            if(progressDivider==0||(roundProgress%progressDivider==0&&roundProgress>currentVideoCompression))
            {
            currentVideoCompression=roundProgress
            onProgress(progress)
            }
        }, completionHandler: { result in
            currentVideoCompression=0;
            VideoCompressor.cleanUpSandboxFiles(for: tmpURL)
            switch exporter.status {
            case .completed:
                onCompletion(exporter.outputURL!)
            case .cancelled:
                try? FileManager.default.removeItem(at: tmpURL)
                onFailure(CompressionError(message: "Compression cancelled"))
            case .failed:
                try? FileManager.default.removeItem(at: tmpURL)
                onFailure(CompressionError(message: "Compression failed"))
            default:
                try? FileManager.default.removeItem(at: tmpURL)
                onFailure(CompressionError(message: "Unknown compression status"))
            }
        })
    }

    func getVideoTrack(asset: AVAsset) -> AVAssetTrack {
        let tracks = asset.tracks(withMediaType: AVMediaType.video)
        return tracks[0];
        }



    func getVideoMetaData(_ filePath: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        
        VideoCompressor.fetchAVAsset(
            filePath,
            options: [:],
            completionHandler: { asset, audioMix in
                var result: [String: Any] = [:]

                // 1. Get Duration
                let time = asset.duration
                let seconds = Double(time.value) / Double(time.timescale)
                result["duration"] = seconds

                // 2. Get Video Dimensions
                if let videoTrack = asset.tracks(withMediaType: .video).first {
                    let size = videoTrack.naturalSize.applying(videoTrack.preferredTransform)
                    result["width"] = abs(size.width)
                    result["height"] = abs(size.height)
                } else {
                    result["width"] = 0
                    result["height"] = 0
                }

                // 3. Get Size and Extension Safely
                var fileSize: UInt64 = 0
                var fileExtension = "mp4" // Default

                // Method A: If it's from the Photo Library, get exact size from the PHAsset database
                if filePath.contains("ph://") {
                    let assetId = filePath.replacingOccurrences(of: "ph://", with: "")
                    if let phAsset = PHAsset.fetchAssets(withLocalIdentifiers: [assetId], options: nil).firstObject {
                        let resources = PHAssetResource.assetResources(for: phAsset)
                        if let resource = resources.first(where: { $0.type == .video }),
                           let unsignedSize = resource.value(forKey: "fileSize") as? CLong {
                            fileSize = UInt64(unsignedSize)
                            fileExtension = (resource.originalFilename as NSString).pathExtension
                        }
                    }
                }

                // Method B: If not a PHAsset, or Method A failed, try reading the URL properties directly
                if fileSize == 0, let urlAsset = asset as? AVURLAsset {
                    fileExtension = urlAsset.url.pathExtension
                    if let size = try? urlAsset.url.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                        fileSize = UInt64(size)
                    }
                }

                // Method C: If it's an edited AVComposition, mathematically estimate the size
                if fileSize == 0 {
                    if let track = asset.tracks(withMediaType: .video).first {
                        let bitRate = track.estimatedDataRate
                        let sizeInBits = Double(bitRate) * seconds
                        fileSize = UInt64(max(0, sizeInBits / 8.0))
                    }
                }

                result["extension"] = fileExtension
                result["size"] = fileSize

                // 4. Get Common Metadata (Artist, Album, etc.)
                var commonMetadata: [AVMetadataItem] = []
                for key in self.metadatas {
                    let items = AVMetadataItem.metadataItems(from: asset.commonMetadata, withKey: key, keySpace: AVMetadataKeySpace.common)
                    commonMetadata.append(contentsOf: items)
                }

                for item in commonMetadata {
                    if let value = item.value, let commonKey = item.commonKey?.rawValue {
                        result[commonKey] = value
                    }
                }

                resolve(result)
            },
            errorHandler: { error in
                reject("MetadataError", error.localizedDescription, error)
            }
        )
    }
    func getAssetSizeInMB(asset: AVAsset) -> Double {
        if let urlAsset = asset as? AVURLAsset {
            // Precise size if it's a standard file
            var fileSizeValue = 0.0
            try? fileSizeValue = (urlAsset.url.resourceValues(forKeys: [.fileSizeKey]).allValues.first?.value as? Double) ?? 0.0
            return fileSizeValue / (1024 * 1024)
        } else {
            // Estimated size if it's an AVComposition (Slow-Mo/Edited)
            let duration = CMTimeGetSeconds(asset.duration)
            if let track = asset.tracks(withMediaType: .video).first {
                let bitRate = track.estimatedDataRate
                let sizeInBits = Double(bitRate) * duration
                return (sizeInBits / 8) / (1024 * 1024)
            }
            return 0.0
        }
    }

    static func fetchAVAsset(_ videoPath: String, options: [String: Any], completionHandler: @escaping (AVAsset, AVAudioMix?) -> Void, errorHandler: @escaping (Error) -> Void) {
        
        // 1. Handle HTTP (Download to temp file, then create AVAsset)
        if videoPath.hasPrefix("http://") || videoPath.hasPrefix("https://") {
            let uuid = options["uuid"] as? String ?? ""
            let progressDivider = options["progressDivider"] as? Int ?? 0
            Downloader.downloadFileAndSaveToCache(videoPath, uuid: uuid, progressDivider: progressDivider) { downloadedPath in
                var cleanPath = downloadedPath
                if cleanPath.hasPrefix("file://") {
                    cleanPath = cleanPath.replacingOccurrences(of: "file://", with: "")
                }
                let asset = AVAsset(url: URL(fileURLWithPath: cleanPath))
                completionHandler(asset, nil)
            }
            return
        } 
        
        // 2. Handle standard Local Files
        if !videoPath.contains("ph://") {
            var cleanPath = videoPath
            if cleanPath.hasPrefix("file://") {
                cleanPath = cleanPath.replacingOccurrences(of: "file://", with: "")
            }
            let asset = AVAsset(url: URL(fileURLWithPath: cleanPath))
            completionHandler(asset, nil)
            return
        }
        
        // 3. Handle PHAsset (The magic happens here)
        let assetId = videoPath.replacingOccurrences(of: "ph://", with: "")
        if assetId.isEmpty {
            errorHandler(CompressionError(message: "Empty asset ID"))
            return
        }

        let localIds = [assetId]
        guard let videoAsset = PHAsset.fetchAssets(withLocalIdentifiers: localIds, options: nil).firstObject else {
            errorHandler(CompressionError(message: "Video asset not found"))
            return
        }

        let videoRequestOptions = PHVideoRequestOptions()
        videoRequestOptions.isNetworkAccessAllowed = true // Handles iCloud
        videoRequestOptions.deliveryMode = .highQualityFormat

        // requestAVAsset will download from iCloud if needed, and return EITHER an AVURLAsset or an AVComposition.
        // We don't care which one it is, we just pass it along!
        PHImageManager.default().requestAVAsset(forVideo: videoAsset, options: videoRequestOptions) { (asset, audioMix, info) in
            guard let asset = asset else {
                errorHandler(CompressionError(message: "Could not fetch AVAsset from Photos"))
                return
            }
            print("🚀 Successfully fetched AVAsset directly into memory!")
            completionHandler(asset, audioMix)
        }
    }

    static func getAbsoluteVideoPath(_ videoPath: String, options: [String: Any], completionHandler: @escaping (String, [String: Any]) -> Void, errorHandler: @escaping (Error) -> Void) {
        if videoPath.hasPrefix("http://") || videoPath.hasPrefix("https://") {
            let uuid=options["uuid"] as? String ?? ""
            let progressDivider=options["progressDivider"] as? Int ?? 0
            Downloader.downloadFileAndSaveToCache(videoPath, uuid: uuid,progressDivider:progressDivider) { downloadedPath in
                completionHandler(downloadedPath, options)
            }
            return
        } else if !videoPath.contains("ph://") {
            completionHandler(Utils.slashifyFilePath(path: videoPath)!, options)
            return
        }
        let assetId = videoPath.replacingOccurrences(of: "ph://", with: "")

        if assetId.isEmpty {
            errorHandler(CompressionError(message: "Empty asset ID"))
            return
        }

        let localIds = [assetId]
        guard let videoAsset = PHAsset.fetchAssets(withLocalIdentifiers: localIds, options: nil).firstObject else {
            errorHandler(CompressionError(message: "Video asset not found"))
            return
        }


        let videoRequestOptions = PHVideoRequestOptions()
        videoRequestOptions.isNetworkAccessAllowed = true
        videoRequestOptions.deliveryMode = .highQualityFormat

        PHImageManager.default().requestAVAsset(forVideo: videoAsset, options: videoRequestOptions) { (asset, audioMix, info) in
            
            // Check if we got a direct URL asset
            if let urlAsset = asset as? AVURLAsset {
                print("🚀 Fast Path: Got direct URL from PHAsset")
                completionHandler(urlAsset.url.absoluteString, options)
                return
            }
            
            // FALLBACK: If it's a Slow-Mo or Composition, we must use ExportSession
            print("⚠️ Slow Path: Video is a composition, starting export session...")
            self.fallbackExport(videoAsset: videoAsset, options: options, completionHandler: completionHandler, errorHandler: errorHandler)
        }
    }
    private static func fallbackExport(videoAsset: PHAsset, options: [String: Any], completionHandler: @escaping (String, [String: Any]) -> Void, errorHandler: @escaping (Error) -> Void) {
        let outputFileType = AVFileType.mp4
        let pressetType = AVAssetExportPresetPassthrough
        let videoRequestOptions = PHVideoRequestOptions()
        videoRequestOptions.isNetworkAccessAllowed = true
        
        let mimeType = UTTypeCopyPreferredTagWithClass(outputFileType as CFString, kUTTagClassMIMEType)?.takeRetainedValue() as String? ?? ""
        let uti = UTTypeCreatePreferredIdentifierForTag(kUTTagClassMIMEType, mimeType as CFString, nil)?.takeRetainedValue() as String? ?? ""
        let extensionValue = UTTypeCopyPreferredTagWithClass(uti as CFString, kUTTagClassFilenameExtension)?.takeRetainedValue() as String? ?? ""
        let path = Utils.generateCacheFilePath(extensionValue)
        let outputUrl = URL(string: "file://\(path)")!

        PHImageManager.default().requestExportSession(forVideo: videoAsset, options: videoRequestOptions, exportPreset: pressetType) { exportSession, _ in
            guard let exportSession = exportSession else {
                errorHandler(CompressionError(message: "Export session is nil"))
                return
            }

            exportSession.shouldOptimizeForNetworkUse = true
            exportSession.outputFileType = outputFileType
            exportSession.outputURL = outputUrl

            if let startMs = options["startTime"] as? Double, let endMs = options["endTime"] as? Double {
                let startTime = startMs / 1000.0
                let endTime = endMs / 1000.0
                let start = CMTime(seconds: startTime, preferredTimescale: 600)
                let end = CMTime(seconds: endTime, preferredTimescale: 600)
                let duration = CMTimeSubtract(end, start)
                
                exportSession.timeRange = CMTimeRange(start: start, duration: duration)
            }

            let uuid=options["uuid"] as? String ?? ""
            VideoCompressor.compressorExportSessions[uuid] = exportSession

            exportSession.exportAsynchronously {
                VideoCompressor.cleanUpSandboxFiles(for: outputUrl)
                switch exportSession.status {
                case .failed:
                    let error = exportSession.error ?? CompressionError(message: "Video export failed.")
                    errorHandler(error)
                case .cancelled:
                    errorHandler(CompressionError(message: "Video export cancelled."))
                case .completed:
                    completionHandler(outputUrl.absoluteString, options)
                default:
                    errorHandler(CompressionError(message: "Unknown status."))
                }
            }
        }
    }

    static func cleanUpSandboxFiles(for url: URL) {
        let directory = url.deletingLastPathComponent()
        let filename = url.lastPathComponent
        if let files = try? FileManager.default.contentsOfDirectory(atPath: directory.path) {
            for file in files {
                if file.hasPrefix(filename) && file.contains(".sb-") {
                    let fileToDelete = directory.appendingPathComponent(file)
                    try? FileManager.default.removeItem(at: fileToDelete)
                }
            }
        }
    }
}
