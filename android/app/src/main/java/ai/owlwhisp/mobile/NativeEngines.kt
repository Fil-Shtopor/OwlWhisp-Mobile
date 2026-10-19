package ai.owlwhisp.mobile
internal object NativeEngines {
    init {
        // Apply runtime privacy flags before native library constructors can run.
        android.system.Os.setenv("NEEDLE_TELEMETRY", "0", true)
        android.system.Os.setenv("DO_NOT_TRACK", "1", true)
        System.loadLibrary("owlwhisp")
    }
    external fun transcribe(kind: String, directory: String, samples: FloatArray): String
}
