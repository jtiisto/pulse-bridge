package dev.jtiisto.wellnesssync.core.common

import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.UUID

fun today(): LocalDate =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

fun LocalDate.toIsoString(): String = toString()

fun generateId(): String = UUID.randomUUID().toString()

fun generateShortId(): String = UUID.randomUUID().toString().take(8)

fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
