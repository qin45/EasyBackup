package cn.qinzhikeji.easybackup.client.gui;

import cn.qinzhikeji.easybackup.backup.BackupPaths;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 备份命名/重命名对话框。
 */
public class NameInputScreen extends Screen {
	@FunctionalInterface
	public interface Validator {
		Component validate(String name);
	}

	private final Screen parent;
	private final String initialValue;
	private final Validator validator;
	private final Consumer<String> onConfirm;
	private EditBox editBox;
	private Button confirmButton;
	private Component error;

	public NameInputScreen(Screen parent, Component title, String initialValue,
						   Validator validator, Consumer<String> onConfirm) {
		super(title);
		this.parent = parent;
		this.initialValue = initialValue == null ? "" : initialValue;
		this.validator = validator;
		this.onConfirm = onConfirm;
	}

	@Override
	protected void init() {
		this.editBox = new EditBox(this.font, this.width / 2 - 120, this.height / 2 - 10,
				240, 20, Component.translatable("easybackup.title.name_input"));
		this.editBox.setMaxLength(BackupPaths.MAX_NAME_LENGTH);
		addRenderableWidget(this.editBox);

		this.confirmButton = Button.builder(Component.translatable("easybackup.button.confirm"),
						button -> confirm())
				.bounds(this.width / 2 - 105, this.height / 2 + 40, 100, 20)
				.build();
		addRenderableWidget(this.confirmButton);
		addRenderableWidget(Button.builder(Component.translatable("easybackup.button.cancel"),
						button -> onClose())
				.bounds(this.width / 2 + 5, this.height / 2 + 40, 100, 20)
				.build());

		this.editBox.setValue(this.initialValue);
		this.editBox.setResponder(this::updateValidation);
		this.setInitialFocus(this.editBox);
		updateValidation(this.editBox.getValue());
	}

	private void updateValidation(String value) {
		Component newError;
		if (value == null || value.isBlank()) {
			newError = Component.translatable("easybackup.name.empty");
		} else {
			newError = this.validator.validate(value);
		}
		this.error = newError;
		if (this.confirmButton != null) {
			this.confirmButton.active = newError == null;
		}
	}

	private void confirm() {
		String value = this.editBox.getValue();
		updateValidation(value);
		if (this.error != null) {
			return;
		}
		this.minecraft.gui.setScreen(this.parent);
		this.onConfirm.accept(value);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			if (this.confirmButton != null && this.confirmButton.active) {
				confirm();
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(gui, mouseX, mouseY, partialTick);
		gui.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 50, 0xFFFFFFFF);
		if (this.error != null) {
			gui.centeredText(this.font, this.error, this.width / 2, this.height / 2 + 16, 0xFFFF5555);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
