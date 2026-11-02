package org.popcraft.bolt.neoforge.testmod;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.testmod.integration.IntegrationFixtures;
import org.popcraft.bolt.neoforge.testmod.commands.CommandContractProbe;
import org.popcraft.bolt.neoforge.testmod.commands.CommandBulkProbe;
import org.popcraft.bolt.neoforge.testmod.compat.DqmContainerFixtures;
import org.popcraft.bolt.neoforge.testmod.protection.ProtectionBehaviorTests;
import java.util.*;

@Mod("bolt_test")
public final class BoltTestMod {
    private int ticks;
    private boolean ran, protectionPending, integrationPending, bulkPending;
    private final List<Throwable> failures=new ArrayList<>();
    private static final BlockPos SENTINEL=new BlockPos(4250,250,4250);
    private static final UUID OWNER=UUID.fromString("28d1499b-2f66-413e-9230-1aeed35f54b8");
    public BoltTestMod() { NeoForge.EVENT_BUS.addListener(this::tick); }
    private void tick(ServerTickEvent.Post event) {
        if (BoltNeoForge.runtime()==null || !de.kuro.worldguard.api.WorldGuardNeoForge.isReady()) return;
        var server=event.getServer();var runtime=BoltNeoForge.runtime();var level=server.overworld();
        try {
            if (bulkPending) {
                if (CommandBulkProbe.tick(server,runtime)) { bulkPending=false; finish(server); }
                return;
            }
            if (protectionPending || integrationPending) {
                if (protectionPending) try { if (ProtectionBehaviorTests.tick(level)) protectionPending=false; }
                catch (Throwable failure) { protectionPending=false; record("protection tick",failure); }
                if (integrationPending) try { if (IntegrationFixtures.tick(level)) integrationPending=false; }
                catch (Throwable failure) { integrationPending=false; record("integration tick",failure); }
                if (!protectionPending && !integrationPending) afterFixtures(server);
                return;
            }
            if (ran || ++ticks<40) return;
            ran=true;
            if (Boolean.getBoolean("bolt.test.restart")) { verifyRestart(level); finish(server); return; }
            try { ProtectionBehaviorTests.run(level); protectionPending=true; }
            catch (Throwable failure) { record("protection",failure); }
            try { DqmContainerFixtures.run(level); }
            catch (Throwable failure) { record("DQM containers",failure); }
            try {
                var actor=FakePlayerFactory.get(level,new GameProfile(OWNER,"bolt_test_cmd"));
                actor.setPos(4300.5,250,4300.5);CommandContractProbe.run(server,actor,runtime);
                System.out.println("BOLT_COMMAND_TESTS_PASSED");
            } catch (Throwable failure) { record("commands",failure); }
            try { IntegrationFixtures.run(level); integrationPending=true; }
            catch (Throwable failure) { record("integration",failure); }
            if (!protectionPending && !integrationPending) afterFixtures(server);
        } catch (Throwable failure) { failure.printStackTrace(); System.err.println("BOLT_SERVER_TESTS_FAILED"); server.halt(false); }
    }
    private void record(String name,Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        failures.add(failure);System.err.println("BOLT_CASE_FAILED "+name);failure.printStackTrace();
    }
    private void afterFixtures(net.minecraft.server.MinecraftServer server) {
        if (!failures.isEmpty()) { finish(server); return; }
        var runtime=BoltNeoForge.runtime();var level=server.overworld();
        level.setBlockAndUpdate(SENTINEL,Blocks.CHEST.defaultBlockState());
        runtime.protections().save(runtime.protections().create(level,SENTINEL,OWNER,"private"));
        runtime.bolt().getStore().flush().join();
        CommandBulkProbe.start(server,runtime);bulkPending=true;
    }
    private void finish(net.minecraft.server.MinecraftServer server) {
        if (!failures.isEmpty()) {
            var failure=new AssertionError("Bolt behavior fixtures failed");failures.forEach(failure::addSuppressed);throw failure;
        }
        System.out.println("BOLT_SERVER_TESTS_PASSED");
    }
    private void verifyRestart(ServerLevel level) {
        var runtime=BoltNeoForge.runtime();var protection=runtime.protections().find(level,SENTINEL);
        if (!level.getBlockState(SENTINEL).is(Blocks.CHEST) || protection==null || !OWNER.equals(protection.getOwner())) throw new AssertionError("Restart lost persisted chest ownership");
        var outsider=FakePlayerFactory.get(level,new GameProfile(UUID.fromString("767ba67a-1210-440c-beb5-6a3d07be38a6"),"bolt_test_restart"));
        if (runtime.access().canAccess(protection,outsider,org.popcraft.bolt.util.Permission.OPEN)) throw new AssertionError("Restart lost access denial");
        runtime.protections().remove(protection);level.removeBlock(SENTINEL,false);System.out.println("BOLT_RESTART_TESTS_PASSED");
    }
}
