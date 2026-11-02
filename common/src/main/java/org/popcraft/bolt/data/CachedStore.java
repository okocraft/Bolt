package org.popcraft.bolt.data;

import org.popcraft.bolt.access.AccessList;
import org.popcraft.bolt.protection.*;
import org.popcraft.bolt.util.Group;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** A complete server-thread cache; the persistent store serializes database operations. */
public final class CachedStore extends MemoryStore {
    private final Store persistent;
    public CachedStore(Store persistent) {
        this.persistent = persistent;
        persistent.loadBlockProtections().join().forEach(super::saveBlockProtection);
        persistent.loadEntityProtections().join().forEach(super::saveEntityProtection);
        persistent.loadGroups().join().forEach(super::saveGroup);
        persistent.loadAccessLists().join().forEach(super::saveAccessList);
        persistent.loadPendingTransfers().join().forEach(super::savePendingTransfer);
    }
    @Override public void saveBlockProtection(BlockProtection value) { super.saveBlockProtection(value); persistent.saveBlockProtection(value); }
    @Override public void removeBlockProtection(BlockProtection value) { super.removeBlockProtection(value); persistent.removeBlockProtection(value); }
    @Override public void saveEntityProtection(EntityProtection value) { super.saveEntityProtection(value); persistent.saveEntityProtection(value); }
    @Override public void removeEntityProtection(EntityProtection value) { super.removeEntityProtection(value); persistent.removeEntityProtection(value); }
    @Override public void saveGroup(Group value) { super.saveGroup(value); persistent.saveGroup(value); }
    @Override public void removeGroup(Group value) { super.removeGroup(value); persistent.removeGroup(value); }
    @Override public void saveAccessList(AccessList value) { super.saveAccessList(value); persistent.saveAccessList(value); }
    @Override public void removeAccessList(AccessList value) { super.removeAccessList(value); persistent.removeAccessList(value); }
    @Override public void savePendingTransfer(PendingTransfer value) { super.savePendingTransfer(value); persistent.savePendingTransfer(value); }
    @Override public void removePendingTransfer(PendingTransfer value) { super.removePendingTransfer(value); persistent.removePendingTransfer(value); }
    @Override public long pendingSave() { return persistent.pendingSave(); }
    @Override public CompletableFuture<Void> flush() { return persistent.flush(); }
}
