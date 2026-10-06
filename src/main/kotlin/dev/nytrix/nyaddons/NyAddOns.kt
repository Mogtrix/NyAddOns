package dev.nytrix.nyaddons

import dev.nytrix.nyaddons.config.NyConfig
import dev.nytrix.nyaddons.core.ChatUtils
import dev.nytrix.nyaddons.core.MenuDump
import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.core.Safe
import dev.nytrix.nyaddons.core.SkyBlockData
import dev.nytrix.nyaddons.core.Storage
import dev.nytrix.nyaddons.features.Features
import dev.nytrix.nyaddons.gui.ConfigTheme
import dev.nytrix.nyaddons.gui.OverlayManager
import dev.nytrix.nyaddons.gui.PositionEditorScreen
import io.github.notenoughupdates.moulconfig.managed.ManagedConfig
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

object NyAddOns : ClientModInitializer {

    const val MOD_ID = "nyaddons"
    private const val SAVE_INTERVAL_TICKS = 100

    val logger: Logger = LoggerFactory.getLogger("NyAddOns")

    val VERSION: String by lazy {
        FabricLoader.getInstance().getModContainer(MOD_ID).map { it.metadata.version.friendlyString }.orElse("dev")
    }

    /** Where the config, saved data and downloaded lists live. */
    lateinit var directory: File
        private set

    private lateinit var managedConfig: ManagedConfig<NyConfig>
    val config: NyConfig get() = managedConfig.instance

    // Screens are opened a tick later, otherwise closing the chat box after a command closes them again.
    private var pendingScreen: (() -> Unit)? = null
    private var ticks = 0

    override fun onInitializeClient() {
        directory = File(FabricLoader.getInstance().configDir.toFile(), MOD_ID)
        managedConfig = ManagedConfig.create(File(directory, "config.json"), NyConfig::class.java)
        Storage.load(File(directory, "data.json"))

        // A feature that fails to start is left out; the rest of the mod and the game carry on.
        core { ConfigTheme.init() }
        core { OverlayManager.init() }
        core { MenuDump.init() }
        for (feature in Features.all) {
            val site = Safe.site(Safe.nameOf(feature))
            site { feature.init() }
        }
        registerEvents()
        registerCommands()
    }

    /** Guards the mod's own plumbing (the tick loop, saving, opening screens). */
    private val core = Safe.site("NyAddOns")

    fun openConfig() {
        pendingScreen = { managedConfig.openConfigGui() }
    }

    /** Closes [screen] on the next tick if it is still open; used when a screen failed and cannot keep drawing. */
    fun closeScreen(screen: Screen) {
        pendingScreen = {
            val mc = Minecraft.getInstance()
            if (mc.screen === screen) mc.setScreen(null)
        }
    }

    fun openScreen(screen: () -> Screen) {
        pendingScreen = { Minecraft.getInstance().setScreen(screen()) }
    }

    fun saveConfig() {
        core { managedConfig.saveToFile() }
    }

    private fun registerEvents() {
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            core {
                pendingScreen?.let {
                    pendingScreen = null
                    it()
                }
                ticks++
                OverlayManager.tick()
            }
            val playing = SkyBlockData.onSkyBlock && mc.player != null
            if (playing) NyEvents.tick.forEach { it() }
            if (ticks % 20 == 0) {
                core { SkyBlockData.update() }
                if (playing) NyEvents.second.forEach { it() }
            }
            if (ticks % SAVE_INTERVAL_TICKS == 0) core { Storage.saveIfDirty() }
        }

        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            core {
                if (!overlay && SkyBlockData.onSkyBlock) {
                    val text = ChatUtils.stripColor(message.string)
                    NyEvents.chat.forEach { it(text) }
                }
            }
        }

        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            if (SkyBlockData.onSkyBlock) NyEvents.worldRender.forEach { it(context) }
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register {
            saveConfig()
            core { Storage.saveIfDirty(wait = true) }
        }
    }

    private fun registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            core {
                for (name in listOf("ny", "nyaddons", "Ny", "NyAddOns")) {
                    val root = ClientCommands.literal(name)
                        .executes { core { openConfig() }; 1 }
                        .then(ClientCommands.literal("gui").executes { core { PositionEditorScreen.open() }; 1 })
                        .then(ClientCommands.literal("reset").executes { core { resetTimers() }; 1 })
                        .then(ClientCommands.literal("help").executes { core { showHelp() }; 1 })
                    Features.all.flatMap { it.subcommands() }.forEach { root.then(it) }
                    dispatcher.register(root)
                }
                Features.all.flatMap { it.commands() }.forEach { dispatcher.register(it) }
            }
        }
    }

    private fun resetTimers() {
        Storage.resetTimers()
        OverlayManager.invalidate()
        ChatUtils.chat("Cleared all timers.")
    }

    private fun showHelp() {
        ChatUtils.chat("§6/ny §7- §eopen the config")
        ChatUtils.chat("§6/ny gui §7- §emove and resize overlays")
        ChatUtils.chat("§6/ny reset §7- §eclear all timers")
        ChatUtils.chat("§6/gh §7- §eopen the Greenhouse window")
    }
}
