package org.popcraft.bolt.access;

import org.junit.jupiter.api.Test;
import org.popcraft.bolt.Bolt;
import org.popcraft.bolt.data.MemoryStore;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.util.Permission;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AccessPolicyTest {
    private static final UUID OWNER=UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FRIEND=UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final Bolt bolt=new Bolt(new MemoryStore());
    private final AccessPolicy policy=new AccessPolicy(bolt);
    AccessPolicyTest() {
        bolt.getAccessRegistry().registerProtectionType("private",false,Set.of(Permission.REDSTONE));
        bolt.getAccessRegistry().registerAccessType("deposit",false,Set.of(Permission.OPEN,Permission.DEPOSIT));
        bolt.getAccessRegistry().registerAccessType("withdraw",false,Set.of(Permission.WITHDRAW));
    }
    private BlockProtection chest() { return new BlockProtection(UUID.randomUUID(),OWNER,"private",1,1,new HashMap<>(),"minecraft:overworld",5,64,-3,"minecraft:chest"); }
    @Test void ownershipAndModeratorHaveDifferentMutationRights() {
        var chest=chest();
        assertTrue(policy.allows(chest,Source.player(OWNER)::equals,Permission.DESTROY));
        assertFalse(policy.allows(chest,Source.player(FRIEND)::equals,Permission.OPEN));
        assertTrue(policy.allows(chest,s->s.equals(Source.of("permission","bolt.mod")),Permission.OPEN));
        assertFalse(policy.allows(chest,s->s.equals(Source.of("permission","bolt.mod")),Permission.DESTROY));
    }
    @Test void perProtectionAndOwnerTrustUnionOnlyGrantedOperations() {
        var chest=chest();
        chest.getAccess().put(Source.player(FRIEND).toString(),"deposit");
        assertTrue(policy.allows(chest,Source.player(FRIEND)::equals,Permission.OPEN,Permission.DEPOSIT));
        assertFalse(policy.allows(chest,Source.player(FRIEND)::equals,Permission.WITHDRAW));
        bolt.getStore().saveAccessList(new AccessList(OWNER,new HashMap<>(Map.of(Source.player(FRIEND).toString(),"withdraw"))));
        assertTrue(policy.allows(chest,Source.player(FRIEND)::equals,Permission.DEPOSIT,Permission.WITHDRAW));
        assertFalse(policy.allows(chest,Source.player(FRIEND)::equals,Permission.DESTROY));
    }
}
