package org.popcraft.bolt.neoforge.protection;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.neoforge.commands.PendingActions;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.util.Permission;

/** Direct entity use, attack, mounting, and attributed damage. */
final class EntityProtectionEvents {

    @SubscribeEvent
    public void onEntityPlaced(EntityJoinLevelEvent event) {
        if (event.isCanceled() || event.loadedFromDisk() || !(event.getLevel() instanceof net.minecraft.server.level.ServerLevel)) return;
        ServerPlayer creator = ProtectionPlacementContext.creator();
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (creator == null || runtime == null || creator.level() != event.getLevel()) return;
        String type = runtime.settings().autoProtectionType(event.getEntity());
        if (type != null) runtime.protections().lock(creator, event.getEntity(), type);
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (PendingActions.consume(player, event.getTarget())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return;
        EntityProtection protection = runtime.protections().find(event.getTarget());
        boolean mayInteract = protection == null
                || runtime.access().canAccess(protection, player, Permission.INTERACT);
        boolean mayDeposit = protection == null
                || !(event.getTarget() instanceof ItemFrame frame)
                || !frame.getItem().isEmpty()
                || player.getItemInHand(event.getHand()).isEmpty()
                || runtime.access().canAccess(protection, player, Permission.DEPOSIT);
        if (!mayInteract || !mayDeposit) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        } else if (protection != null) {
            protection.setAccessed(System.currentTimeMillis());
            runtime.protections().save(protection);
        }
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (PendingActions.consume(player, target)) {
            event.setCanceled(true);
            return;
        }
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return;
        EntityProtection protection = runtime.protections().find(target);
        if (protection == null) return;
        if (target instanceof ItemFrame frame && !frame.getItem().isEmpty()) {
            if (!runtime.access().canAccess(protection, player, Permission.WITHDRAW)) event.setCanceled(true);
        } else if (!mayDestroy(runtime, protection, target, player)) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onMount(EntityMountEvent event) {
        if (event.isCanceled() || !event.isMounting()
                || !(event.getEntityMounting() instanceof ServerPlayer player)) return;
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return;
        EntityProtection protection = runtime.protections().find(event.getEntityBeingMounted());
        if (protection != null && !runtime.access().canAccess(protection, player, Permission.MOUNT)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onInvulnerability(EntityInvulnerabilityCheckEvent event) {
        if (event.isInvulnerable()) return;
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return;
        EntityProtection protection = runtime.protections().find(event.getEntity());
        if (protection != null && !mayDestroy(runtime, protection, event.getEntity(),
                event.getSource().getEntity())) {
            event.setInvulnerable(true);
        }
    }

    @SubscribeEvent
    public void onLivingDamage(LivingIncomingDamageEvent event) {
        if (event.isCanceled()) return;
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return;
        EntityProtection protection = runtime.protections().find(event.getEntity());
        if (protection != null && !mayDestroy(runtime, protection, event.getEntity(),
                event.getSource().getEntity())) {
            event.setCanceled(true);
        }
    }

    private static boolean mayDestroy(BoltRuntime runtime, EntityProtection protection,
                                      Entity target, Entity attacker) {
        if (attacker instanceof ServerPlayer player
                && runtime.access().canAccess(protection, player, Permission.DESTROY)) return true;
        boolean living = target instanceof LivingEntity && !(target instanceof ArmorStand);
        boolean playerDamage = attacker instanceof ServerPlayer;
        return runtime.settings().bool("settings.entities.allow-"
                        + (living ? "living" : "nonliving") + "-entity-"
                        + (playerDamage ? "player" : "environment") + "-damage",
                living);
    }
}
