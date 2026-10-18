package cn.qinzhikeji.easybackup.client.gui;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

/**
 * 与 {@link ConfirmScreen} 布局一致，但确认按钮使用红色的危险操作样式。
 */
public class EasyBackupConfirmScreen extends ConfirmScreen {
	public EasyBackupConfirmScreen(BooleanConsumer callback, Component title, Component message,
								   Component confirmLabel, Component cancelLabel) {
		super(callback, title, message, confirmLabel, cancelLabel);
	}

	@Override
	protected void addButtons(LinearLayout layout) {
		this.yesButton = (Button) layout.addChild(DangerButton.create(this.yesButtonComponent,
				button -> this.callback.accept(true), 0, 0, Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT));
		this.noButton = (Button) layout.addChild(Button.builder(this.noButtonComponent,
				button -> this.callback.accept(false)).build());
	}
}
