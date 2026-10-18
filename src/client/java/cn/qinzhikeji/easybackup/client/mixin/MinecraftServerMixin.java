package cn.qinzhikeji.easybackup.client.mixin;

import cn.qinzhikeji.easybackup.client.BackupManager;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 备份期间拦截自动保存与暂停保存；退出世界时中止进行中的备份。
 * <p>
 * 只拦截 {@code saveEverything}：{@code stopServer()} 会直接调用 {@code saveAllChunks}
 * 完成最终保存，那一份必须保留。强制保存（{@code forced = true}）也不拦截，
 * 保证备份开始前能看到完整落盘状态。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Inject(method = "saveEverything", at = @At("HEAD"), cancellable = true)
	private void easybackup$blockAutoSave(boolean suppressLog, boolean flush, boolean forced,
										  CallbackInfoReturnable<Boolean> cir) {
		if (!forced && BackupManager.isSuppressed((MinecraftServer) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "stopServer", at = @At("HEAD"))
	private void easybackup$onStopping(CallbackInfo ci) {
		BackupManager.onServerStopping((MinecraftServer) (Object) this);
	}
}
