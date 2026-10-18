package cn.qinzhikeji.easybackup.client.gui;

import cn.qinzhikeji.easybackup.client.WorldRef;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 主菜单的存档选择界面：选中一个存档后进入它的备份列表。
 */
public class BackupWorldSelectScreen extends Screen {
	private final Screen parent;
	private final List<LevelSummary> levels = new ArrayList<>();
	private WorldListWidget list;
	private boolean loading = true;
	private Component loadError;

	public BackupWorldSelectScreen(Screen parent) {
		super(Component.translatable("easybackup.title.world_select"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int listTop = 36;
		int listHeight = Math.max(60, this.height - listTop - 40);
		this.list = new WorldListWidget(this, this.minecraft,
				this.width - 40, listHeight, listTop, 36);
		this.list.updateSizeAndPosition(this.width - 40, listHeight, 20, listTop);
		addRenderableWidget(this.list);

		addRenderableWidget(Button.builder(Component.translatable("easybackup.button.back"),
						button -> onClose())
				.bounds(this.width / 2 - 50, this.height - 28, 100, 20).build());

		loadWorlds();
	}

	private void loadWorlds() {
		this.loading = true;
		this.loadError = null;
		this.levels.clear();
		if (this.list != null) {
			this.list.reload(this.levels);
		}
		CompletableFuture.supplyAsync(() -> {
			try {
				LevelStorageSource source = Minecraft.getInstance().getLevelSource();
				LevelStorageSource.LevelCandidates candidates = source.findLevelCandidates();
				return source.loadLevelSummaries(candidates).join();
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}).whenComplete((loaded, error) -> Minecraft.getInstance().execute(() -> {
			this.loading = false;
			if (error != null) {
				Throwable cause = error instanceof CompletionException && error.getCause() != null
						? error.getCause() : error;
				this.loadError = Component.literal(String.valueOf(cause.getMessage()));
				return;
			}
			List<LevelSummary> sorted = new ArrayList<>(loaded);
			sorted.sort(Comparator.comparingLong(LevelSummary::getLastPlayed).reversed());
			this.levels.clear();
			this.levels.addAll(sorted);
			if (this.list != null) {
				this.list.reload(this.levels);
			}
		}));
	}

	public WorldRef worldOf(LevelSummary summary) {
		return WorldRef.fromSummary(this.minecraft, summary);
	}

	public void openBackups(WorldRef world) {
		this.minecraft.gui.setScreen(new BackupScreen(this, world));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(gui, mouseX, mouseY, partialTick);
		gui.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

		if (this.loading) {
			gui.centeredText(this.font, Component.translatable("easybackup.list.loading"),
					this.width / 2, this.height / 2, 0xFFAAAAAA);
		} else if (this.loadError != null) {
			gui.centeredText(this.font, Component.translatable("easybackup.error.load_worlds"),
					this.width / 2, this.height / 2, 0xFFFF5555);
			int textWidth = Math.min(440, this.width - 60);
			gui.textWithWordWrap(this.font, this.loadError, (this.width - textWidth) / 2,
					this.height / 2 + 14, textWidth, 0xFFFF9999);
		} else if (this.list != null && this.list.isEmpty()) {
			gui.centeredText(this.font, Component.translatable("easybackup.list.no_worlds"),
					this.width / 2, this.height / 2, 0xFF909090);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
