import Foundation

public enum RecordingPolicy {
    public static let sampleRate = 16_000
    public static let chunkSamples = sampleRate * 30
    public static let limits = [0, 60, 300, 900]
    public static func label(_ seconds: Int) -> String {
        seconds == 0 ? "No time limit" : (seconds == 60 ? "1 minute" : "\(seconds / 60) minutes")
    }
}
