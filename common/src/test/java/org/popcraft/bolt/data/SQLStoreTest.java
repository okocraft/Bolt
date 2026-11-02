package org.popcraft.bolt.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.popcraft.bolt.access.AccessList;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.util.Group;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SQLStoreTest {
    @TempDir Path directory;
    private static final UUID OWNER=UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FRIEND=UUID.fromString("22222222-2222-2222-2222-222222222222");
    private SQLStore open() { return new SQLStore(new SQLStore.Configuration("sqlite",directory.resolve("bolt.db").toString(),"","","","","",Map.of("busy_timeout","1"))); }
    private BlockProtection chest(UUID id) { return new BlockProtection(id,OWNER,"private",10,20,new HashMap<>(),"dqmvii:example",7,80,-8,"dqmvii:test_chest"); }
    @Test void restartRetainsNamespacedPositionOwnerSharedAccessAndGroups() {
        var sql=open(); var store=new CachedStore(sql);var id=UUID.randomUUID(); var chest=chest(id);
        chest.getAccess().put("player:"+FRIEND,"normal");store.saveBlockProtection(chest);
        store.saveGroup(new Group("builders",OWNER,new ArrayList<>(List.of(FRIEND))));
        store.saveAccessList(new AccessList(OWNER,new HashMap<>(Map.of("group:builders","normal"))));
        sql.close();
        var restarted=open();try {
            var cache=new CachedStore(restarted);
            var saved=cache.loadBlockProtection(new org.popcraft.bolt.util.BlockLocation("dqmvii:example",7,80,-8)).join();
            assertEquals(id,saved.getId()); assertEquals(OWNER,saved.getOwner()); assertEquals("dqmvii:test_chest",saved.getBlock());
            assertEquals("normal",saved.getAccess().get("player:"+FRIEND));
            assertEquals(List.of(FRIEND),cache.loadGroup("builders").join().getMembers());
            assertEquals("normal",cache.loadAccessList(OWNER).join().getAccess().get("group:builders"));
        } finally { restarted.close(); }
    }
    @Test void queuedUpdatesUseSnapshotsAndLastOperationWins() {
        var sql=open();var record=chest(UUID.randomUUID());
        try {
            sql.saveBlockProtection(record);record.setOwner(FRIEND);
            sql.flush().join();assertEquals(OWNER,sql.loadBlockProtection(new org.popcraft.bolt.util.BlockLocation("dqmvii:example",7,80,-8)).join().getOwner());
            sql.removeBlockProtection(record);sql.saveBlockProtection(record);sql.flush().join();
            assertEquals(FRIEND,sql.loadBlockProtection(new org.popcraft.bolt.util.BlockLocation("dqmvii:example",7,80,-8)).join().getOwner());
            sql.saveBlockProtection(record);sql.removeBlockProtection(record);sql.flush().join();
            assertNull(sql.loadBlockProtection(new org.popcraft.bolt.util.BlockLocation("dqmvii:example",7,80,-8)).join());
        } finally { sql.close(); }
    }
    @Test void temporaryWriteFailureRetainsQueuedLockAndRetries() throws Exception {
        var sql=open();var record=chest(UUID.randomUUID());
        try {
            sql.saveBlockProtection(record);
            try(var blocker=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("bolt.db"));var command=blocker.createStatement()) {
                command.execute("BEGIN EXCLUSIVE");
                assertThrows(java.util.concurrent.CompletionException.class,()->sql.flush().join());
                command.execute("ROLLBACK");
            }
            sql.flush().join();
            assertEquals(OWNER,sql.loadBlockProtection(new org.popcraft.bolt.util.BlockLocation("dqmvii:example",7,80,-8)).join().getOwner());
        } finally { sql.close(); }
    }
}
