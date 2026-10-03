package dev.nytrix.nyaddons.core

import dev.nytrix.nyaddons.NyAddOns
import net.minecraft.client.gui.screens.Screen
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a mistake in one feature from taking the game down with it.
 *
 * Every callback the mod hands to the game (tick, chat, HUD, world, screen, key and command callbacks) runs through a [Site]:
 * `site { ... }`. A [Site] is named after the feature that owns it, and sites with the same name share their counters. An
 * exception is caught, written to the log and to `config/nyaddons/errors.log` (first time at once, then at most every 30
 * seconds per site) and the game carries on. A site that fails [MAX_FAILURES] times within a minute is turned off for the rest
 * of the session and the player is told once, so a feature that is broken on someone's setup stops instead of failing every
 * frame. On the happy path a guard is a flag check and a try block: it allocates nothing.
 */
object Safe {

    private const val WINDOW_NANOS = 60_000_000_000L
    private const val LOG_GAP_NANOS = 30_000_000_000L
    private const val MAX_FAILURES = 5
    private const val LOG_CAP_BYTES = 200 * 1024L
    private const val MAX_TRACE_LINES = 60

    private val sites = ConcurrentHashMap<String, Site>()
    private val fileLock = Any()
    private var sessionStarted = false

    /** The guard for the feature called [name]. The same name always gives the same site. */
    fun site(name: String): Site = sites.computeIfAbsent(name, ::Site)

    /** A readable feature name from a listener or object: `HoneycombTreeTimer$init$1` becomes `Honeycomb Tree Timer`. */
    fun nameOf(owner: Any): String {
        val plain = owner.javaClass.name.substringAfterLast('.').substringBefore('$').removeSuffix("Impl")
        return plain.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").ifBlank { "NyAddOns" }
    }

    /**
     * Runs [block] on a new daemon thread. Whatever it throws is logged and ends only that thread, so the game never
     * sees it. Put anything that must happen however the work ends (clearing a "loading" flag) in a `finally` inside [block].
     */
    fun background(name: String, block: () -> Unit): Thread {
        val site = site(name)
        return Thread({
            try {
                block()
            } catch (t: Throwable) {
                site.record(t, counts = false)
            }
        }, "NyAddOns $name").apply {
            isDaemon = true
            start()
        }
    }

    class Site internal constructor(val name: String) {

        /** True once the site failed too often; its guarded code no longer runs. */
        @Volatile
        var off = false
            private set

        private var windowStart = 0L
        private var failures = 0
        private var lastLogged = 0L
        private var everLogged = false

        /** Runs [block] unless this site is off; anything it throws is recorded instead of reaching the game. */
        inline operator fun invoke(block: () -> Unit) {
            if (off) return
            try {
                block()
            } catch (t: Throwable) {
                fail(t)
            }
        }

        /** Like [invoke] for callbacks that answer something; [default] is the answer when the site is off or [block] fails. */
        inline fun <T> call(default: T, block: () -> T): T {
            if (off) return default
            return try {
                block()
            } catch (t: Throwable) {
                fail(t)
                default
            }
        }

        /** For a screen's callbacks: a failure closes [owner] on the next tick instead of letting it fail every frame. */
        inline fun screen(owner: Screen, block: () -> Unit) {
            if (off) {
                NyAddOns.closeScreen(owner)
                return
            }
            try {
                block()
            } catch (t: Throwable) {
                fail(t)
                NyAddOns.closeScreen(owner)
            }
        }

        /** [screen] for callbacks that answer something, like a click that was or was not used. */
        inline fun <T> screenCall(owner: Screen, default: T, block: () -> T): T {
            if (off) {
                NyAddOns.closeScreen(owner)
                return default
            }
            return try {
                block()
            } catch (t: Throwable) {
                fail(t)
                NyAddOns.closeScreen(owner)
                default
            }
        }

        /** Records [t]. Only running out of memory is passed on: nothing can be saved then. */
        fun fail(t: Throwable) {
            if (t is OutOfMemoryError) throw t
            record(t, counts = true)
        }

        internal fun record(t: Throwable, counts: Boolean) {
            val now = System.nanoTime()
            var log: Boolean
            var turnedOff = false
            synchronized(this) {
                if (counts) {
                    if (failures == 0 || now - windowStart > WINDOW_NANOS) {
                        windowStart = now
                        failures = 0
                    }
                    failures++
                    if (failures >= MAX_FAILURES && !off) {
                        off = true
                        turnedOff = true
                    }
                }
                log = !everLogged || now - lastLogged >= LOG_GAP_NANOS || turnedOff
                if (log) {
                    everLogged = true
                    lastLogged = now
                }
            }
            if (log) writeLog(name, t, turnedOff)
            if (turnedOff) {
                try {
                    ChatUtils.chat("§c$name had an error and was turned off; details in config/nyaddons/errors.log")
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun writeLog(name: String, t: Throwable, turnedOff: Boolean) {
        try {
            NyAddOns.logger.error("$name failed${if (turnedOff) " and was turned off" else ""}", t)
        } catch (_: Throwable) {
        }
        synchronized(fileLock) {
            try {
                val directory = NyAddOns.directory
                directory.mkdirs()
                val file = File(directory, "errors.log")
                if (file.length() > LOG_CAP_BYTES) {
                    Files.move(file.toPath(), File(directory, "errors.old.log").toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                val text = StringBuilder()
                if (!sessionStarted) {
                    sessionStarted = true
                    text.append("=== NyAddOns ").append(NyAddOns.VERSION).append(", ").append(Instant.now()).append(" ===\n")
                }
                text.append(Instant.now()).append("  ").append(name).append(if (turnedOff) "  (turned off)" else "").append('\n')
                t.stackTraceToString().lineSequence().take(MAX_TRACE_LINES).forEach { text.append(it).append('\n') }
                Files.writeString(file.toPath(), text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            } catch (_: Throwable) {
                // Logging must never be the thing that fails.
            }
        }
    }
}
