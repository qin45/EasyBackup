package cn.qinzhikeji.easybackup.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.world.level.storage.LevelSummary;

import java.util.List;

/**
 * 主菜单存档列表容器。
 */
public class WorldListWidget extends ContainerObjectSelectionList<WorldListEntry> {
	private final BackupWorldSelectScreen screen;

	public WorldListWidget(BackupWorldSelectScreen screen, Minecraft minecraft,
						   int width, int height, int y, int itemHeight) {
		super(minecraft, width, height, y, itemHeight);
		this.screen = screen;
	}

	public void reload(List<LevelSummary> levels) {
		clearEntries();
		for (LevelSummary level : levels) {
			addEntry(new WorldListEntry(this.screen, level, this.screen.worldOf(level)));
		}
	}

	public boolean isEmpty() {
		return getItemCount() == 0;
	}

	@Override
	public int getRowWidth() {
		return Math.max(160, this.width - 12);
	}
}
