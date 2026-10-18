package cn.qinzhikeji.easybackup.client.gui;

import cn.qinzhikeji.easybackup.client.BackupManager;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 回档失败界面：显示原因，并提供重试/恢复回档前存档。
 */
public class RestoreFailedScreen extends Screen {
	private final BackupManager.RestoreFlow flow;
	private final String error;

	public RestoreFailedScreen(BackupManager.RestoreFlow flow, String error) {
		super(Component.translatable("easybackup.title.restore_failed"));
		this.flow = flow;
		this.error = error == null || error.isEmpty() ? "未知错误" : error;
	}

	@Override
	protected void init() {
		int count = 1 + (this.flow.canRetry() ? 1 : 0) + (this.flow.canRollback() ? 1 : 0);
		int buttonWidth = 120;
		int gap = 6;
		int totalWidth = count * buttonWidth + (count - 1) * gap;
		int x = (this.width - totalWidth) / 2;
		int y = this.height / 2 + 50;

		if (this.flow.canRetry()) {
			addRenderableWidget(Button.builder(Component.translatable("easybackup.button.retry"),
							button -> BackupManager.retryRestore(this.flow))
					.bounds(x, y, buttonWidth, 20).build());
			x += buttonWidth + gap;
		}
		if (this.flow.canRollback()) {
			addRenderableWidget(DangerButton.create(
					Component.translatable("easybackup.button.rollback"),
					button -> confirmRollback(), x, y, buttonWidth, 20));
			x += buttonWidth + gap;
		}
		addRenderableWidget(Button.builder(Component.translatable("easybackup.button.back"),
						button -> onClose())
				.bounds(x, y, buttonWidth, 20).build());
	}

	private void confirmRollback() {
		this.minecraft.gui.setScreen(new EasyBackupConfirmScreen(result -> {
			if (result) {
				BackupManager.rollbackRestore(this.flow);
			} else {
				this.minecraft.gui.setScreen(this);
			}
		}, Component.translatable("easybackup.confirm.rollback.title"),
				Component.translatable("easybackup.confirm.rollback.message"),
				Component.translatable("easybackup.button.rollback"),
				Component.translatable("easybackup.button.cancel")));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(gui, mouseX, mouseY, partialTick);
		gui.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 80, 0xFFFF5555);
		int textWidth = Math.min(440, this.width - 60);
		gui.textWithWordWrap(this.font, Component.literal(this.error),
				(this.width - textWidth) / 2, this.height / 2 - 55, textWidth, 0xFFFFFFFF);
		if (this.flow.canRollback()) {
			gui.centeredText(this.font, Component.translatable("easybackup.failed.rollback_hint"),
					this.width / 2, this.height / 2 + 30, 0xFFAAAAAA);
		}
	}

	@Override
	public void onClose() {
		BackupManager.cancelRestore(this.flow);
	}
}
