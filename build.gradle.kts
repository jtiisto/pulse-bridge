plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover)
}

// Merged coverage across all modules; gated by githooks/pre-push via
// koverVerifyAggregated. Composables and generated code are excluded so the
// metric tracks unit-testable logic (no Compose UI test rig in this project).
kover {
    merge {
        allProjects()
        createVariant("aggregated") {
            add("debug", optional = true)
        }
    }

    reports {
        filters {
            excludes {
                classes(
                    "*BuildConfig",
                    "*_Impl",
                    "*_Impl$*",
                    "*ComposableSingletons*",
                    // Framework glue below: services, workers, receivers,
                    // notification builders, and OS-API wrappers can only run
                    // on a device — the instrumented suite exercises them.
                    // Keeping them out makes the unit-coverage gate sensitive
                    // to real regressions instead of diluted by permanent 0%.
                    // Testable-in-principle classes (stores, monitors, models)
                    // stay IN the metric even while untested.
                    "dev.jtiisto.pulsebridge.MainActivity",
                    "dev.jtiisto.pulsebridge.PulseBridgeApplication",
                    "dev.jtiisto.pulsebridge.core.ble.service.BleCaptureService",
                    "dev.jtiisto.pulsebridge.core.ble.service.BleCaptureService$*",
                    "dev.jtiisto.pulsebridge.core.ble.service.BleCaptureNotification",
                    "dev.jtiisto.pulsebridge.core.ble.polar.PolarSyncService",
                    "dev.jtiisto.pulsebridge.core.ble.polar.PolarSyncService$*",
                    "dev.jtiisto.pulsebridge.core.ble.polar.PolarSyncNotification",
                    "dev.jtiisto.pulsebridge.core.ble.polar.PolarScanWorker",
                    "dev.jtiisto.pulsebridge.core.ble.polar.PolarScanWorker$*",
                    "dev.jtiisto.pulsebridge.core.ble.polar.PolarScanReceiver",
                    "dev.jtiisto.pulsebridge.core.ble.polar.PolarScanReceiver$*",
                    "dev.jtiisto.pulsebridge.core.ble.polar.PolarDeviceDetector",
                    "dev.jtiisto.pulsebridge.core.ble.scanner.BleScanner",
                    "dev.jtiisto.pulsebridge.core.ble.scanner.BleScanner$*",
                    "dev.jtiisto.pulsebridge.core.sync.SyncWorker",
                    "dev.jtiisto.pulsebridge.core.sync.SyncWorker$*",
                    "dev.jtiisto.pulsebridge.core.database.PulseBridgeDatabase",
                    "dev.jtiisto.pulsebridge.core.database.PulseBridgeDatabase$*",
                    "*ModuleKt",
                )
                packages(
                    "dev.jtiisto.pulsebridge.core.ui.theme",
                    "dev.jtiisto.pulsebridge.navigation",
                )
                annotatedBy("androidx.compose.runtime.Composable")
            }
        }
        variant("aggregated") {
            verify {
                rule {
                    // Baseline-derived gate (83.5% line coverage measured
                    // 2026-08-01 after excluding device-only framework glue) —
                    // raise as coverage improves, never lower without a
                    // deliberate decision.
                    minBound(82)
                }
            }
        }
    }
}
