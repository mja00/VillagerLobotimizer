package dev.mja00.villagerLobotomizer.utils;

import dev.mja00.villagerLobotomizer.policy.ActivityDecision;
import dev.mja00.villagerLobotomizer.policy.ActivityDecision.BlockVerdict;
import dev.mja00.villagerLobotomizer.policy.ActivityDecision.DirectionTrace;
import dev.mja00.villagerLobotomizer.policy.ActivityDecision.MovementTrace;
import dev.mja00.villagerLobotomizer.policy.ActivityDecision.RuleCheck;
import dev.mja00.villagerLobotomizer.policy.BlockSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders an {@link ActivityDecision} as chat lines for {@code /lobotomy debug}. Tags are plain ASCII
 * so the same lines read cleanly when serialized to the console or pasted into a bug report.
 */
public final class ActivityDecisionFormatter {

    private ActivityDecisionFormatter() {
    }

    public static List<Component> format(ActivityDecision decision) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Rule result: ")
                .append(Component.text(decision.active() ? "KEEP ACTIVE" : "LOBOTOMIZE")
                        .color(decision.active() ? NamedTextColor.GREEN : NamedTextColor.RED))
                .append(Component.text(" (" + decision.summary() + ")").color(NamedTextColor.GRAY)));
        lines.add(Component.text("Rules, in order (first decisive rule wins):").color(NamedTextColor.GOLD));
        for (RuleCheck check : decision.checks()) {
            Component line = Component.text(" [" + check.outcome().tag() + "] ").color(colorOf(check.outcome()))
                    .append(Component.text(check.rule().label()).color(NamedTextColor.WHITE));
            if (!check.detail().isEmpty()) {
                line = line.append(Component.text(": " + check.detail()).color(NamedTextColor.GRAY));
            }
            lines.add(line);
        }

        MovementTrace movement = decision.movement();
        if (movement == null) {
            return lines;
        }
        lines.add(Component.text("Movement check: ").color(NamedTextColor.GOLD)
                .append(Component.text("floor " + name(movement.floor()) + "; "
                        + movement.roofReason() + "; "
                        + (movement.roofed()
                        ? "under-feet walls/fences/gates also block"
                        : "under-feet blocks ignored")).color(NamedTextColor.GRAY)));
        if (movement.overhang() > 0) {
            lines.add(Component.text(String.format(Locale.ROOT,
                    " Hitbox is raised %.4f into the layer above head height, so blocks there (\"above\") also count.",
                    movement.overhang())).color(NamedTextColor.GRAY));
        }
        for (DirectionTrace d : movement.directions()) {
            Component line = Component.text(" " + d.direction() + " @ " + d.x() + " " + d.y() + " " + d.z() + ": ")
                    .color(NamedTextColor.WHITE)
                    .append(Component.text(d.open() ? "OPEN" : "BLOCKED")
                            .color(d.open() ? NamedTextColor.GREEN : NamedTextColor.RED))
                    .append(Component.text(" | ").color(NamedTextColor.DARK_GRAY))
                    .append(block("feet", d.feet(), d.feetVerdict()))
                    .append(Component.text(", ").color(NamedTextColor.DARK_GRAY))
                    .append(block("head", d.head(), d.headVerdict()));
            if (d.overheadVerdict() != null) {
                line = line.append(Component.text(", ").color(NamedTextColor.DARK_GRAY))
                        .append(block("above", d.overhead(), d.overheadVerdict()));
            }
            if (movement.roofed()) {
                line = line.append(Component.text(", ").color(NamedTextColor.DARK_GRAY))
                        .append(block("under", d.underFeet(), d.underFeetVerdict()));
            }
            lines.add(line);
        }
        return lines;
    }

    private static Component block(String position, BlockSnapshot snapshot, BlockVerdict verdict) {
        return Component.text(position + " " + name(snapshot) + " (" + verdict.description() + ")")
                .color(verdict.blocking() ? NamedTextColor.RED : NamedTextColor.GRAY);
    }

    private static String name(BlockSnapshot snapshot) {
        return snapshot == null ? "?" : snapshot.type().name();
    }

    private static NamedTextColor colorOf(ActivityDecision.Outcome outcome) {
        return switch (outcome) {
            case PASSED -> NamedTextColor.GRAY;
            case KEPT_ACTIVE -> NamedTextColor.GREEN;
            case LOBOTOMIZED -> NamedTextColor.RED;
            case DISABLED, NOT_REACHED -> NamedTextColor.DARK_GRAY;
        };
    }
}
