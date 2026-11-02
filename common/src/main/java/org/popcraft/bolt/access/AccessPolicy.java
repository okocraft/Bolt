package org.popcraft.bolt.access;

import org.popcraft.bolt.Bolt;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceResolver;
import org.popcraft.bolt.source.SourceTypes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

/** Platform-independent permission union used by Bolt's protection adapters. */
public final class AccessPolicy {
    private final Bolt bolt;
    public AccessPolicy(Bolt bolt) { this.bolt = bolt; }
    public boolean allows(Protection protection, SourceResolver resolver, String... permissions) {
        if (protection == null || permissions.length == 0) return true;
        var remaining = new HashSet<>(Arrays.asList(permissions));
        if (resolver.resolve(Source.player(protection.getOwner()))
                || resolver.resolve(Source.of(SourceTypes.PERMISSION, "bolt.admin"))) remaining.removeAll(DefaultAccess.OWNER);
        if (resolver.resolve(Source.of(SourceTypes.PERMISSION, "bolt.mod"))) remaining.removeAll(DefaultAccess.DISPLAY);
        bolt.getAccessRegistry().getProtectionByType(protection.getType())
                .ifPresent(type -> remaining.removeAll(type.permissions()));
        apply(protection.getAccess(), resolver, remaining);
        var access = bolt.getStore().loadAccessList(protection.getOwner()).join();
        if (access != null) apply(access.getAccess(), resolver, remaining);
        remaining.removeIf(permission -> resolver.resolve(Source.of(SourceTypes.PERMISSION, "bolt.permission." + permission)));
        return remaining.isEmpty();
    }
    private void apply(Map<String, String> access, SourceResolver resolver, java.util.Set<String> remaining) {
        for (var entry : access.entrySet()) if (resolver.resolve(Source.parse(entry.getKey()))) {
            bolt.getAccessRegistry().getAccessByType(entry.getValue()).ifPresent(type -> remaining.removeAll(type.permissions()));
        }
    }
}
