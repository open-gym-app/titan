plugins {
    id("opengym.android.benchmark")
}

android {
    namespace = "com.opengym.benchmark"

    // The app this module measures. It is what makes AGP build one variant of this module per
    // variant of `:app` — hence the single `benchmark` build type on both sides — and what
    // wires `connectedBenchmarkAndroidTest` to install both APKs before it runs.
    //
    // Deliberately not a `dependencies { implementation(project(":app")) }`: the benchmark
    // does not link against the app, it drives it through UiAutomator from another process.
    // `checkArchitecture` sees no edge here for exactly that reason — see docs/ARCHITECTURE.md.
    targetProjectPath = ":app"
}
