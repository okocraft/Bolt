package org.popcraft.bolt.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.popcraft.bolt.neoforge.protection.ProtectionPlacementContext;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemStack.class)
public abstract class ItemPlacementMixin {
    @WrapMethod(method = "useOn")
    private InteractionResult bolt$useOn(UseOnContext context, Operation<InteractionResult> original) {
        return ProtectionPlacementContext.useOn(context, () -> original.call(context));
    }

    @WrapMethod(method = "use")
    private InteractionResult bolt$use(Level level, Player player, InteractionHand hand,
                                       Operation<InteractionResult> original) {
        return ProtectionPlacementContext.use(player, () -> original.call(level, player, hand));
    }
}
