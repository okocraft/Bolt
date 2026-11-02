package org.popcraft.bolt.neoforge.commands;

import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceTypes;

import java.util.Map;
import java.util.stream.Collectors;

/** Chat rendering for protection records and access sources. */
final class AccessDisplay {
    private AccessDisplay() {}

    static String access(Map<String, String> entries) {
        return entries.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> name(Source.parse(entry.getKey())) + "=" + entry.getValue())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String name(Source source) {
        return source.getType().equals(SourceTypes.PASSWORD) ? "Password" : source.toString();
    }

    static String protection(Protection protection) {
        String target = switch (protection) {
            case BlockProtection block -> block.getWorld() + " " + block.getX() + " " + block.getY() + " " + block.getZ();
            case EntityProtection entity -> entity.getEntity() + " " + entity.getId();
        };
        return target + " / " + protection.getType() + " / 所有者 " + protection.getOwner()
                + " / 共有 " + access(protection.getAccess());
    }
}
