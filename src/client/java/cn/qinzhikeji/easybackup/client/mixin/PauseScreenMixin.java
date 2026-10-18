package cn.qinzhikeji.easybackup.client.mixin;

import cn.qinzhikeji.easybackup.client.WorldRef;
import cn.qinzhikeji.easybackup.client.gui.BackupScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在单人游戏的 ESC 菜单右上角加入「备份」按钮；多人服务器不显示。
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
	protected PauseScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void easybackup$addBackupButton(CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.hasSingleplayerServer()) {
			return;
		}
		addRenderableWidget(Button.builder(Component.translatable("easybackup.button.entry"),
						button -> {
							WorldRef world = WorldRef.current(minecraft);
							if (world != null) {
								minecraft.gui.setScreen(new BackupScreen(this, world));
							}
						})
				.bounds(this.width - 104, 4, 100, 20)
				.build());
	}
}
