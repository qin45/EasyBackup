package cn.qinzhikeji.easybackup.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 通用提示界面：标题 + 自动换行的正文 + 确定按钮。
 */
public class MessageScreen extends Screen {
	private final Screen parent;
	private final Component message;

	public MessageScreen(Screen parent, Component title, Component message) {
		super(title);
		this.parent = parent;
		this.message = message;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("easybackup.button.ok"),
						button -> onClose())
				.bounds(this.width / 2 - 50, this.height / 2 + 50, 100, 20)
				.build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(gui, mouseX, mouseY, partialTick);
		gui.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 60, 0xFFFFFFFF);
		gui.textWithWordWrap(this.font, this.message,
				this.width / 2 - Math.min(200, this.width / 2 - 20),
				this.height / 2 - 35,
				Math.min(400, this.width - 40), 0xFFCCCCCC);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
