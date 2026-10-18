package cn.qinzhikeji.easybackup.client.gui;

import cn.qinzhikeji.easybackup.client.WorldRef;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 主菜单存档列表的一行：存档名称、信息与「查看备份」按钮。
 */
public class WorldListEntry extends ContainerObjectSelectionList.Entry<WorldListEntry> {
	private static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private final BackupWorldSelectScreen screen;
	private final LevelSummary summary;
	private final WorldRef world;
	private final Button openButton;
	private final List<AbstractWidget> children;

	public WorldListEntry(BackupWorldSelectScreen screen, LevelSummary summary, WorldRef world) {
		this.screen = screen;
		this.summary = summary;
		this.world = world;
		this.openButton = Button.builder(Component.translatable("easybackup.button.view_backups"),
						button -> screen.openBackups(this.world))
				.size(84, 20)
				.build();
		this.children = List.of(this.openButton);
		layout();
	}

	private void layout() {
		this.openButton.setX(getContentRight() - 4 - 84);
		this.openButton.setY(getContentY() + Math.max(0, (getContentHeight() - 20) / 2));
	}

	@Override
	public void extractContent(GuiGraphicsExtractor gui, int mouseX, int mouseY,
							   boolean hovered, float partialTick) {
		layout();
		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		int x = getContentX() + 5;
		int y = getContentY() + 6;
		int maxTextWidth = Math.max(60, this.openButton.getX() - x - 8);

		String name = font.plainSubstrByWidth(this.summary.getLevelName(), maxTextWidth);
		gui.text(font, name, x, y, this.summary.isLocked() ? 0xFFFF8080 : 0xFFFFFFFF);

		String meta = TIME_FORMAT.format(Instant.ofEpochMilli(this.summary.getLastPlayed()))
				+ "  " + this.summary.getInfo().getString();
		if (this.summary.isLocked()) {
			meta = Component.translatable("easybackup.list.locked").getString() + "  " + meta;
		}
		gui.text(font, font.plainSubstrByWidth(meta, maxTextWidth), x, y + 12, 0xFFA0A0A0);

		this.openButton.extractRenderState(gui, mouseX, mouseY, partialTick);
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
