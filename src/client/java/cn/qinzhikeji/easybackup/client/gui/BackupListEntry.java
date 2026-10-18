package cn.qinzhikeji.easybackup.client.gui;

import cn.qinzhikeji.easybackup.backup.BackupInfo;
import cn.qinzhikeji.easybackup.backup.BackupType;
import cn.qinzhikeji.easybackup.backup.FileOps;
import cn.qinzhikeji.easybackup.client.BackupManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 备份列表的一行：名称、类型、时间、大小，以及回档/重命名/删除按钮。
 */
public class BackupListEntry extends ContainerObjectSelectionList.Entry<BackupListEntry> {
	private static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private final BackupScreen screen;
	private final BackupInfo backup;
	private final Button restoreButton;
	private final Button renameButton;
	private final Button deleteButton;
	private final List<AbstractWidget> children;

	public BackupListEntry(BackupScreen screen, BackupInfo backup) {
		this.screen = screen;
		this.backup = backup;
		this.restoreButton = Button.builder(Component.translatable("easybackup.button.restore"),
						button -> screen.openRestore(backup))
				.size(48, 20).build();
		this.renameButton = Button.builder(Component.translatable("easybackup.button.rename"),
						button -> screen.openRename(backup))
				.size(48, 20).build();
		this.deleteButton = DangerButton.create(Component.translatable("easybackup.button.delete"),
				button -> screen.confirmDelete(backup), 0, 0, 48, 20);
		this.children = List.of(this.restoreButton, this.renameButton, this.deleteButton);
		layout();
	}

	private void layout() {
		int right = getContentRight();
		int y = getContentY() + Math.max(0, (getContentHeight() - 20) / 2);
		this.deleteButton.setX(right - 4 - 48);
		this.deleteButton.setY(y);
		this.renameButton.setX(this.deleteButton.getX() - 52);
		this.renameButton.setY(y);
		this.restoreButton.setX(this.renameButton.getX() - 52);
		this.restoreButton.setY(y);
	}

	@Override
	public void extractContent(GuiGraphicsExtractor gui, int mouseX, int mouseY,
							   boolean hovered, float partialTick) {
		layout();
		boolean enabled = !BackupManager.isJobActive();
		this.restoreButton.active = enabled;
		this.renameButton.active = enabled;
		this.deleteButton.active = enabled;

		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		int x = getContentX() + 5;
		int y = getContentY() + 6;
		int maxTextWidth = Math.max(40, this.restoreButton.getX() - x - 8);

		String name = font.plainSubstrByWidth(this.backup.name(), maxTextWidth);
		gui.text(font, name, x, y, 0xFFFFFFFF);

		Component meta = Component.translatable("easybackup.list.meta",
				BackupManager.typeName(this.backup.type()),
				TIME_FORMAT.format(this.backup.createdAt()),
				FileOps.formatSize(this.backup.displayBytes()));
		String metaText = font.plainSubstrByWidth(meta.getString(), maxTextWidth);
		gui.text(font, metaText, x, y + 12,
				this.backup.type() == BackupType.FULL ? 0xFF9FD8FF : 0xFFB8E6A0);

		this.restoreButton.extractRenderState(gui, mouseX, mouseY, partialTick);
		this.renameButton.extractRenderState(gui, mouseX, mouseY, partialTick);
		this.deleteButton.extractRenderState(gui, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		layout();
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return this.children;
	}

	@Override
	public List<? extends AbstractWidget> children() {
		return this.children;
	}
}
