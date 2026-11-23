package com.local.tvlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Reads only exposed, identified sources, at ordinary app privileges and on a worker thread. */
final class ProjectorTemperature {
    // Linux thermal_zone*/temp uses millidegrees Celsius. Only known silicon types qualify.
    private static final Pattern SOC_TYPE = Pattern.compile("soc(?:[0-9]{1,2})?(?:-(?:thermal|therm|temp|temperature))?");
    private static final Pattern CPU_TYPE = Pattern.compile("cpu(?:[0-9]{1,2}|-(?:big|little|mid|prime|gold|silver))?"
            + "(?:-(?:thermal|therm|temp|temperature))?|cpu-[0-9]{1,2}-[0-9]{1,2}-usr|x86-pkg-temp");
    private static final int ZONE_LIMIT = 32, CPU_LIMIT = 8, SOC_LIMIT = 4;
    private static final Reading MISSING = new Reading("— °C", "Показания температуры недоступны.");

    private ProjectorTemperature() {
    }

    static final class Reading {
        private final String text, detail;

        private Reading(String text, String detail) {
            this.text = text;
            this.detail = detail;
        }

        String displayText() { return text; }
        String description() { return detail; }
    }

    static Reading read(Context context) {
        Reading hardware = readHardware(readLedProperty(), new File("/sys/class/thermal"));
        if (hardware != null) return hardware;
        try {
            // Query the sticky broadcast once. No receiver or listener remains registered.
            Intent info = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (info != null && info.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
                Reading battery = batteryReading(info.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false),
                        info.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE));
                if (battery != null) return battery;
            }
        } catch (RuntimeException ignored) {
            // A restricted build may not expose battery status to apps.
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                PowerManager power = context.getSystemService(PowerManager.class);
                if (power != null) return statusReading(power.getCurrentThermalStatus());
            } catch (RuntimeException ignored) {
                // The thermal service may be absent on a TV firmware.
            }
        }
        return MISSING;
    }

    static Reading readHardware(String ledProperty, File zones) {
        Integer led = integer(ledProperty);
        if (led != null && led != -1 && led >= -40 && led <= 150) {
            return degrees("LED", "Температура светодиода", led);
        }

        try {
            if (!zones.isDirectory() || !zones.canExecute()) return null;
        } catch (SecurityException denied) {
            return null;
        }
        Integer hottestCpu = null;
        int cpuCount = 0, socCount = 0;
        for (int index = 0; index < ZONE_LIMIT; index++) {
            File zone = new File(zones, "thermal_zone" + index);
            String type = smallFile(new File(zone, "type"));
            if (type == null) continue;
            type = type.toLowerCase(Locale.ROOT).replace('_', '-');
            boolean soc = SOC_TYPE.matcher(type).matches();
            if (soc) {
                if (socCount >= SOC_LIMIT) continue;
                socCount++;
            } else {
                if (cpuCount >= CPU_LIMIT || !CPU_TYPE.matcher(type).matches()) continue;
                cpuCount++;
            }
            Integer value = millidegrees(smallFile(new File(zone, "temp")));
            if (value == null) continue;
            if (soc) return degrees("SoC", "Температура системного чипа", value);
            if (hottestCpu == null || value > hottestCpu) hottestCpu = value;
        }
        return hottestCpu == null ? null
                : degrees("CPU", "Температура процессора по самому тёплому из прочитанных датчиков", hottestCpu);
    }

    private static Reading degrees(String source, String label, int value) {
        String number = value + " °C";
        return new Reading(source + " " + number, label + " " + number + ".");
    }

    private static Integer millidegrees(String raw) {
        Integer value = integer(raw);
        // Near zero we cannot distinguish an unscaled driver or an unset sample from real cold.
        if (value == null || value < -40000 || value > 150000 || (value > -1000 && value < 1000)) return null;
        return Math.round(value / 1000f);
    }

    static Reading batteryReading(boolean present, int tenths) {
        // The normal app-facing unit is 0.1 °C. Some TV health HALs supply a permanent zero.
        if (!present || tenths < -400 || tenths > 1000 || tenths == 0 || tenths == -1) return null;
        return degrees("АКБ", "Температура аккумулятора", Math.round(tenths / 10f));
    }

    static Reading statusReading(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_MODERATE:
                return new Reading("Нагрев", "Android сообщает об умеренном нагреве устройства.");
            case PowerManager.THERMAL_STATUS_SEVERE:
                return new Reading("Сильный нагрев", "Android сообщает о сильном нагреве устройства.");
            case PowerManager.THERMAL_STATUS_CRITICAL:
            case PowerManager.THERMAL_STATUS_EMERGENCY:
            case PowerManager.THERMAL_STATUS_SHUTDOWN:
                return new Reading("Перегрев", "Android сообщает о критическом нагреве устройства.");
            default:
                return MISSING;
        }
    }

    private static Integer integer(String raw) {
        try {
            return raw == null ? null : Integer.valueOf(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String smallFile(File file) {
        try (InputStream input = new FileInputStream(file)) {
            return smallText(input);
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    private static String smallText(InputStream input) throws IOException {
        byte[] bytes = new byte[65];
        int used = 0;
        while (used < bytes.length) {
            int n = input.read(bytes, used, bytes.length - used);
            if (n <= 0) break;
            used += n;
        }
        return used == 0 || used == bytes.length ? null
                : new String(bytes, 0, used, StandardCharsets.US_ASCII).trim();
    }

    private static String readLedProperty() {
        Process proc = null;
        try {
            // ZVT's status bar and Coolux ImageUtils read this as integer LED degrees Celsius.
            // The CPU and TEC have separate properties.
            proc = new ProcessBuilder("/system/bin/getprop", "vendor.zvt.temp")
                    .redirectErrorStream(true).start();
            try (InputStream property = proc.getInputStream()) {
                // Timed Process.waitFor is unavailable on Android 7.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (true) {
                    try {
                        if (proc.exitValue() != 0) return null;
                        break;
                    } catch (IllegalThreadStateException running) {
                        if (System.nanoTime() - deadline >= 0) return null;
                        Thread.sleep(25);
                    }
                }
                return smallText(property);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | SecurityException ignored) {
            // Vendor properties are not exposed to ordinary apps on every build.
        } finally {
            if (proc != null) proc.destroy();
        }
        return null;
    }

}
