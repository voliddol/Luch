package com.local.tvlauncher;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Plain JVM tests with android.jar on the classpath; the app build reads src/ only. */
public final class ProjectorTemperatureTest {
    private static int checks;

    private static void expect(String label, String expected, ProjectorTemperature.Reading actual) {
        String value = actual == null ? null : actual.displayText();
        if (!(expected == null ? value == null : expected.equals(value))) {
            throw new AssertionError(label + ": expected " + expected + ", got " + value);
        }
        if (actual != null && actual.description().trim().isEmpty()) throw new AssertionError(label);
        checks++;
    }

    private static void zone(Path root, int n, String type, String value) throws Exception {
        Path dir = Files.createDirectories(root.resolve("thermal_zone" + n));
        Files.write(dir.resolve("type"), type.getBytes(StandardCharsets.US_ASCII));
        if (value != null) Files.write(dir.resolve("temp"), value.getBytes(StandardCharsets.US_ASCII));
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("luch-thermal-fixture-");
        File file = root.toFile();
        try {
            expect("missing tree", null, ProjectorTemperature.readHardware(null, new File(file, "missing")));
            zone(root, 0, "gpu-thermal", "72000");
            zone(root, 1, "battery", "315");
            zone(root, 2, "cpu-cooling-limit", "95000");
            zone(root, 3, "cpu-thermal", null);
            zone(root, 4, "board", "50000");
            zone(root, 5, "skin", "30000");
            zone(root, 6, "soc", "50");
            zone(root, 7, "cpu0", "-1");
            zone(root, 8, "cpu-temp", "99999999999");
            zone(root, 9, "soc-temp", "-90000");
            zone(root, 10, "soc-thermal", "151000");
            zone(root, 11, "cpu-thermal", new String(new char[120]).replace('\0', '9'));
            zone(root, 32, "soc-thermal", "48000");
            expect("ignore unrelated, unknown units, oversized and out-of-range files", null,
                    ProjectorTemperature.readHardware("50.0", file));
            zone(root, 7, "CPU0_thermal\n", "44300\n");
            zone(root, 8, "cpu-big-thermal", "51500");
            zone(root, 11, "cpu-0-0-usr", "48500");
            expect("hottest explicitly named CPU", "CPU 52 °C", ProjectorTemperature.readHardware("-1", file));
            zone(root, 10, "SOC_THERMAL\n", "49500");
            expect("SoC ahead of CPU", "SoC 50 °C", ProjectorTemperature.readHardware("260", file));
            expect("optical LED ahead of SoC", "LED 37 °C", ProjectorTemperature.readHardware(" 37\n", file));
            expect("absent battery", null, ProjectorTemperature.batteryReading(false, 265));
            expect("missing temperature extra", null, ProjectorTemperature.batteryReading(true, Integer.MIN_VALUE));
            expect("unset battery sample", null, ProjectorTemperature.batteryReading(true, 0));
            expect("implausible battery sample", null, ProjectorTemperature.batteryReading(true, 2650));
            expect("battery tenths are rounded", "АКБ 27 °C", ProjectorTemperature.batteryReading(true, 265));
            expect("subzero battery", "АКБ -4 °C", ProjectorTemperature.batteryReading(true, -40));
            expect("none is not a temperature", "— °C", ProjectorTemperature.statusReading(0));
            expect("light throttling", "— °C", ProjectorTemperature.statusReading(1));
            expect("moderate throttling", "Нагрев", ProjectorTemperature.statusReading(2));
            expect("severe throttling", "Сильный нагрев", ProjectorTemperature.statusReading(3));
            expect("critical", "Перегрев", ProjectorTemperature.statusReading(4));
            expect("shutdown", "Перегрев", ProjectorTemperature.statusReading(6));
            expect("unknown status", "— °C", ProjectorTemperature.statusReading(77));
            System.out.println(checks + " temperature checks passed");
        } finally {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }
}
