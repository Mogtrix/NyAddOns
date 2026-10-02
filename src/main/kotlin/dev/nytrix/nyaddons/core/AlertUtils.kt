package dev.nytrix.nyaddons.core

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

object AlertUtils {

    fun playSound() {
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.5f))
    }

    fun showTitle(title: String) {
        val gui = Minecraft.getInstance().gui
        gui.setTimes(5, 50, 10)
        gui.setTitle(Component.literal(title))
    }

    /** The shared "something is ready" alert, honouring a feature's chat, sound and title toggles. */
    fun ready(chat: Boolean, sound: Boolean, title: Boolean, chatText: String, titleText: String) {
        if (chat) ChatUtils.chat(chatText)
        if (sound) playSound()
        if (title) showTitle(titleText)
    }
}
