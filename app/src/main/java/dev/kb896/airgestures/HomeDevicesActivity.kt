package dev.kb896.airgestures

import android.os.Bundle
import android.widget.LinearLayout

/**
 * Home devices: the lamps, speakers… a Send to automation can be for. Add, rename (every
 * gesture for it sends the new name) and delete (they send without a device).
 */
class HomeDevicesActivity : GestureScreen() {
    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rows = column(gap = 4f)
        show(pageColumn().apply {
            addView(note(
                "Send to automation can say which of these a gesture is for: the broadcast carries device with its name, " +
                    "for your Tasker, MacroDroid or Home Assistant rules. " +
                    "Any app on the phone can receive this broadcast, so device names are visible to them.",
            ))
            addView(card(null, rows))
        }, title = "Home devices")
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun render() {
        rows.removeAllViews()
        val devices = Profiles.devices(this).devices
        if (devices.isEmpty()) rows.addView(note("None yet: add one here, or right after picking Send to automation."))
        for (device in devices) {
            rows.addView(navRow(R.drawable.ic_house, device.name, detail = uses(Profiles.deviceUses(this, device.id))) { menu(device) })
        }
        rows.addView(row().apply { addView(pill("New device", R.drawable.ic_house) { newDevice { render() } }.first) })
    }

    private fun uses(n: Int) = when (n) {
        0 -> "Not used"
        1 -> "1 gesture"
        else -> "$n gestures"
    }

    private fun menu(device: HomeDevice) {
        val options = listOf(Option("Rename", false, R.drawable.ic_pencil), Option("Delete", false, R.drawable.ic_x))
        choose(device.name, options, heading = "Home device") { i -> if (i == 0) rename(device) else delete(device) }
    }

    /** The gestures keep the device, so they send the new name. */
    private fun rename(device: HomeDevice) = ask(
        "Rename",
        device.name,
        "Save",
        note = "Home Assistant, Tasker and MacroDroid rules match on the name: update them too.",
    ) { typed ->
        val new = HomeDevices.clean(typed)
        when {
            new == null -> sheet("A device needs a name", note("Type a name for ${device.name}."), "OK", cancel = false) { true }
            Profiles.devices(this).devices.any { it.id != device.id && it.name.equals(new, ignoreCase = true) } ->
                sheet("That name is taken", note("Another device is called $new: pick another name."), "OK", cancel = false) { true }
            else -> {
                Profiles.updateDevices(applicationContext) { it.renamed(device.id, new) }
                render()
            }
        }
    }

    /** What sent for it sends without a device from now on. */
    private fun delete(device: HomeDevice) {
        val n = Profiles.deviceUses(this, device.id)
        val body = when (n) {
            0 -> "No gesture sends for it."
            1 -> "Used by 1 gesture; it'll send without a device."
            else -> "Used by $n gestures; they'll send without a device."
        }
        confirm("Delete ${device.name}?", body, "Delete") {
            Profiles.deleteDevice(applicationContext, device.id)
            if (n > 0) BandService.instance?.remap()
            render()
        }
    }
}
