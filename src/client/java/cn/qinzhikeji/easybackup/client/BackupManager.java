package cn.qinzhikeji.easybackup.client;

import cn.qinzhikeji.easybackup.EasyBackup;
import cn.qinzhikeji.easybackup.backup.BackupEngine;
import cn.qinzhikeji.easybackup.backup.BackupException;
import cn.qinzhikeji.easybackup.backup.BackupIndex;
import cn.qinzhikeji.easybackup.backup.BackupInfo;
import cn.qinzhikeji.easybackup.backup.BackupType;
import cn.qinzhikeji.easybackup.backup.CancelledException;
import cn.qinzhikeji.easybackup.backup.FileOps;
import cn.qinzhikeji.easybackup.backup.ProgressListener;
import cn.qinzhikeji.easybackup.backup.ProgressStage;
import cn.qinzhikeji.easybackup.backup.RestoreEngine;
import cn.qinzhikeji.easybackup.backup.RestoreJournal;
import cn.qinzhikeji.easybackup.backup.RestorePlan;
import cn.qinzhikeji.easybackup.client.gui.BackupScreen;
import cn.qinzhikeji.easybackup.client.gui.MessageScreen;
import cn.qinzhikeji.easybackup.client.gui.MissingFilesScreen;
import cn.qinzhikeji.easybackup.client.gui.RestoreFailedScreen;
import cn.qinzhikeji.easybackup.client.gui.RestoreProgressScreen;
import cn.qinzhikeji.easybackup.client.gui.RestoreRecoveryScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 备份/回档任务调度、自动保存控制与聊天栏通知。
 * 所有 UI 状态更新都通过 {@link Minecraft#execute(Runnable)} 回到客户端线程执行。
 */
public final class BackupManager {
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "EasyBackup");
		thread.setDaemon(true);
		return thread;
	});
	private static final Set<MinecraftServer> SUPPRESSED =
			ConcurrentHashMap.newKeySet();
	private static final AtomicBoolean RECOVERY_CHECKED = new AtomicBoolean();

	private static volatile BackupJob job;
	private static volatile boolean restoreActive;
	private static volatile RestoreJournal pendingJournal;
	private static volatile Component pendingNotice;
	private static volatile Component lastStatus = Component.translatable("easybackup.status.ready");

	private BackupManager() {
	}

	// ---------------------------------------------------------------- 公共状态

	public static boolean isJobActive() {
		return job != null;
	}

	public static BackupJob currentJob() {
		return job;
	}

	public static Component lastStatus() {
		return lastStatus;
	}

	public static boolean isSuppressed(MinecraftServer server) {
		return SUPPRESSED.contains(server);
	}

	public static Path easyBackupRoot(Minecraft minecraft) {
		return minecraft.gameDirectory.toPath().resolve("easybackup");
	}

	public static Component typeName(BackupType type) {
		return Component.translatable(type == BackupType.FULL
				? "easybackup.type.full"
				: "easybackup.type.incremental");
	}

	// ---------------------------------------------------------------- 备份

	public static void startBackup(WorldRef world, BackupType type, String name) {
		Minecraft minecraft = Minecraft.getInstance();
		if (job != null || restoreActive) {
			return;
		}
		BackupJob newJob = new BackupJob(world, type, name);
		job = newJob;
		Component startMessage = Component.translatable(
				world.server() != null
						? "easybackup.chat.backup_start"
						: "easybackup.chat.backup_start_menu",
				typeName(type), name);
		lastStatus = startMessage;
		if (minecraft.level != null) {
			chat(startMessage);
		}
		if (world.server() != null) {
			startServerSuppression(newJob);
		} else {
			newJob.started.countDown();
		}
		EXECUTOR.execute(() -> runBackup(newJob));
	}

	private static void startServerSuppression(BackupJob runningJob) {
		MinecraftServer server = runningJob.world.server();
		if (server == null || !server.isRunning()) {
			runningJob.started.countDown();
			return;
		}
		server.executeIfPossible(() -> {
			try {
				if (runningJob.cancelled.get()) {
					return;
				}
				runningJob.previousAutoSave = server.isAutoSave();
				// 强制保存一次，保证备份看到的是已落盘的完整状态
				server.saveEverything(true, true, true);
				SUPPRESSED.add(server);
				runningJob.suppressInstalled = true;
				server.setAutoSave(false);
			} catch (Throwable throwable) {
				EasyBackup.LOGGER.error("Failed to suppress autosave", throwable);
			} finally {
				runningJob.started.countDown();
			}
		});
	}

	private static void runBackup(BackupJob runningJob) {
		Minecraft minecraft = Minecraft.getInstance();
		try {
			if (!runningJob.started.await(10, TimeUnit.MINUTES)) {
				throw new BackupException("等待游戏保存超时，备份已取消");
			}
			if (runningJob.cancelled.get()) {
				throw new CancelledException("备份已取消");
			}
			ProgressListener listener = new ProgressListener() {
				@Override
				public void onProgress(ProgressStage stage, long done, long total) {
					runningJob.update(stage, done, total);
				}

				@Override
				public boolean isCancelled() {
					return runningJob.cancelled.get();
				}
			};
			BackupIndex index = new BackupEngine(easyBackupRoot(minecraft)).createBackup(
					runningJob.world.worldPath(),
					runningJob.world.levelId(),
					runningJob.world.levelName(),
					runningJob.type,
					runningJob.name,
					listener);
			runningJob.successIndex = index;
			scheduleBackupFinish(runningJob, true, null);
		} catch (CancelledException e) {
			scheduleBackupFinish(runningJob, false, null);
		} catch (BackupException e) {
			scheduleBackupFinish(runningJob, false, e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			scheduleBackupFinish(runningJob, false, "备份线程被中断");
		} catch (Throwable throwable) {
			EasyBackup.LOGGER.error("Backup failed", throwable);
			scheduleBackupFinish(runningJob, false, String.valueOf(throwable.getMessage()));
		}
	}

	private static void scheduleBackupFinish(BackupJob runningJob, boolean success, String error) {
		if (!runningJob.finishScheduled.compareAndSet(false, true)) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		MinecraftServer server = runningJob.world.server();
		if (server != null && runningJob.suppressInstalled && !runningJob.cancelled.get()) {
			server.executeIfPossible(() -> {
				restoreAutoSave(server, runningJob);
				minecraft.execute(() -> completeBackup(runningJob, success, error));
			});
		} else {
			minecraft.execute(() -> completeBackup(runningJob, success, error));
		}
	}

	private static void restoreAutoSave(MinecraftServer server, BackupJob runningJob) {
		SUPPRESSED.remove(server);
		if (runningJob.previousAutoSave != null && server.isRunning()) {
			server.setAutoSave(runningJob.previousAutoSave);
		}
	}

	private static void completeBackup(BackupJob runningJob, boolean success, String error) {
		if (!runningJob.notified.compareAndSet(false, true)) {
			return;
		}
		if (job == runningJob) {
			job = null;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (success) {
			long bytes = runningJob.successIndex == null ? 0 : runningJob.successIndex.displayBytes();
			notifyChat(Component.translatable("easybackup.chat.backup_done",
					runningJob.name, typeName(runningJob.type), FileOps.formatSize(bytes)));
			if (runningJob.suppressInstalled) {
				notifyChat(Component.translatable("easybackup.chat.autosave_restored"));
			}
		} else if (runningJob.cancelled.get()) {
			notifyChat(Component.translatable("easybackup.chat.backup_aborted"));
		} else {
			notifyChat(Component.translatable("easybackup.chat.backup_failed",
					error == null ? "" : error));
		}
		if (minecraft.gui.screen() instanceof BackupScreen screen) {
			screen.reloadBackups();
		}
	}

	/**
	 * 由 {@code MinecraftServer.stopServer} 的 Mixin 在服务器线程调用。
	 * 中止进行中的备份，并清理自动保存抑制状态（最终保存由原版 stopServer 完成）。
	 */
	public static void onServerStopping(MinecraftServer server) {
		SUPPRESSED.remove(server);
		BackupJob runningJob = job;
		if (runningJob == null || runningJob.world.server() != server) {
			return;
		}
		runningJob.cancelled.set(true);
		runningJob.started.countDown();
		Minecraft minecraft = Minecraft.getInstance();
		if (runningJob.finishScheduled.compareAndSet(false, true)) {
			minecraft.execute(() -> completeBackup(runningJob, runningJob.successIndex != null, null));
		} else {
			// 收尾任务可能因服务器关闭而被丢弃，这里再投递一次客户端清理。
			minecraft.execute(() -> completeBackup(runningJob, runningJob.successIndex != null, null));
		}
	}

	// ---------------------------------------------------------------- 回档

	public static void startRestore(WorldRef world, BackupInfo backup, boolean makeBackup, Screen returnScreen) {
		Minecraft minecraft = Minecraft.getInstance();
		if (job != null || restoreActive) {
			return;
		}
		if (world.locked()) {
			minecraft.gui.setScreen(new MessageScreen(returnScreen,
					Component.translatable("easybackup.title.error"),
					Component.translatable("easybackup.error.world_locked")));
			return;
		}
		RestoreFlow flow = new RestoreFlow(world, backup, makeBackup, returnScreen);
		restoreActive = true;
		RestoreProgressScreen progress = new RestoreProgressScreen();
		if (world.current() && world.server() != null) {
			minecraft.disconnect(progress, true, true);
		} else {
			minecraft.gui.setScreen(progress);
		}
		EXECUTOR.execute(() -> runRestore(flow, progress, false));
	}

	private static void runRestore(RestoreFlow flow, RestoreProgressScreen progress, boolean skipPreBackup) {
		Minecraft minecraft = Minecraft.getInstance();
		try {
			Path root = easyBackupRoot(minecraft);
			String levelId = flow.world.levelId();
			String levelName = flow.world.levelName();
			BackupEngine backupEngine = new BackupEngine(root);

			if (flow.makeBackup && !skipPreBackup) {
				progress.setStageKey("easybackup.stage.pre_restore_backup");
				String name = backupEngine.suggestRestoreBackupName(levelId, levelName, LocalDate.now());
				ProgressListener backupListener = new ProgressListener() {
					@Override
					public void onProgress(ProgressStage stage, long done, long total) {
						progress.setProgress(done, total);
						progress.setStageKey("easybackup.stage.pre_restore_backup");
					}
				};
				BackupIndex created = backupEngine.createBackup(flow.world.worldPath(), levelId, levelName,
						BackupType.INCREMENTAL, name, backupListener);
				flow.preRestoreBackup = backupEngine.findBackup(levelId, created.id).orElse(null);
				flow.makeBackup = false;
			}

			progress.setStageKey("easybackup.stage.checking");
			RestoreEngine restoreEngine = new RestoreEngine(root);
			BackupInfo target = backupEngine.findBackup(levelId, flow.backup.id()).orElse(flow.backup);
			RestorePlan plan = restoreEngine.createPlan(levelId, target);

			if (!plan.canContinue() || !plan.missingFiles().isEmpty()) {
				Boolean proceed = askMissingFiles(flow, progress, plan);
				if (proceed == null || !proceed) {
					restoreActive = false;
					minecraft.execute(() -> minecraft.gui.setScreen(
							flow.world.current() ? new TitleScreen() : flow.returnScreen));
					return;
				}
				minecraft.execute(() -> minecraft.gui.setScreen(progress));
			}

			progress.setStageKey("easybackup.stage.copying");
			RestoreJournal journal = restoreEngine.restore(levelId, levelName,
					flow.world.worldPath(), plan, progressListener(progress));
			flow.journal = journal;
			progress.setStageKey("easybackup.stage.entering");
			openWorldAfterRestore(minecraft, levelId, journal);
		} catch (BackupException e) {
			failRestore(flow, e.getMessage());
		} catch (Throwable throwable) {
			EasyBackup.LOGGER.error("Restore failed", throwable);
			failRestore(flow, String.valueOf(throwable.getMessage()));
		}
	}

	private static Boolean askMissingFiles(RestoreFlow flow, RestoreProgressScreen progress, RestorePlan plan) {
		Minecraft minecraft = Minecraft.getInstance();
		CompletableFuture<Boolean> decision = new CompletableFuture<>();
		minecraft.execute(() -> minecraft.gui.setScreen(
				new MissingFilesScreen(flow.world, plan, progress, decision)));
		try {
			return decision.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (ExecutionException e) {
			return false;
		}
	}

	private static ProgressListener progressListener(RestoreProgressScreen progress) {
		return (stage, done, total) -> {
			progress.setProgress(done, total);
			progress.setStageKey(stageKey(stage));
		};
	}

	public static String stageKey(ProgressStage stage) {
		return switch (stage) {
			case PREPARING -> "easybackup.stage.preparing";
			case SCANNING -> "easybackup.stage.checking";
			case COPYING -> "easybackup.stage.copying";
			case VERIFYING -> "easybackup.stage.verifying";
			case SWAPPING -> "easybackup.stage.swapping";
		};
	}

	private static void openWorldAfterRestore(Minecraft minecraft, String levelId, RestoreJournal journal) {
		pendingJournal = journal;
		minecraft.execute(() -> minecraft.createWorldOpenFlows().openWorld(levelId, () -> {
			pendingJournal = null;
			restoreActive = false;
			minecraft.execute(() -> minecraft.gui.setScreen(new MessageScreen(new TitleScreen(),
					Component.translatable("easybackup.title.error"),
					Component.translatable("easybackup.error.open_world", ""))));
		}));
	}

	private static void failRestore(RestoreFlow flow, String error) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			if (flow.journal == null) {
				restoreActive = false;
			}
			minecraft.gui.setScreen(new RestoreFailedScreen(flow, error));
		});
	}

	public static void retryRestore(RestoreFlow flow) {
		if (flow.journal != null) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		restoreActive = true;
		RestoreProgressScreen progress = new RestoreProgressScreen();
		minecraft.gui.setScreen(progress);
		EXECUTOR.execute(() -> runRestore(flow, progress, true));
	}

	public static void rollbackRestore(RestoreFlow flow) {
		Minecraft minecraft = Minecraft.getInstance();
		RestoreProgressScreen progress = new RestoreProgressScreen();
		minecraft.gui.setScreen(progress);
		restoreActive = true;
		EXECUTOR.execute(() -> {
			try {
				if (flow.journal != null) {
					new RestoreEngine(easyBackupRoot(minecraft)).rollback(flow.journal);
					flow.journal = null;
					openWorldAfterRollback(minecraft, flow.world.levelId());
				} else if (flow.preRestoreBackup != null) {
					RestoreFlow fallback = new RestoreFlow(flow.world.detached(),
							flow.preRestoreBackup, false, flow.returnScreen);
					runRestore(fallback, progress, true);
				} else {
					throw new BackupException("未找到回档前的存档");
				}
			} catch (BackupException e) {
				failRestore(flow, e.getMessage());
			}
		});
	}

	private static void openWorldAfterRollback(Minecraft minecraft, String levelId) {
		pendingNotice = Component.translatable("easybackup.chat.rollback_done");
		restoreActive = false;
		minecraft.execute(() -> minecraft.createWorldOpenFlows().openWorld(levelId, () -> {
			pendingNotice = null;
			minecraft.execute(() -> minecraft.gui.setScreen(new MessageScreen(new TitleScreen(),
					Component.translatable("easybackup.title.error"),
					Component.translatable("easybackup.error.open_world", ""))));
		}));
	}

	public static void cancelRestore(RestoreFlow flow) {
		restoreActive = false;
		Minecraft.getInstance().gui.setScreen(new TitleScreen());
	}

	// ---------------------------------------------------------------- 崩溃恢复

	public static void checkRecovery(Screen parent, boolean force) {
		Minecraft minecraft = Minecraft.getInstance();
		if (job != null || restoreActive) {
			return;
		}
		if (!force && !RECOVERY_CHECKED.compareAndSet(false, true)) {
			return;
		}
		RECOVERY_CHECKED.set(true);
		EXECUTOR.execute(() -> {
			RestoreEngine engine = new RestoreEngine(easyBackupRoot(minecraft));
			List<RestoreJournal> journals = engine.scanJournals();
			engine.cleanupAllStaleFiles();
			if (journals.isEmpty()) {
				return;
			}
			RestoreJournal journal = journals.get(0);
			minecraft.execute(() -> {
				Screen current = minecraft.gui.screen();
				if (current instanceof TitleScreen titleScreen) {
					minecraft.gui.setScreen(new RestoreRecoveryScreen(titleScreen, journal));
				}
			});
		});
	}

	public static void redoRecovery(RestoreJournal journal, Screen parent) {
		Minecraft minecraft = Minecraft.getInstance();
		restoreActive = true;
		RestoreProgressScreen progress = new RestoreProgressScreen();
		minecraft.gui.setScreen(progress);
		EXECUTOR.execute(() -> {
			try {
				Path root = easyBackupRoot(minecraft);
				new RestoreEngine(root).discardJournal(journal);
				BackupEngine backupEngine = new BackupEngine(root);
				Optional<BackupInfo> backup = backupEngine.findBackup(journal.levelId, journal.backupId);
				if (backup.isEmpty()) {
					restoreActive = false;
					minecraft.execute(() -> minecraft.gui.setScreen(new MessageScreen(parent,
							Component.translatable("easybackup.title.error"),
							Component.translatable("easybackup.error.backup_not_found"))));
					return;
				}
				WorldRef world = new WorldRef(journal.levelId, journal.levelName,
						Path.of(journal.worldPath), false, false, null);
				RestoreFlow flow = new RestoreFlow(world, backup.get(), false, parent);
				runRestore(flow, progress, true);
			} catch (Throwable throwable) {
				EasyBackup.LOGGER.error("Recovery redo failed", throwable);
				restoreActive = false;
				minecraft.execute(() -> minecraft.gui.setScreen(new MessageScreen(parent,
						Component.translatable("easybackup.title.error"),
						Component.literal(String.valueOf(throwable.getMessage())))));
			}
		});
	}

	public static void continueRecovery(RestoreJournal journal, Screen parent) {
		Minecraft minecraft = Minecraft.getInstance();
		restoreActive = true;
		RestoreProgressScreen progress = new RestoreProgressScreen();
		progress.setStageKey("easybackup.stage.swapping");
		minecraft.gui.setScreen(progress);
		EXECUTOR.execute(() -> {
			try {
				RestoreEngine engine = new RestoreEngine(easyBackupRoot(minecraft));
				if (!engine.continueSwap(journal)) {
					throw new BackupException("无法继续完成回档，请选择恢复回档前存档");
				}
				openWorldAfterRestore(minecraft, journal.levelId, journal);
			} catch (BackupException e) {
				failRestore(flowFromJournal(minecraft, journal, parent), e.getMessage());
			}
		});
	}

	public static void rollbackRecovery(RestoreJournal journal, Screen parent) {
		Minecraft minecraft = Minecraft.getInstance();
		restoreActive = true;
		RestoreProgressScreen progress = new RestoreProgressScreen();
		progress.setStageKey("easybackup.stage.swapping");
		minecraft.gui.setScreen(progress);
		EXECUTOR.execute(() -> {
			try {
				new RestoreEngine(easyBackupRoot(minecraft)).rollback(journal);
				openWorldAfterRollback(minecraft, journal.levelId);
			} catch (BackupException e) {
				failRestore(flowFromJournal(minecraft, journal, parent), e.getMessage());
			}
		});
	}

	public static void completeRecovery(RestoreJournal journal, Screen parent) {
		Minecraft minecraft = Minecraft.getInstance();
		EXECUTOR.execute(() -> {
			new RestoreEngine(easyBackupRoot(minecraft)).completeRestore(journal);
			minecraft.execute(() -> checkRecovery(parent, true));
		});
	}

	private static RestoreFlow flowFromJournal(Minecraft minecraft, RestoreJournal journal, Screen parent) {
		BackupInfo backup = null;
		try {
			backup = new BackupEngine(easyBackupRoot(minecraft))
					.findBackup(journal.levelId, journal.backupId).orElse(null);
		} catch (RuntimeException ignored) {
		}
		Path worldPath;
		try {
			worldPath = Path.of(journal.worldPath);
		} catch (RuntimeException e) {
			worldPath = easyBackupRoot(minecraft);
		}
		WorldRef world = new WorldRef(journal.levelId, journal.levelName, worldPath, false, false, null);
		RestoreFlow flow = new RestoreFlow(world, backup, false, parent);
		flow.journal = journal;
		return flow;
	}

	// ---------------------------------------------------------------- 客户端 tick

	public static void onClientTick(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null) {
			return;
		}
		Component notice = pendingNotice;
		if (notice != null) {
			pendingNotice = null;
			chat(notice);
		}
		RestoreJournal journal = pendingJournal;
		if (journal != null) {
			pendingJournal = null;
			restoreActive = false;
			chat(Component.translatable("easybackup.chat.restore_done",
					journal.backupName == null ? "" : journal.backupName));
			Path root = easyBackupRoot(minecraft);
			EXECUTOR.execute(() -> new RestoreEngine(root).completeRestore(journal));
		}
	}

	// ---------------------------------------------------------------- 聊天栏

	private static void notifyChat(Component message) {
		lastStatus = message;
		chat(message);
	}

	private static void chat(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null) {
			minecraft.gui.hud.getChat().addClientSystemMessage(message);
		}
	}

	// ---------------------------------------------------------------- 数据结构

	public static final class BackupJob {
		private final WorldRef world;
		private final BackupType type;
		private final String name;
		final AtomicBoolean cancelled = new AtomicBoolean();
		final AtomicBoolean finishScheduled = new AtomicBoolean();
		final AtomicBoolean notified = new AtomicBoolean();
		final CountDownLatch started = new CountDownLatch(1);
		volatile boolean suppressInstalled;
		volatile Boolean previousAutoSave;
		volatile BackupIndex successIndex;
		private volatile ProgressStage stage = ProgressStage.PREPARING;
		private volatile long done;
		private volatile long total;

		BackupJob(WorldRef world, BackupType type, String name) {
			this.world = world;
			this.type = type;
			this.name = name;
		}

		void update(ProgressStage stage, long done, long total) {
			this.stage = stage;
			this.done = done;
			this.total = total;
		}

		public WorldRef world() {
			return world;
		}

		public BackupType type() {
			return type;
		}

		public String name() {
			return name;
		}

		public ProgressStage stage() {
			return stage;
		}

		public long done() {
			return done;
		}

		public long total() {
			return total;
		}

		public int percent() {
			if (total <= 0) {
				return done > 0 ? 100 : 0;
			}
			return (int) Math.min(100, done * 100 / total);
		}
	}

	public static final class RestoreFlow {
		private final WorldRef world;
		private final BackupInfo backup;
		private boolean makeBackup;
		private final Screen returnScreen;
		RestoreJournal journal;
		BackupInfo preRestoreBackup;

		RestoreFlow(WorldRef world, BackupInfo backup, boolean makeBackup, Screen returnScreen) {
			this.world = world;
			this.backup = backup;
			this.makeBackup = makeBackup;
			this.returnScreen = returnScreen;
		}

		public WorldRef world() {
			return world;
		}

		public BackupInfo backup() {
			return backup;
		}

		public RestoreJournal journal() {
			return journal;
		}

		public BackupInfo preRestoreBackup() {
			return preRestoreBackup;
		}

		public boolean canRetry() {
			return journal == null;
		}

		public boolean canRollback() {
			return journal != null || preRestoreBackup != null;
		}
	}
}
