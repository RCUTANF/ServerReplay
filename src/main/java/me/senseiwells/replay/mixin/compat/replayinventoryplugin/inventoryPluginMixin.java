package me.senseiwells.replay.mixin.compat.replayinventoryplugin;
import me.senseiwells.replay.compat.inventory.ReplayInventoryPlugin;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public class inventoryPluginMixin {

    @Inject(method = "giveExperiencePoints", at = @At("RETURN"))
    private void onGiveExperiencePoints(int points, CallbackInfo ci) {
        ReplayInventoryPlugin.INSTANCE.onExperienceChange((ServerPlayer)(Object)this);
    }

    @Inject(method = "setExperiencePoints", at = @At("RETURN"))
    private void onSetExperiencePoints(int points, CallbackInfo ci) {
        ReplayInventoryPlugin.INSTANCE.onExperienceChange((ServerPlayer)(Object)this);
    }

    @Inject(method = "setExperienceLevels", at = @At("RETURN"))
    private void onSetExperienceLevels(int levels, CallbackInfo ci) {
        ReplayInventoryPlugin.INSTANCE.onExperienceChange((ServerPlayer)(Object)this);
    }
}