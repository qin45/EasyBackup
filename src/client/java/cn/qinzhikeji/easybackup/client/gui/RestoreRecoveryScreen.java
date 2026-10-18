package cn.qinzhikeji.easybackup.client.gui;

import cn.qinzhikeji.easybackup.backup.RestoreJournal;
import cn.qinzhikeji.easybackup.client.BackupManager;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 启动时发现上次回档未完成/未清理时的恢复界面。
 */
public class RestoreRecoveryScreen extends Screen {
	private final Screen parent;
	private final RestoreJournal journal;

	public RestoreRecoveryScreen(Screen parent, RestoreJournal journal) {
		super(Component.translatable("easybackup.title.recovery"));
		this.parent = parent;
		this.journal = journal;
	}

	@Override
	protected void init() {
		int buttonWidth = 140;
		int gap = 6;
		int count = 2;
		int totalWidth = count * buttonWidth + (count - 1) * gap;
		int x = (this.width - totalWidth) / 2;
		int y = this.height / 2 + 40;

		switch (this.journal.phase()) {
			case COPYING -> {
				addRenderableWidget(Button.builder(Component.translatable("easybackup.button.redo_restore"),
								button -> BackupManager.redoRecovery(this.journal, this.parent))
						.bounds(x, y, buttonWidth, 20).build());
				addRenderableWidget(Button.builder(Component.translatable("easybackup.button.cleanup"),
								button -> BackupManager.completeRecovery(this.journal, this.parent))
						.bounds(x + buttonWidth + gap, y, buttonWidth, 20).build());
			}
			case SWAPPING -> {
				addRenderableWidget(Button.builder(Component.translatable("easybackup.button.continue_restore"),
								button -> BackupManager.continueRecovery(this.journal, this.parent))
						.bounds(x, y, buttonWidth, 20).build());
				addRenderableWidget(Button.builder(Component.translatable("easybackup.button.rollback"),
								button -> BackupManager.rollbackRecovery(this.journal, this.parent))
						.bounds(x + buttonWidth + gap, y, buttonWidth, 20).build());
			}
			case SWAPPED -> {
				addRenderableWidget(Button.builder(Component.translatable("easybackup.button.delete_old"),
								button -> BackupManager.completeRecovery(this.journal, this.parent))
						.bounds(x, y, buttonWidth, 20).build());
				addRenderableWidget(Button.builder(Component.translatable("easybackup.button.rollback"),
								button -> BackupManager.rollbackRecovery(this.journal, this.parent))
						.bounds(x + buttonWidth + gap, y, buttonWidth, 20).build());
			}
		}

		addRenderableWidget(Button.builder(Component.translatable("easybackup.button.later"),
						button -> onClose())
				.bounds(this.width / 2 - 50, y + 30, 100, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(gui, mouseX, mouseY, partialTick);
		gui.centeredText(this.font, this.title, this.width / 2, 40, 0xFFFFFFFF);
		String name = this.journal.levelName == null ? this.journal.levelId : this.journal.levelName;
		gui.centeredText(this.font, Component.literal(name), this.width / 2, 70, 0xFFFFD700);

		Component description = switch (this.journal.phase()) {
			case COPYING -> Component.translatable("easybackup.recovery.copying");
			case SWAPPING -> Component.translatable("easybackup.recovery.swapping");
			case SWAPPED -> Component.translatable("easybackup.recovery.swapped");
		};
		int textWidth = Math.min(460, this.width - 60);
		gui.textWithWordWrap(this.font, description, (this.width - textWidth) / 2,
				this.height / 2 - 40, textWidth, 0xFFCCCCCC);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
