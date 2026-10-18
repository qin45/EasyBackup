package cn.qinzhikeji.easybackup.backup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 回档引擎：预检、复制到临时目录、交换世界目录、回滚与崩溃恢复。
 * 不依赖任何 Minecraft 类。
 */
public final class RestoreEngine {
	public static final String LEVEL_DAT = "level.dat";

	private final BackupPaths paths;

	public RestoreEngine(Path easyBackupRoot) {
		this.paths = new BackupPaths(easyBackupRoot);
	}

	public BackupPaths paths() {
		return paths;
	}

	// ---------------------------------------------------------------- 预检

	public RestorePlan createPlan(String levelId, BackupInfo backup) throws BackupException {
		BackupRepository repository;
		try {
			repository = new BackupRepository(paths, levelId);
		} catch (IOException e) {
			throw new BackupException("读取备份列表失败: " + e.getMessage(), e);
		}

		List<RestorePlan.RestoreEntry> entries = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		long totalBytes = 0;

		List<java.util.Map.Entry<String, FileRecord>> records =
				new ArrayList<>(backup.index().files.entrySet());
		records.sort(java.util.Map.Entry.comparingByKey());

		for (var entry : records) {
			String relative = entry.getKey();
			FileRecord record = entry.getValue();
			Path sourceDir = repository.resolve(record.sourceId, record.sourceName);
			if (sourceDir == null) {
				missing.add(relative);
				continue;
			}
			Path source;
			try {
				source = FileOps.resolveRelative(sourceDir, relative);
			} catch (IOException e) {
				throw new BackupException("备份索引中存在非法路径: " + relative, e);
			}
			if (Files.isSymbolicLink(source)
					|| !Files.isRegularFile(source)
					|| safeSize(source) != record.size) {
				missing.add(relative);
				continue;
			}
			entries.add(new RestorePlan.RestoreEntry(relative, source, record.size, record.sha256));
			totalBytes += record.size;
		}

		missing.sort(Comparator.naturalOrder());
		if (entries.isEmpty()) {
			throw new BackupException("备份索引为空，无法回档");
		}

		String criticalError = null;
		Optional<RestorePlan.RestoreEntry> levelDat = entries.stream()
				.filter(entry -> LEVEL_DAT.equals(entry.path()))
				.findFirst();
		if (levelDat.isEmpty()) {
			criticalError = "缺少关键文件 level.dat，无法回档。";
		} else if (!FileOps.isValidLevelDat(levelDat.get().source())) {
			criticalError = "关键文件 level.dat 已损坏，无法回档。";
		}

		return new RestorePlan(backup, List.copyOf(entries), List.copyOf(missing),
				criticalError, totalBytes);
	}

	private static long safeSize(Path path) {
		try {
			return Files.size(path);
		} catch (IOException e) {
			return -1L;
		}
	}

	// ---------------------------------------------------------------- 执行回档

	/**
	 * 执行回档。返回的日志处于 {@code SWAPPED} 阶段，
	 * 需要在新世界成功加载后调用 {@link #completeRestore(RestoreJournal)} 清理旧存档。
	 */
	public RestoreJournal restore(String levelId, String levelName, Path worldDir,
								  RestorePlan plan, ProgressListener listener) throws BackupException {
		ProgressListener progress = listener == null ? ProgressListener.NONE : listener;
		Path levelRoot = paths.levelRoot(levelId);
		Path worldPath = worldDir.toAbsolutePath().normalize();
		String uuid = UUID.randomUUID().toString();
		String tempName = ".restore_tmp_" + uuid;
		String oldName = ".restore_old_" + uuid;
		Path tempDir = levelRoot.resolve(tempName);
		Path oldDir = levelRoot.resolve(oldName);

		RestoreJournal journal = new RestoreJournal();
		journal.levelId = levelId;
		journal.levelName = levelName;
		journal.worldPath = worldPath.toString();
		journal.backupId = plan.backup().id();
		journal.backupName = plan.backup().name();
		journal.backupType = plan.backup().type().id();
		journal.tempWorldDir = tempName;
		journal.oldWorldDir = null;
		journal.setPhase(RestoreJournal.Phase.COPYING);
		journal.createdAt = java.time.Instant.now().toString();

		boolean oldMoved = false;
		boolean worldPlaced = false;
		try {
			Files.createDirectories(levelRoot);
			writeJournal(journal);

			FileOps.deleteQuietlyRecursive(tempDir);
			Files.createDirectories(tempDir);
			progress.onProgress(ProgressStage.COPYING, 0, plan.totalBytes());

			long[] done = {0L};
			for (RestorePlan.RestoreEntry entry : plan.entries()) {
				Path target = FileOps.resolveRelative(tempDir, entry.path());
				FileOps.CopyResult result = FileOps.copyAndHash(entry.source(), target,
						amount -> {
							done[0] += amount;
							progress.onProgress(ProgressStage.COPYING, done[0], plan.totalBytes());
						},
						progress::isCancelled);
				if (result.size() != entry.size()
						|| !result.sha256().equalsIgnoreCase(entry.sha256())) {
					throw new BackupException("备份文件校验失败: " + entry.path()
							+ "（文件可能已损坏或被修改）");
				}
			}
			progress.onProgress(ProgressStage.VERIFYING, plan.totalBytes(), plan.totalBytes());

			Path tempLevelDat = tempDir.resolve(LEVEL_DAT);
			if (!FileOps.isValidLevelDat(tempLevelDat)) {
				throw new BackupException("回档后的 level.dat 无效，已取消回档");
			}

			journal.oldWorldDir = oldName;
			journal.setPhase(RestoreJournal.Phase.SWAPPING);
			writeJournal(journal);

			if (Files.exists(worldPath)) {
				FileOps.move(worldPath, oldDir);
				oldMoved = true;
			}
			FileOps.move(tempDir, worldPath);
			worldPlaced = true;

			journal.setPhase(RestoreJournal.Phase.SWAPPED);
			writeJournal(journal);
			return journal;
		} catch (CancelledException e) {
			cleanupFailedRestore(journal, oldMoved, worldPlaced, tempDir, oldDir, worldPath);
			throw e;
		} catch (BackupException e) {
			cleanupFailedRestore(journal, oldMoved, worldPlaced, tempDir, oldDir, worldPath);
			throw e;
		} catch (IOException e) {
			cleanupFailedRestore(journal, oldMoved, worldPlaced, tempDir, oldDir, worldPath);
			throw new BackupException("回档失败: " + e.getMessage(), e);
		}
	}

	private void cleanupFailedRestore(RestoreJournal journal, boolean oldMoved, boolean worldPlaced,
									  Path tempDir, Path oldDir, Path worldPath) {
		if (worldPlaced) {
			// 世界已经被替换；保留日志与旧存档，交由失败界面/下次启动处理。
			try {
				journal.error = "回档完成后写入日志失败";
				writeJournal(journal);
			} catch (IOException ignored) {
			}
			return;
		}
		FileOps.deleteQuietlyRecursive(tempDir);
		if (oldMoved) {
			// 尝试把旧世界移回原位
			if (!Files.exists(worldPath) && Files.isDirectory(oldDir)) {
				try {
					FileOps.move(oldDir, worldPath);
					deleteJournal(journal.levelId);
					return;
				} catch (IOException ignored) {
					// 失败时保留日志，下次启动可恢复
				}
			}
			return;
		}
		deleteJournal(journal.levelId);
	}

	private void writeJournal(RestoreJournal journal) throws IOException {
		Json.write(paths.journal(journal.levelId), journal);
	}

	private void deleteJournal(String levelId) {
		FileOps.deleteQuietly(paths.journal(levelId));
	}

	// ---------------------------------------------------------------- 完成 / 回滚

	/**
	 * 新世界成功加载后调用：删除回档前的旧存档与日志。
	 */
	public void completeRestore(RestoreJournal journal) {
		Path old = journal.oldPath(paths);
		if (old != null) {
			FileOps.deleteQuietlyRecursive(old);
		}
		Path temp = journal.tempPath(paths);
		if (temp != null) {
			FileOps.deleteQuietlyRecursive(temp);
		}
		deleteJournal(journal.levelId);
	}

	/**
	 * 回滚到回档前的存档（使用隐藏的旧存档目录）。
	 */
	public void rollback(RestoreJournal journal) throws BackupException {
		Path world = journal.worldPath();
		Path old = journal.oldPath(paths);
		if (world == null || journal.levelId == null
				|| !journal.levelId.equals(String.valueOf(world.getFileName()))) {
			throw new BackupException("回档日志中的世界路径无效，无法恢复");
		}
		if (old == null || !Files.isDirectory(old)) {
			throw new BackupException("未找到回档前的存档，无法恢复");
		}

		Path levelRoot = paths.levelRoot(journal.levelId);
		Path discard = levelRoot.resolve(".restore_discard_" + UUID.randomUUID());
		try {
			if (Files.exists(world)) {
				FileOps.move(world, discard);
			}
			FileOps.move(old, world);
		} catch (IOException e) {
			// 尽量把当前世界放回去
			if (!Files.exists(world) && Files.exists(discard)) {
				try {
					FileOps.move(discard, world);
				} catch (IOException ignored) {
				}
			}
			throw new BackupException("恢复回档前存档失败: " + e.getMessage(), e);
		}

		FileOps.deleteQuietlyRecursive(discard);
		Path temp = journal.tempPath(paths);
		if (temp != null) {
			FileOps.deleteQuietlyRecursive(temp);
		}
		deleteJournal(journal.levelId);
	}

	/**
	 * 崩溃后继续完成尚未完成的交换。返回是否成功推进到 {@code SWAPPED} 阶段。
	 */
	public boolean continueSwap(RestoreJournal journal) throws BackupException {
		Path world = journal.worldPath();
		Path temp = journal.tempPath(paths);
		Path old = journal.oldPath(paths);
		if (world == null || temp == null) {
			throw new BackupException("回档日志不完整，无法继续回档");
		}
		try {
			if (Files.exists(world) && !Files.exists(temp)) {
				journal.setPhase(RestoreJournal.Phase.SWAPPED);
				writeJournal(journal);
				return true;
			}
			if (!Files.exists(world) && Files.isDirectory(temp)) {
				FileOps.move(temp, world);
				journal.setPhase(RestoreJournal.Phase.SWAPPED);
				writeJournal(journal);
				return true;
			}
			if (Files.exists(world) && Files.isDirectory(temp) && old != null) {
				// 旧世界可能只复制了一半：丢弃它，把当前世界完整移走后再放入临时世界。
				FileOps.deleteQuietlyRecursive(old);
				FileOps.move(world, old);
				FileOps.move(temp, world);
				journal.setPhase(RestoreJournal.Phase.SWAPPED);
				writeJournal(journal);
				return true;
			}
			return false;
		} catch (IOException e) {
			throw new BackupException("继续回档失败: " + e.getMessage(), e);
		}
	}

	/**
	 * 丢弃 COPYING 阶段的临时文件与日志（世界未被修改）。
	 */
	public void discardJournal(RestoreJournal journal) {
		Path temp = journal.tempPath(paths);
		if (temp != null) {
			FileOps.deleteQuietlyRecursive(temp);
		}
		if (journal.oldWorldDir != null) {
			Path old = journal.oldPath(paths);
			if (old != null) {
				FileOps.deleteQuietlyRecursive(old);
			}
		}
		deleteJournal(journal.levelId);
	}

	// ---------------------------------------------------------------- 日志扫描

	public Optional<RestoreJournal> readJournal(String levelId) {
		Path file = paths.journal(levelId);
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			RestoreJournal journal = Json.read(file, RestoreJournal.class);
			if (journal == null || !journal.isValid() || !levelId.equals(journal.levelId)) {
				return Optional.empty();
			}
			return Optional.of(journal);
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}

	/**
	 * 扫描所有存档的回档日志，用于启动时恢复提示。
	 */
	public List<RestoreJournal> scanJournals() {
		List<RestoreJournal> journals = new ArrayList<>();
		if (!Files.isDirectory(paths.root())) {
			return journals;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(paths.root())) {
			for (Path child : stream) {
				if (!Files.isDirectory(child)) {
					continue;
				}
				readJournal(child.getFileName().toString()).ifPresent(journals::add);
			}
		} catch (IOException ignored) {
		}
		journals.sort(Comparator.comparing(journal -> journal.createdAt == null ? "" : journal.createdAt));
		return journals;
	}

	/**
	 * 清理没有被日志引用的临时目录与备份临时目录。
	 */
	public void cleanupAllStaleFiles() {
		if (!Files.isDirectory(paths.root())) {
			return;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(paths.root())) {
			for (Path child : stream) {
				if (Files.isDirectory(child)) {
					cleanupStaleFiles(child.getFileName().toString());
				}
			}
		} catch (IOException ignored) {
		}
	}

	public void cleanupStaleFiles(String levelId) {
		Path levelRoot = paths.levelRoot(levelId);
		if (!Files.isDirectory(levelRoot)) {
			return;
		}
		String referencedTemp = readJournal(levelId).map(journal -> journal.tempWorldDir).orElse(null);
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(levelRoot)) {
			for (Path child : stream) {
				String name = child.getFileName().toString();
				if ((name.startsWith(".restore_tmp_") || name.startsWith(".restore_discard_"))
						&& !name.equals(referencedTemp)) {
					FileOps.deleteQuietlyRecursive(child);
				}
			}
		} catch (IOException ignored) {
		}
		cleanupBackupTemps(paths.fullRoot(levelId));
		cleanupBackupTemps(paths.incrementalRoot(levelId));
	}

	private static void cleanupBackupTemps(Path parent) {
		if (!Files.isDirectory(parent)) {
			return;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
			for (Path child : stream) {
				String name = child.getFileName().toString();
				if (name.startsWith(".") && name.contains(".tmp-")) {
					FileOps.deleteQuietlyRecursive(child);
				}
			}
		} catch (IOException ignored) {
		}
	}
}
