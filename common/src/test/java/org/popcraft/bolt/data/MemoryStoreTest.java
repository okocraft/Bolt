package org.popcraft.bolt.data;

import org.junit.jupiter.api.Test;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.util.BlockLocation;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {
    private BlockProtection at(UUID id,int x) { return new BlockProtection(id,UUID.fromString("11111111-1111-1111-1111-111111111111"),"private",1,1,new HashMap<>(),"minecraft:overworld",x,64,0,"minecraft:chest"); }
    @Test void movedProtectionHasOnlyItsCurrentLocation() {
        var store=new MemoryStore();var protection=at(UUID.randomUUID(),1);
        store.saveBlockProtection(protection);protection.setX(2);store.saveBlockProtection(protection);
        assertNull(store.loadBlockProtection(new BlockLocation("minecraft:overworld",1,64,0)).join());
        assertEquals(protection.getId(),store.loadBlockProtection(new BlockLocation("minecraft:overworld",2,64,0)).join().getId());
        store.removeBlockProtection(protection);
        assertNull(store.loadBlockProtection(new BlockLocation("minecraft:overworld",2,64,0)).join());
    }
    @Test void removingReplacedIdentityCannotEraseTheNewLock() {
        var store=new MemoryStore();var previous=at(UUID.randomUUID(),1);var replacement=at(UUID.randomUUID(),1);
        store.saveBlockProtection(previous);store.saveBlockProtection(replacement);store.removeBlockProtection(previous);
        assertEquals(replacement.getId(),store.loadBlockProtection(new BlockLocation("minecraft:overworld",1,64,0)).join().getId());
    }
}
