package ai.owlwhisp.mobile
internal object NativeEngines {
    init { System.loadLibrary("owlwhisp") }
    external fun transcribe(kind: String, directory: String, samples: FloatArray): String
}
