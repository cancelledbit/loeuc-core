package example.hud

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The only screen: Bluetooth permissions. The service cannot request runtime permissions
 * itself, and without them it cannot reach the HUD. Everything else - enabling the extension,
 * its settings, its journal - lives in LoEUC, under Link and sensors -> Extensions.
 */
class SetupActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 16f }
        val grant = Button(this).apply {
            text = "Grant Bluetooth access"
            setOnClickListener { requestPermissions(HudPermissions.required, RequestCode) }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
                addView(status)
                addView(grant)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        status.text = if (HudPermissions.granted(this)) {
            "Bluetooth access granted. Now open LoEUC -> Link and sensors -> Extensions and allow HUD bridge."
        } else {
            "The bridge needs Bluetooth access to reach your HUD."
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onResume()
    }

    private companion object {
        const val RequestCode = 1
    }
}
