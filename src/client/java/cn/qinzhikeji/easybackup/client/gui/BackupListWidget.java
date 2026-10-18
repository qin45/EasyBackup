package cn.qinzhikeji.easybackup.client.gui;

import cn.qinzhikeji.easybackup.backup.BackupInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;

import java.util.List;

/**
 * 备份列表容器。
 */
public class BackupListWidget extends ContainerObjectSelectionList<BackupListEntry> {
	private final BackupScreen screen;

	public BackupListWidget(BackupScreen screen, Minecraft minecraft,
							int width, int height, int y, int itemHeight) {
		super(minecraft, width, height, y, itemHeight);
		this.screen = screen;
	}

	public void reload(List<BackupInfo> backups) {
		clearEntries();
		for (BackupInfo backup : backups) {
			addEntry(new BackupListEntry(this.screen, backup));
		}
	}

	public boolean isEmpty() {
		return getItemCount() == 0;
	}

	@Override
	public int getRowWidth() {
		return Math.max(120, this.width - 12);
	}
}
