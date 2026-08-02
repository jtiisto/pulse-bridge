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
                )
                annotatedBy("androidx.compose.runtime.Composable")
            }
        }
        variant("aggregated") {
            verify {
                rule {
                    // Baseline-derived gate (55.6% line coverage measured
                    // 2026-08-01) — raise as coverage improves, never lower
                    // without a deliberate decision.
                    minBound(54)
                }
            }
        }
    }
}
