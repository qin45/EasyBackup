package cn.qinzhikeji.easybackup.client.gui;

import cn.qinzhikeji.easybackup.EasyBackup;
import cn.qinzhikeji.easybackup.backup.BackupEngine;
import cn.qinzhikeji.easybackup.backup.BackupException;
import cn.qinzhikeji.easybackup.backup.BackupInfo;
import cn.qinzhikeji.easybackup.backup.BackupPaths;
import cn.qinzhikeji.easybackup.backup.BackupType;
import cn.qinzhikeji.easybackup.backup.FileOps;
import cn.qinzhikeji.easybackup.client.BackupManager;
import cn.qinzhikeji.easybackup.client.WorldRef;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 备份管理主界面：全量/增量备份按钮、增量占用统计与备份列表。
 */
public class BackupScreen extends Screen {
	private final Screen parent;
	private final WorldRef world;
	private final BackupEngine engine;
	private BackupListWidget list;
	private Button fullButton;
	private Button incrementalButton;
	private long incrementalUsage;
	private Component loadError;

	public BackupScreen(Screen parent, WorldRef world) {
		super(Component.translatable("easybackup.title.backup"));
		this.parent = parent;
		this.world = world;
		this.engine = new BackupEngine(BackupManager.easyBackupRoot(net.minecraft.client.Minecraft.getInstance()));
	}

	public WorldRef world() {
		return this.world;
	}

	@Override
	protected void init() {
		this.fullButton = Button.builder(Component.translatable("easybackup.button.full_backup"),
						button -> openNameDialog(BackupType.FULL))
				.bounds(this.width / 2 - 125, 28, 120, 20)
				.build();
		this.incrementalButton = Button.builder(Component.translatable("easybackup.button.incremental_backup"),
						button -> openNameDialog(BackupType.INCREMENTAL))
				.bounds(this.width / 2 + 5, 28, 120, 20)
				.build();
		addRenderableWidget(this.fullButton);
		addRenderableWidget(this.incrementalButton);

		int listTop = 68;
		int listHeight = Math.max(60, this.height - listTop - 52);
		this.list = new BackupListWidget(this, this.minecraft,
				this.width - 40, listHeight, listTop, 40);
		this.list.updateSizeAndPosition(this.width - 40, listHeight, 20, listTop);
		addRenderableWidget(this.list);

		addRenderableWidget(Button.builder(Component.translatable("easybackup.button.back"),
						button -> onClose())
				.bounds(this.width / 2 - 50, this.height - 28, 100, 20)
				.build());

		reloadBackups();
	}

	public void reloadBackups() {
		if (this.list == null) {
			return;
		}
		try {
			List<BackupInfo> backups = this.engine.listBackups(this.world.levelId());
			long usage = this.engine.incrementalUsage(this.world.levelId());
			this.list.reload(backups);
			this.incrementalUsage = usage;
			this.loadError = null;
		} catch (RuntimeException e) {
			EasyBackup.LOGGER.error("读取备份列表失败: levelId={}", this.world.levelId(), e);
			this.list.reload(List.of());
			this.loadError = Component.translatable("easybackup.error.backup_list", describe(e));
		}
		boolean busy = BackupManager.isJobActive();
		boolean usable = !busy && this.loadError == null;
		if (this.fullButton != null) {
			this.fullButton.active = usable;
		}
		if (this.incrementalButton != null) {
			this.incrementalButton.active = usable;
		}
	}

	private void openNameDialog(BackupType type) {
		try {
			String suggested = this.engine.suggestName(this.world.levelId(),
					this.world.levelName(), LocalDate.now());
			this.minecraft.gui.setScreen(new NameInputScreen(this,
					Component.translatable("easybackup.title.name_input"), suggested,
					this::validateNewName,
					name -> BackupManager.startBackup(this.world, type, name)));
		} catch (RuntimeException e) {
			EasyBackup.LOGGER.error("无法开始备份: levelId={}", this.world.levelId(), e);
			this.loadError = Component.translatable("easybackup.error.start_backup", describe(e));
		}
	}

	private static String describe(Throwable throwable) {
		String message = throwable.getMessage();
		if (message == null || message.isBlank()) {
			return throwable.getClass().getSimpleName();
		}
		return message;
	}

	private Component validateNewName(String name) {
		if (!BackupPaths.isValidName(name)) {
			return Component.translatable("easybackup.name.invalid");
		}
		if (this.engine.backupExists(this.world.levelId(), name)) {
			return Component.translatable("easybackup.name.exists");
		}
		return null;
	}

	public void openRestore(BackupInfo backup) {
		this.minecraft.gui.setScreen(new RestoreConfirmScreen(this, this.world, backup));
	}

	public void openRename(BackupInfo backup) {
		this.minecraft.gui.setScreen(new NameInputScreen(this,
				Component.translatable("easybackup.title.rename"), backup.name(),
				name -> {
					if (!BackupPaths.isValidName(name)) {
						return Component.translatable("easybackup.name.invalid");
					}
					if (!name.equals(backup.name())
							&& this.engine.backupExists(this.world.levelId(), name)) {
						return Component.translatable("easybackup.name.exists");
					}
					return null;
				},
				name -> {
					try {
						this.engine.renameBackup(this.world.levelId(), backup, name);
					} catch (BackupException e) {
						this.minecraft.gui.setScreen(new MessageScreen(this,
								Component.translatable("easybackup.title.error"),
								Component.literal(e.getMessage())));
						return;
					}
					reloadBackups();
				}));
	}

	public void confirmDelete(BackupInfo backup) {
		Component title = Component.translatable(backup.type() == BackupType.FULL
				? "easybackup.confirm.delete_full.title"
				: "easybackup.confirm.delete_incremental.title");
		Component message = Component.translatable(backup.type() == BackupType.FULL
						? "easybackup.confirm.delete_full.message"
						: "easybackup.confirm.delete_incremental.message",
				backup.name());
		this.minecraft.gui.setScreen(new EasyBackupConfirmScreen(result -> {
			if (result) {
				try {
					this.engine.deleteBackup(backup);
				} catch (BackupException e) {
					this.minecraft.gui.setScreen(new MessageScreen(this,
							Component.translatable("easybackup.title.error"),
							Component.literal(e.getMessage())));
					return;
				}
				reloadBackups();
			}
			this.minecraft.gui.setScreen(this);
		}, title, message,
				Component.translatable("easybackup.button.delete"),
				Component.translatable("easybackup.button.cancel")));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(gui, mouseX, mouseY, partialTick);
		boolean busy = BackupManager.isJobActive() || this.loadError != null;
		if (this.fullButton != null) {
			this.fullButton.active = !busy;
		}
		if (this.incrementalButton != null) {
			this.incrementalButton.active = !busy;
		}
		gui.centeredText(this.font, this.title, this.width / 2, 11, 0xFFFFFFFF);
		gui.centeredText(this.font, Component.translatable("easybackup.usage.incremental",
				FileOps.formatSize(this.incrementalUsage)), this.width / 2, 54, 0xFFB0B0B0);

		if (this.loadError != null) {
			gui.centeredText(this.font, this.loadError, this.width / 2, 92, 0xFFFF5555);
		} else if (this.list != null && this.list.isEmpty()) {
			gui.centeredText(this.font, Component.translatable("easybackup.list.empty"),
					this.width / 2, 92, 0xFF909090);
		}

		BackupManager.BackupJob job = BackupManager.currentJob();
		Component status;
		if (job != null && job.world().levelId().equals(this.world.levelId())) {
			status = Component.translatable("easybackup.status.backing_up",
					job.name(),
					Component.translatable(BackupManager.stageKey(job.stage())),
					job.percent() + "%");
		} else {
			status = BackupManager.lastStatus();
		}
		gui.centeredText(this.font, status, this.width / 2, this.height - 44, 0xFFCCCCCC);
	}

	@Override
	public boolean isPauseScreen() {
		return !(this.world.current() && BackupManager.isJobActive());
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
