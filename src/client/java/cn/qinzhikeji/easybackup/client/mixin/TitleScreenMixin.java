package cn.qinzhikeji.easybackup.client.mixin;

import cn.qinzhikeji.easybackup.client.BackupManager;
import cn.qinzhikeji.easybackup.client.gui.BackupWorldSelectScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在标题界面左上角加入「备份」按钮，并触发一次崩溃恢复检查。
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	protected TitleScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void easybackup$addBackupButton(CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		addRenderableWidget(Button.builder(Component.translatable("easybackup.button.entry"),
						button -> minecraft.gui.setScreen(new BackupWorldSelectScreen(this)))
				.bounds(4, 4, 100, 20)
				.build());
		BackupManager.checkRecovery(this, false);
	}
}
