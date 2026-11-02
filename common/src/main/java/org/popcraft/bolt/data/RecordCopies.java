package org.popcraft.bolt.data;

import org.popcraft.bolt.access.AccessList;
import org.popcraft.bolt.protection.*;
import org.popcraft.bolt.util.Group;
import java.util.ArrayList;
import java.util.HashMap;

final class RecordCopies {
    private RecordCopies() { }
    static BlockProtection block(BlockProtection p) { return new BlockProtection(p.getId(),p.getOwner(),p.getType(),p.getCreated(),p.getAccessed(),new HashMap<>(p.getAccess()),p.getWorld(),p.getX(),p.getY(),p.getZ(),p.getBlock()); }
    static EntityProtection entity(EntityProtection p) { return new EntityProtection(p.getId(),p.getOwner(),p.getType(),p.getCreated(),p.getAccessed(),new HashMap<>(p.getAccess()),p.getEntity()); }
    static Group group(Group p) { return new Group(p.getName(),p.getOwner(),new ArrayList<>(p.getMembers())); }
    static AccessList access(AccessList p) { return new AccessList(p.getOwner(),new HashMap<>(p.getAccess())); }
}
