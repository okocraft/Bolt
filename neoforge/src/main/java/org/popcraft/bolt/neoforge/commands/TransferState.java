package org.popcraft.bolt.neoforge.commands;

import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.protection.PendingTransfer;

import java.util.UUID;

final class TransferState {
    private TransferState() {}

    static void clear(BoltRuntime runtime, UUID protection) {
        for (PendingTransfer transfer : runtime.bolt().getStore().loadPendingTransfers().join()) {
            if (transfer.protection().equals(protection)) runtime.bolt().getStore().removePendingTransfer(transfer);
        }
    }
}
