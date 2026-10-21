package dev.kb896.airgestures

/**
 * What the widget shows, from the service's state: the chip (OFF, DISCONNECTED, or ON with
 * the band's charge after a dot) and the colour of the chip and of each button: brand green
 * on, coral off, grey while the band is being looked for.
 */
data class WidgetState(val chip: String, val chipTone: Tone, val power: Tone, val hand: Tone) {
    enum class Tone { LIVE, STOP, GREY }

    companion object {
        fun of(running: Boolean, connected: Boolean, paused: Boolean, battery: Int?): WidgetState {
            if (!running) return WidgetState("OFF", Tone.STOP, Tone.STOP, Tone.STOP)
            val hand = if (paused) Tone.STOP else Tone.LIVE
            if (!connected) return WidgetState("DISCONNECTED", Tone.GREY, Tone.LIVE, hand)
            val chip = listOfNotNull("ON", battery?.let { "$it %" }).joinToString(" · ")
            return WidgetState(chip, Tone.LIVE, Tone.LIVE, hand)
        }
    }
}
