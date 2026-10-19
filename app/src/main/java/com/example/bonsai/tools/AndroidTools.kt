package com.example.bonsai.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.AlarmClock

/**
 * Herramientas de demostración sobre APIs nativas de Android.
 *
 * setAlarm  → AlarmClock.ACTION_SET_ALARM (sin UI, el reloj del sistema la crea)
 * openApp   → PackageManager.getLaunchIntentForPackage + startActivity
 * getBatteryLevel → sticky broadcast ACTION_BATTERY_CHANGED
 */
fun buildAndroidTools(context: Context): ToolRegistry = ToolRegistry()
    .register(setAlarmTool(context))
    .register(openAppTool(context))
    .register(batteryTool(context))

// ---------------------------------------------------------------------------

private fun setAlarmTool(context: Context) = Tool(
    name = "setAlarm",
    description = "Set an alarm in the clock app (24-hour time).",
    parameters = mapOf(
        "hour" to ToolParam("integer", "Hour 0-23"),
        "minute" to ToolParam("integer", "Minute 0-59"),
        "label" to ToolParam("string", "Alarm label", required = false),
    ),
) { args ->
    val hour = args.getInt("hour")
    val minute = args.getInt("minute")
    require(hour in 0..23 && minute in 0..59) { "Hora inválida: $hour:$minute" }

    val label = args.optString("label").takeIf { it.isNotBlank() }
    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, hour)
        putExtra(AlarmClock.EXTRA_MINUTES, minute)
        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)

    buildString {
        append("Alarma creada para las %02d:%02d".format(hour, minute))
        if (label != null) append(" con etiqueta \"$label\"")
    }
}

// ---------------------------------------------------------------------------

private fun openAppTool(context: Context) = Tool(
    name = "openApp",
    description = "Open an installed app by package name.",
    parameters = mapOf(
        "packageName" to ToolParam("string", "Package name, e.g. com.whatsapp"),
    ),
) { args ->
    val packageName = args.getString("packageName").trim()
    require(packageName.isNotEmpty()) { "packageName vacío" }

    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        ?: error("No hay ninguna app instalada con el paquete $packageName")

    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(launchIntent)
    "App abierta: $packageName"
}

// ---------------------------------------------------------------------------

private fun batteryTool(context: Context) = Tool(
    name = "getBatteryLevel",
    description = "Get battery percentage and charging state.",
    parameters = emptyMap(),
) {
    // Sticky broadcast: se puede consultar sin registrar Receiver (API 24+).
    val status = context.registerReceiver(
        null,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
    ) ?: error("No se pudo leer el estado de la batería")

    val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
    val charging = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        .let {
            it == BatteryManager.BATTERY_STATUS_CHARGING ||
                it == BatteryManager.BATTERY_STATUS_FULL
        }

    buildString {
        append("Batería al $percent%")
        append(if (charging) " (cargando)" else " (descargando)")
    }
}
