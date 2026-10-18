package cn.qinzhikeji.easybackup.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 危险操作按钮（删除、回档等）：在普通按钮上叠加红色底色，使其更醒目。
 * 按钮不可用时保持原版的灰色外观。
 */
public final class DangerButton extends Button {
	private static final int TINT = 0x66B02020;

	private DangerButton(int x, int y, int width, int height, Component message, OnPress onPress) {
		super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
	}

	public static DangerButton create(Component message, OnPress onPress,
									  int x, int y, int width, int height) {
		return new DangerButton(x, y, width, height, message, onPress);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
		extractDefaultSprite(gui);
		if (this.active) {
			gui.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, TINT);
		}
		extractDefaultLabel(gui.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
	}
}
