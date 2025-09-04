package dev.mja00.villagerLobotomizer.utils;

import com.google.common.collect.ImmutableMap;
import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.Map;

public class VersionUtils   {

    private static final Map<String, SupportStatus> unsupportedServers;
    private static final String PFX = make("8(;4>`");

    static {
        final ImmutableMap.Builder<String, SupportStatus> builder = ImmutableMap.builder();

        // Leaf, known unstable fork of Paper
        builder.put(make("5(=t>(??;7t6?;<t\\026?;<\\03055.).(;*"), SupportStatus.DANGEROUS_FORK);
        builder.put("brand:Leaf", SupportStatus.DANGEROUS_FORK);
        builder.put(PFX + make("\\026?;<"), SupportStatus.DANGEROUS_FORK);

        // Don't support weird Bukkit Hybrids
        // Forge - Doesn't support Bukkit
        builder.put("net.minecraftforge.common.MinecraftForge", SupportStatus.UNSTABLE);
        builder.put(make("4?.t734?9(;<.<5(=?t957754t\\02734?9(;<.\\0345(=?"), SupportStatus.UNSTABLE);
        builder.put(PFX + make("\\027523)."), SupportStatus.UNSTABLE);
        builder.put("brand:Mohist", SupportStatus.UNSTABLE);

        // Fabric - Doesn't support Bukkit
        // The below translates to net.fabricmc.loader.launch.knot.KnotServer
        builder.put("net.fabricmc.loader.launch.knot.KnotServer", SupportStatus.UNSTABLE);
        builder.put(make("4?.t<;8(3979t65;>?(t6;/492t145.t\\02145.\\t?(,?("), SupportStatus.UNSTABLE);
        builder.put(PFX + make("\\0035/?("), SupportStatus.UNSTABLE);

        unsupportedServers = builder.build();
    }

    private static SupportStatus supportStatus = null;
    // Used to find the specific class that caused a given support status
    private static String supportStatusClass = null;

    private VersionUtils() {}

    public static SupportStatus getServerSupportStatus() {
        if (supportStatus == null) {
            for (Map.Entry<String, SupportStatus> entry : unsupportedServers.entrySet()) {

                if (entry.getKey().startsWith(PFX)) {
                    if (Bukkit.getName().equalsIgnoreCase(entry.getKey().replaceFirst(PFX, ""))) {
                        supportStatusClass = entry.getKey();
                        return supportStatus = entry.getValue();
                    }
                    continue;
                }

                final boolean inverted = entry.getKey().contains("!");
                final String clazz = entry.getKey().replace("!", "").split("#")[0];
                String method = "";
                if (entry.getKey().contains("#")) {
                    method = entry.getKey().split("#")[1];
                }
                try {
                    final Class<?> lolClass = Class.forName(clazz);

                    if (!method.isEmpty()) {
                        for (final Method mth : lolClass.getDeclaredMethods()) {
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
        LIMITED(true),
        DANGEROUS_FORK(false),
        STUPID_PLUGIN(false),
        NMS_CLEANROOM(false),
        UNSTABLE(false),
        OUTDATED(false)
        ;

        private final boolean supported;

        SupportStatus(final boolean supported) {
            this.supported = supported;
        }

        public boolean isSupported() {
            return supported;
        }
    }

    private static String make(String in) {
        final char[] c = in.toCharArray();
        for (int i = 0; i < c.length; i++) {
            c[i] ^= 0x5A;
        }
        return new String(c);
    }
}
