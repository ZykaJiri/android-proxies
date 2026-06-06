package com.truthshield.androidproxies

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("config", Context.MODE_PRIVATE)

    var sshHost: String
        get() = prefs.getString(K_HOST, "") ?: ""
        set(v) { prefs.edit().putString(K_HOST, v).apply() }

    var sshPort: Int
        get() = prefs.getInt(K_PORT, 22)
        set(v) { prefs.edit().putInt(K_PORT, v).apply() }

    var sshUser: String
        get() = prefs.getString(K_USER, "") ?: ""
        set(v) { prefs.edit().putString(K_USER, v).apply() }

    var privateKeyPem: String
        get() = prefs.getString(K_KEY, "") ?: ""
        set(v) { prefs.edit().putString(K_KEY, v).apply() }

    var password: String
        get() = prefs.getString(K_PASS, "") ?: ""
        set(v) { prefs.edit().putString(K_PASS, v).apply() }

    var remoteBindHost: String
        get() = prefs.getString(K_RHOST, "127.0.0.1") ?: "127.0.0.1"
        set(v) { prefs.edit().putString(K_RHOST, v).apply() }

    var remoteBindPort: Int
        get() = prefs.getInt(K_RPORT, 8080)
        set(v) { prefs.edit().putInt(K_RPORT, v).apply() }

    var localProxyPort: Int
        get() = prefs.getInt(K_LPORT, 8118)
        set(v) { prefs.edit().putInt(K_LPORT, v).apply() }

    var autoStart: Boolean
        get() = prefs.getBoolean(K_AUTOSTART, false)
        set(v) { prefs.edit().putBoolean(K_AUTOSTART, v).apply() }

    var killStale: Boolean
        get() = prefs.getBoolean(K_KILLSTALE, false)
        set(v) { prefs.edit().putBoolean(K_KILLSTALE, v).apply() }

    var cycleEnabled: Boolean
        get() = prefs.getBoolean(K_CYCLE_ON, false)
        set(v) { prefs.edit().putBoolean(K_CYCLE_ON, v).apply() }

    var cycleIntervalSec: Int
        get() = prefs.getInt(K_CYCLE_INTERVAL, 600)
        set(v) { prefs.edit().putInt(K_CYCLE_INTERVAL, v).apply() }

    var airplaneOnSec: Int
        get() = prefs.getInt(K_AIRPLANE_DUR, 5)
        set(v) { prefs.edit().putInt(K_AIRPLANE_DUR, v).apply() }

    var useSshTunnel: Boolean
        get() = prefs.getBoolean(K_USE_SSH, true)
        set(v) { prefs.edit().putBoolean(K_USE_SSH, v).apply() }

    var tunnelCount: Int
        get() = prefs.getInt(K_TUNNELS, 1)
        set(v) { prefs.edit().putInt(K_TUNNELS, v).apply() }

    var autoReboot: Boolean
        get() = prefs.getBoolean(K_AUTO_REBOOT, false)
        set(v) { prefs.edit().putBoolean(K_AUTO_REBOOT, v).apply() }

    var rebootIntervalSec: Int
        get() = prefs.getInt(K_REBOOT_INTERVAL, 21_600)
        set(v) { prefs.edit().putInt(K_REBOOT_INTERVAL, v).apply() }

    companion object {
        private const val K_HOST = "ssh_host"
        private const val K_PORT = "ssh_port"
        private const val K_USER = "ssh_user"
        private const val K_KEY = "ssh_key"
        private const val K_PASS = "ssh_password"
        private const val K_RHOST = "remote_bind_host"
        private const val K_RPORT = "remote_bind_port"
        private const val K_LPORT = "local_proxy_port"
        private const val K_AUTOSTART = "auto_start"
        private const val K_KILLSTALE = "kill_stale_port"
        private const val K_CYCLE_ON = "cycle_enabled"
        private const val K_CYCLE_INTERVAL = "cycle_interval_sec"
        private const val K_AIRPLANE_DUR = "airplane_on_sec"
        private const val K_USE_SSH = "use_ssh_tunnel"
        private const val K_TUNNELS = "tunnel_count"
        private const val K_AUTO_REBOOT = "auto_reboot"
        private const val K_REBOOT_INTERVAL = "reboot_interval_sec"
    }
}
