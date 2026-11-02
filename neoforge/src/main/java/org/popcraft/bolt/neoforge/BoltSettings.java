package org.popcraft.bolt.neoforge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.popcraft.bolt.access.AccessRegistry;
import org.popcraft.bolt.data.SQLStore;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class BoltSettings {
    private final Path directory;
    private Map<String, Object> values;
    public BoltSettings(Path directory) throws IOException { this.directory = directory; reload(); }
    public void reload() throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("config.yml");
        if (!Files.exists(file)) try (var source = getClass().getResourceAsStream("/default-config.yml")) {
            Files.copy(Objects.requireNonNull(source), file);
        }
        try (var reader = Files.newBufferedReader(file)) {
            values = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        }
        if (values == null) throw new IllegalArgumentException("Empty Bolt configuration");
    }
    public Object value(String path, Object fallback) {
        Object current = values;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) return fallback;
            current = map.get(part);
        }
        return current == null ? fallback : current;
    }
    public boolean bool(String key, boolean fallback) { return Boolean.parseBoolean(value(key, fallback).toString()); }
    public String text(String key, String fallback) { return value(key, fallback).toString(); }
    public String defaultProtectionType() { return defaultType("protections", "private"); }
    public String defaultAccessType() { return defaultType("access", "normal"); }
    private String defaultType(String section, String fallback) {
        for (var entry : section(section).entrySet()) if (entry.getValue() instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("default"))) return entry.getKey();
        return fallback;
    }
    @SuppressWarnings("unchecked") public Map<String, Object> section(String name) {
        Object section = value(name, Map.of());
        if (!(section instanceof Map<?, ?>)) throw new IllegalArgumentException("Bolt section is not a map: " + name);
        return (Map<String, Object>) section;
    }
    public void register(AccessRegistry registry) {
        registry.unregisterAll();
        for (String name : List.of("protections", "access")) for (var entry : section(name).entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> options)) throw new IllegalArgumentException("Invalid Bolt access type: " + entry.getKey());
            var permissions = new HashSet<String>();
            if (options.get("allows") instanceof Collection<?> items) items.forEach(item -> permissions.add(item.toString()));
            boolean restricted = Boolean.TRUE.equals(options.get("require-permission"));
            if (name.equals("protections")) registry.registerProtectionType(entry.getKey(), restricted, permissions);
            else registry.registerAccessType(entry.getKey(), restricted, permissions);
        }
        if (registry.getProtectionByType(defaultProtectionType()).isEmpty()) throw new IllegalArgumentException("Unknown default Bolt protection type");
        for (String section : List.of("blocks", "entities")) for (var entry : section(section).entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> options)) throw new IllegalArgumentException("Invalid Bolt block/entity rule: " + entry.getKey());
            Object type=options.get("autoProtect");
            if (type!=null && !"false".equalsIgnoreCase(type.toString()) && registry.getProtectionByType(type.toString()).isEmpty())
                throw new IllegalArgumentException("Unknown Bolt auto-protection type for " + entry.getKey());
        }
    }
    public boolean isProtectable(ServerLevel level, net.minecraft.core.BlockPos pos) {
        var state = level.getBlockState(pos);
        return blockRule(state) != null || (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft")
                && level.getBlockEntity(pos) instanceof MenuProvider && bool("settings.blocks.protect-mod-containers", true));
    }
    public boolean isProtectable(Entity entity) { return section("entities").containsKey(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath())
            || section("entities").containsKey(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()); }
    private Map<?, ?> blockRule(BlockState state) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        for (var entry : section("blocks").entrySet()) {
            String key = entry.getKey();
            boolean matches = key.startsWith("#") ? state.is(TagKey.create(Registries.BLOCK, Identifier.parse(key.substring(1))))
                    : Identifier.parse(key).equals(id);
            if (matches) return (Map<?, ?>) entry.getValue();
        }
        return null;
    }
    public String autoProtectionType(ServerLevel level, net.minecraft.core.BlockPos pos) {
        Map<?, ?> rule = blockRule(level.getBlockState(pos));
        if (rule == null) return isProtectable(level, pos) ? defaultProtectionType() : null;
        Object type = rule.get("autoProtect");
        return type == null || "false".equalsIgnoreCase(type.toString()) ? null : type.toString();
    }
    public boolean autoProtect(ServerLevel level, net.minecraft.core.BlockPos pos) { return autoProtectionType(level, pos) != null; }
    public String autoProtectionType(Entity entity) {
        Object rule = section("entities").getOrDefault(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), section("entities").get(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath()));
        if (!(rule instanceof Map<?, ?> map)) return null;
        Object type=map.get("autoProtect");
        return type==null || "false".equalsIgnoreCase(type.toString()) ? null : type.toString();
    }
    public boolean autoProtect(Entity entity) { return autoProtectionType(entity)!=null; }
    public boolean redstoneDenyByDefault() { return bool("redstone.deny-by-default", false); }
    public int autoCloseTicks() { return ((Number)value("settings.blocks.auto-close-ticks", 60)).intValue(); }
    public boolean transferRequestsEnabled() { return bool("settings.transfer-requests", true); }
    public boolean transferResetsAccess() { return bool("settings.transfer-resets-access", true); }
    public SQLStore.Configuration database() {
        var properties = new HashMap<String, String>();
        Object configured = value("database.properties", Map.of());
        if (configured instanceof Map<?, ?> map) map.forEach((key, value) -> properties.put(key.toString(), value.toString()));
        return new SQLStore.Configuration(text("database.type", "sqlite"), directory.resolve("bolt.db").toAbsolutePath().toString(),
                text("database.hostname", ""), text("database.database", ""), text("database.username", ""), text("database.password", ""), text("database.prefix", ""), properties);
    }
}
