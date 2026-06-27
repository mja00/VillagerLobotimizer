package dev.mja00.villagerLobotomizer.utils;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.Map;

public class VersionUtils {

    /**
     * Detection keys for known-unsupported server forks. Keys prefixed with {@value #BRAND_PREFIX}
     * are matched against {@link org.bukkit.Server#getName()}; non-prefixed keys are matched
     * against class names via {@link Class#forName(String)}. The optional suffixes {@value #INVERTED_PREFIX}
     * (match when the class is NOT present) and {@code class#method} (match only when the class
     * declares the named method) are preserved for forward compatibility, though no current entry
     * uses them.
     */
    private static final Map<String, SupportStatus> UNSUPPORTED_SERVERS = Map.ofEntries(
            // Leaf, known unstable fork of Paper
            Map.entry("org.dreeam.leaf.LeafBootstrap", SupportStatus.DANGEROUS_FORK),
            Map.entry("brand:Leaf", SupportStatus.DANGEROUS_FORK),

            // Don't support weird Bukkit Hybrids
            // Forge - Doesn't support Bukkit
            Map.entry("net.minecraftforge.common.MinecraftForge", SupportStatus.UNSTABLE),
            Map.entry("brand:Mohist", SupportStatus.UNSTABLE),

            // Fabric - Doesn't support Bukkit
            Map.entry("net.fabricmc.loader.launch.knot.KnotServer", SupportStatus.UNSTABLE),
            Map.entry("brand:Youer", SupportStatus.UNSTABLE)
    );

    private static final String BRAND_PREFIX = "brand:";
    private static final String INVERTED_PREFIX = "!";
    private static final String METHOD_SEPARATOR = "#";

    private static SupportStatus supportStatus = null;
    // Used to find the specific class that caused a given support status
    private static String supportStatusClass = null;

    private VersionUtils() {}

    public static SupportStatus getServerSupportStatus() {
        if (supportStatus == null) {
            for (Map.Entry<String, SupportStatus> entry : UNSUPPORTED_SERVERS.entrySet()) {

                if (entry.getKey().startsWith(BRAND_PREFIX)) {
                    if (Bukkit.getName().equalsIgnoreCase(entry.getKey().substring(BRAND_PREFIX.length()))) {
                        supportStatusClass = entry.getKey();
                        return supportStatus = entry.getValue();
                    }
                    continue;
                }

                final boolean inverted = entry.getKey().contains(INVERTED_PREFIX);
                final String clazz = entry.getKey().replace(INVERTED_PREFIX, "").split(METHOD_SEPARATOR)[0];
                String method = "";
                if (entry.getKey().contains(METHOD_SEPARATOR)) {
                    method = entry.getKey().split(METHOD_SEPARATOR)[1];
                }
                try {
                    final Class<?> detectedClass = Class.forName(clazz);

                    if (!method.isEmpty()) {
                        for (final Method mth : detectedClass.getDeclaredMethods()) {
                            if (mth.getName().equals(method)) {
                                if (!inverted) {
                                    supportStatusClass = entry.getKey();
                                    return supportStatus = entry.getValue();
                                }
                            }
                        }
                        continue;
                    }

                    if (!inverted) {
                        supportStatusClass = entry.getKey();
                        return supportStatus = entry.getValue();
                    }
                } catch (final ClassNotFoundException ignored) {
                    if (inverted) {
                        supportStatusClass = entry.getKey();
                        return supportStatus = entry.getValue();
                    }
                }
            }

            return supportStatus = SupportStatus.FULL;
        }
        return supportStatus;
    }

    public static String getSupportStatusClass() {
        return supportStatusClass;
    }

    public enum SupportStatus {
        FULL(true),
        DANGEROUS_FORK(false),
        UNSTABLE(false),
        OUTDATED(false)
        ;

        private final boolean supported;

        SupportStatus(final boolean supported) {
            this.supported = supported;
        }

        /**
         * Indicates whether this server configuration is supported.
         *
         * @return true if supported, false otherwise.
         */
        public boolean isSupported() {
            return supported;
        }
    }
}
