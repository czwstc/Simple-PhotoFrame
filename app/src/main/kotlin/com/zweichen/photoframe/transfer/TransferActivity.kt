package com.zweichen.photoframe.transfer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zweichen.photoframe.BuildConfig
import com.zweichen.photoframe.R
import com.zweichen.photoframe.databinding.ActivityTransferBinding
import com.zweichen.photoframe.frameApp
import com.zweichen.photoframe.util.RootShell
import com.zweichen.photoframe.util.enterImmersive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows the Wi‑Fi and page QR codes while the hotspot and upload server are running. */
class TransferActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTransferBinding
    private val session get() = frameApp.transfer
    private var shownInfo: TransferSession.Info? = null
    private var sessionSeen = false

    private val hotspotPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        session.start(allowLocalOnly = result.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterImmersive()

        binding.doneButton.setOnClickListener { finish() }
        binding.retryButton.setOnClickListener {
            sessionSeen = false
            session.stop()
            begin()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { session.state.collect(::render) }
                launch { session.received.collect { renderReceived() } }
                launch { session.finishRequests.collect { finish() } }
            }
        }

        if (!session.isActive) begin()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onStop() {
        super.onStop()
        // leaving the transfer screen (power button, home) ends the transfer; the hotspot shouldn't linger unseen
        if (!isChangingConfigurations && !isFinishing) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) session.stop()
    }

    private fun begin() {
        lifecycleScope.launch {
            val root = withContext(Dispatchers.IO) { RootShell.isAvailable() }
            val debugTimeout = intent.getIntExtra(EXTRA_IDLE_TIMEOUT_SEC, 0).takeIf { BuildConfig.DEBUG && it > 0 }
            when {
                BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_SKIP_HOTSPOT, false) ->
                    session.start(allowLocalOnly = false, useHotspot = false)
                root && debugTimeout != null -> session.start(allowLocalOnly = false, idleTimeoutMs = debugTimeout * 1000L)
                root -> session.start(allowLocalOnly = false)
                hasHotspotPermissions() -> session.start(allowLocalOnly = true)
                else -> permissionRequest.launch(hotspotPermissions)
            }
        }
    }

    private fun hasHotspotPermissions() = hotspotPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun render(state: TransferSession.State) {
        if (state !is TransferSession.State.Idle) {
            sessionSeen = true
        } else if (sessionSeen) {
            // the session ended on its own (idle timeout, hotspot gone)
            finish()
            return
        }
        when (state) {
            TransferSession.State.Idle, TransferSession.State.Starting -> showProgress(getString(R.string.transfer_starting), spinner = true)
            is TransferSession.State.Failed -> showProgress(getString(R.string.transfer_failed, state.message), spinner = false)
            is TransferSession.State.Ready -> showInfo(state.info)
        }
        renderReceived()
    }

    private fun showProgress(message: String, spinner: Boolean) {
        shownInfo = null
        binding.cards.visibility = View.INVISIBLE
        binding.progress.isVisible = true
        binding.progressSpinner.isVisible = spinner
        binding.progressText.text = message
        binding.retryButton.isVisible = !spinner
        binding.tipText.isVisible = false
    }

    private fun showInfo(info: TransferSession.Info) {
        binding.progress.isVisible = false
        binding.cards.visibility = View.VISIBLE
        binding.tipText.isVisible = true
        if (info == shownInfo) return
        shownInfo = info

        if (info.sameNetwork) {
            binding.wifiStepTitle.setText(R.string.step_same_wifi)
            binding.wifiQr.isVisible = false
            binding.wifiName.text = info.ssid.orEmpty()
            binding.wifiName.isVisible = info.ssid != null
            binding.wifiPassword.isVisible = false
            binding.wifiHint.setText(R.string.same_wifi_hint)
        } else {
            val ssid = info.ssid.orEmpty()
            binding.wifiStepTitle.setText(R.string.step_wifi)
            binding.wifiQr.isVisible = true
            binding.wifiQr.setImageDrawable(QrCode.drawable(resources, QrCode.wifiPayload(ssid, info.password)))
            binding.wifiName.text = ssid
            binding.wifiName.isVisible = true
            binding.wifiPassword.isVisible = true
            binding.wifiPassword.text = info.password?.let { getString(R.string.wifi_password, it) } ?: getString(R.string.wifi_no_password)
            binding.wifiHint.setText(R.string.wifi_scan_hint)
        }
        binding.urlQr.setImageDrawable(QrCode.drawable(resources, info.url))
        binding.urlText.text = info.url
    }

    private fun renderReceived() {
        val count = session.received.value
        binding.statusText.text = when {
            session.state.value !is TransferSession.State.Ready -> ""
            count == 0 -> getString(R.string.transfer_ready)
            else -> getString(R.string.transfer_received, count)
        }
    }

    companion object {
        /** Debug builds only: serve on the current network instead of opening a hotspot, so the frame stays reachable over adb. */
        const val EXTRA_SKIP_HOTSPOT = "skip_hotspot"

        /** Debug builds only: end the session after this many idle seconds instead of 20 minutes. */
        const val EXTRA_IDLE_TIMEOUT_SEC = "idle_timeout_sec"
    }
}
