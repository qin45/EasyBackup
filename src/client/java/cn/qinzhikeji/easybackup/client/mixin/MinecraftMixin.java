package cn.qinzhikeji.easybackup.client.mixin;

import cn.qinzhikeji.easybackup.client.BackupManager;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 每个客户端 tick 检查：新世界是否已加载，用来完成回档收尾与聊天栏提示。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void easybackup$onTick(CallbackInfo ci) {
		BackupManager.onClientTick((Minecraft) (Object) this);
	}
}
