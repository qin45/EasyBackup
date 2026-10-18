package cn.qinzhikeji.easybackup.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * 回档进度界面。所有字段由后台线程写入，渲染时读取；使用 volatile 保证可见性。
 */
public class RestoreProgressScreen extends Screen {
	private volatile Component stageText = Component.translatable("easybackup.stage.preparing");
	private volatile long done;
	private volatile long total;

	public RestoreProgressScreen() {
		super(Component.translatable("easybackup.title.restore"));
	}

	public void setStageKey(String translationKey) {
		this.stageText = Component.translatable(translationKey);
	}

	public void setProgress(long done, long total) {
		this.done = done;
		this.total = total;
	}

	private int percent() {
		if (this.total <= 0) {
			return 0;
		}
		return (int) Math.max(0, Math.min(100, this.done * 100 / this.total));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(gui, mouseX, mouseY, partialTick);

		gui.centeredText(this.font, this.title, this.width / 2, 30, 0xFFFFFFFF);
		gui.centeredText(this.font, Component.translatable("easybackup.restore.do_not_close"),
				this.width / 2, 52, 0xFFFF5555);
		gui.centeredText(this.font, this.stageText, this.width / 2, 76, 0xFFFFD700);

		int barWidth = Math.min(400, this.width - 80);
		int barHeight = 20;
		int x = (this.width - barWidth) / 2;
		int y = 104;

		gui.fill(x - 2, y - 2, x + barWidth + 2, y + barHeight + 2, 0xFF000000);
		gui.fill(x, y, x + barWidth, y + barHeight, 0xFF303030);
		int filled = barWidth * percent() / 100;
		if (filled > 0) {
			gui.fill(x, y, x + filled, y + barHeight, 0xFF55FF55);
		}
		gui.centeredText(this.font, Component.literal(percent() + "%"),
				this.width / 2, y + barHeight + 10, 0xFFFFFFFF);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return true;
	}
}
