package cn.qinzhikeji.easybackup.client.gui;

import cn.qinzhikeji.easybackup.backup.RestorePlan;
import cn.qinzhikeji.easybackup.client.WorldRef;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 回档缺失文件提示。关键文件缺失时只能取消。
 */
public class MissingFilesScreen extends Screen {
	private final RestorePlan plan;
	private final Screen fallback;
	private final CompletableFuture<Boolean> decision;
	private boolean completed;
	private MissingListWidget list;

	public MissingFilesScreen(WorldRef world, RestorePlan plan, Screen fallback,
							  CompletableFuture<Boolean> decision) {
		super(Component.translatable("easybackup.title.missing_files"));
		this.plan = plan;
		this.fallback = fallback;
		this.decision = decision;
	}

	@Override
	protected void init() {
		int listTop = 66;
		int listHeight = Math.max(40, this.height - listTop - 44);
		this.list = new MissingListWidget(this.minecraft,
				this.width - 40, listHeight, listTop, 18);
		this.list.updateSizeAndPosition(this.width - 40, listHeight, 20, listTop);
		this.list.reload(this.plan.missingFiles());
		addRenderableWidget(this.list);

		if (this.plan.canContinue()) {
			addRenderableWidget(DangerButton.create(
					Component.translatable("easybackup.button.continue_anyway"),
					button -> complete(true), this.width / 2 - 105, this.height - 30, 100, 20));
		}
		addRenderableWidget(Button.builder(
						this.plan.canContinue()
								? Component.translatable("easybackup.button.cancel")
								: Component.translatable("easybackup.button.cancel_restore"),
						button -> complete(false))
				.bounds(this.plan.canContinue() ? this.width / 2 + 5 : this.width / 2 - 50,
						this.height - 30, 100, 20)
				.build());
	}

	private void complete(boolean proceed) {
		if (this.completed) {
			return;
		}
		this.completed = true;
		if (this.decision != null) {
			this.decision.complete(proceed);
		}
		if (!proceed) {
			this.minecraft.gui.setScreen(this.fallback);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(gui, mouseX, mouseY, partialTick);
		gui.centeredText(this.font, this.title, this.width / 2, 18, 0xFFFFFFFF);
		Component summary = this.plan.canContinue()
				? Component.translatable("easybackup.missing.summary", this.plan.missingFiles().size())
				: Component.translatable("easybackup.missing.critical",
				this.plan.criticalError() == null ? "" : this.plan.criticalError());
		gui.textWithWordWrap(this.font, summary, 20, 38, this.width - 40,
				this.plan.canContinue() ? 0xFFFFAA00 : 0xFFFF5555);
	}

	@Override
	public void onClose() {
		complete(false);
	}

	private static final class MissingListWidget extends ObjectSelectionList<MissingEntry> {
		MissingListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight) {
			super(minecraft, width, height, y, itemHeight);
		}

		void reload(java.util.List<String> missing) {
			clearEntries();
			for (String path : missing) {
				addEntry(new MissingEntry(path));
			}
		}

		@Override
		public int getRowWidth() {
			return Math.max(80, this.width - 10);
		}
	}

	private static final class MissingEntry extends ObjectSelectionList.Entry<MissingEntry> {
		private final String path;

		MissingEntry(String path) {
			this.path = path;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor gui, int mouseX, int mouseY,
								   boolean hovered, float partialTick) {
			Minecraft minecraft = Minecraft.getInstance();
			String text = minecraft.font.plainSubstrByWidth(this.path,
					Math.max(20, this.getContentWidth() - 6));
			gui.text(minecraft.font, text, this.getContentX() + 3, this.getContentY() + 5,
					0xFFFFAAAA);
		}

		@Override
		public Component getNarration() {
			return Component.literal(this.path);
		}
	}
}
