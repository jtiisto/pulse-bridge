package dev.jtiisto.wellnesssync.core.ble.di

import dev.jtiisto.wellnesssync.core.ble.polar.PolarSyncServiceState
import dev.jtiisto.wellnesssync.core.ble.service.BleCaptureServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication

/**
 * Regression test for the Koin type-erasure collision: two unqualified
 * MutableStateFlow singles register under the same raw class key, so the
 * second silently replaces the first and every consumer receives the wrong
 * flow instance.
 */
class BleModuleTest {

    @Test
    fun `capture and polar state flows resolve to distinct, correctly-typed instances`() {
        val koin = koinApplication { modules(bleModule) }.koin

        val captureFlow = koin.get<MutableStateFlow<*>>(bleCaptureStateQualifier)
        val polarFlow = koin.get<MutableStateFlow<*>>(polarSyncStateQualifier)

        assertNotSame(captureFlow, polarFlow)
        assertInstanceOf(BleCaptureServiceState::class.java, captureFlow.value)
        assertInstanceOf(PolarSyncServiceState::class.java, polarFlow.value)
    }

    @Test
    fun `no unqualified MutableStateFlow definition exists`() {
        val koin = koinApplication { modules(bleModule) }.koin

        assertThrows(NoDefinitionFoundException::class.java) {
            koin.get<MutableStateFlow<*>>()
        }
    }
}
