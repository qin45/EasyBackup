package cn.qinzhikeji.easybackup.client.gui;

import cn.qinzhikeji.easybackup.backup.BackupInfo;
import cn.qinzhikeji.easybackup.client.BackupManager;
import cn.qinzhikeji.easybackup.client.WorldRef;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * 回档确认界面：可选择在回档前是否先把当前存档增量备份一次。
 */
public class RestoreConfirmScreen extends Screen {
	private final Screen parent;
	private final WorldRef world;
	private final BackupInfo backup;
	private Checkbox backupCheckbox;

	public RestoreConfirmScreen(Screen parent, WorldRef world, BackupInfo backup) {
		super(Component.translatable("easybackup.title.restore_confirm"));
		this.parent = parent;
		this.world = world;
		this.backup = backup;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int y = this.height / 2 - 10;

		this.backupCheckbox = Checkbox.builder(
						Component.translatable("easybackup.checkbox.backup_current"), this.font)
				.pos(centerX - 100, y)
				.selected(true)
				.build();
		addRenderableWidget(this.backupCheckbox);

		addRenderableWidget(Button.builder(Component.translatable("easybackup.button.cancel"),
						button -> onClose())
				.bounds(centerX - 104, y + 40, 100, 20).build());
		addRenderableWidget(DangerButton.create(
				Component.translatable("easybackup.button.start_restore"),
				button -> start(), centerX + 4, y + 40, 100, 20));
	}

	private void start() {
		boolean selected = this.backupCheckbox != null && this.backupCheckbox.selected();
		if (!selected) {
			this.minecraft.gui.setScreen(new EasyBackupConfirmScreen(result -> {
				if (result) {
					BackupManager.startRestore(this.world, this.backup, false, this.parent);
				} else {
					this.minecraft.gui.setScreen(this);
				}
			}, Component.translatable("easybackup.confirm.no_backup.title"),
					Component.translatable("easybackup.confirm.no_backup.message"),
					Component.translatable("easybackup.button.continue_restore"),
					Component.translatable("easybackup.button.back")));
			return;
		}
		BackupManager.startRestore(this.world, this.backup, true, this.parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(gui, mouseX, mouseY, partialTick);
		gui.centeredText(this.font, this.title, this.width / 2, 40, 0xFFFFFFFF);

		int textWidth = Math.min(440, this.width - 60);
		Component message = Component.translatable("easybackup.confirm.restore.message",
				this.backup.name(), this.world.levelName());
		List<FormattedCharSequence> lines = this.font.split(message, textWidth);
		int textY = this.height / 2 - 70;
		for (FormattedCharSequence line : lines) {
			gui.centeredText(this.font, line, this.width / 2, textY, 0xFFCCCCCC);
			textY += 11;
		}

		if (this.world.locked()) {
			gui.centeredText(this.font, Component.translatable("easybackup.error.world_locked"),
					this.width / 2, this.height / 2 + 20, 0xFFFF5555);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
