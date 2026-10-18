package cn.qinzhikeji.easybackup.backup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 备份引擎：负责全量/增量备份、列表、空间统计、删除与重命名。
 * 不依赖任何 Minecraft 类，可在单元测试中直接使用。
 */
public final class BackupEngine {
	private static final long DISK_MARGIN = 16L * 1024 * 1024;

	private final BackupPaths paths;

	public BackupEngine(Path easyBackupRoot) {
		this.paths = new BackupPaths(easyBackupRoot);
	}

	public BackupPaths paths() {
		return paths;
	}

	// ---------------------------------------------------------------- 命名

	/**
	 * 生成默认备份名：{@code 存档名-yyyy-MM-dd-顺序码}。
	 */
	public String suggestName(String levelId, String worldName, LocalDate date) {
		return uniqueName(levelId, worldName, date, "");
	}

	/**
	 * 生成回档前自动备份的名称：{@code 存档名-yyyy-MM-dd-顺序码-回档备份}。
	 */
	public String suggestRestoreBackupName(String levelId, String worldName, LocalDate date) {
		return uniqueName(levelId, worldName, date, BackupPaths.RESTORE_SUFFIX);
	}

	private String uniqueName(String levelId, String worldName, LocalDate date, String suffix) {
		int max = 0;
		for (String existing : existingNames(levelId)) {
			max = Math.max(max, BackupPaths.sequenceOf(existing, date));
		}
		int sequence = max + 1;
		String datePart = BackupPaths.dateString(date);
		while (true) {
			// 名称总长（含日期、顺序码与 -回档备份 后缀）不能超过限制，超出时截短存档名前缀。
			String tail = "-" + datePart + "-" + sequence + suffix;
			int maxBase = BackupPaths.MAX_NAME_LENGTH - tail.length();
			String base = BackupPaths.sanitizeName(worldName);
			if (base.isEmpty()) {
				base = "world";
			}
			if (base.length() > maxBase) {
				base = base.substring(0, Math.max(1, maxBase)).strip();
			}
			String candidate = BackupPaths.sanitizeName(base + tail);
			if (!candidate.isEmpty() && !backupExists(levelId, candidate)) {
				return candidate;
			}
			sequence++;
		}
	}

	public boolean backupExists(String levelId, String name) {
		return Files.exists(paths.fullRoot(levelId).resolve(name))
				|| Files.exists(paths.incrementalRoot(levelId).resolve(name));
	}

	// ---------------------------------------------------------------- 创建备份

	public BackupIndex createBackup(Path worldDir, String levelId, String worldName,
									BackupType type, String name,
									ProgressListener listener) throws BackupException {
		BackupPaths.requireValidName(name);
		ProgressListener progress = listener == null ? ProgressListener.NONE : listener;
		Path parent = type == BackupType.FULL ? paths.fullRoot(levelId) : paths.incrementalRoot(levelId);
		Path target = parent.resolve(name);
		if (Files.exists(target)) {
			throw new BackupException("备份名称已存在: " + name);
		}

		Path tempDir = null;
		try {
			Files.createDirectories(parent);
			List<FileOps.WorldFile> files = FileOps.listWorldFiles(worldDir);
			long worldBytes = 0;
			for (FileOps.WorldFile file : files) {
				worldBytes += file.size();
			}
			long usable = FileOps.usableSpace(parent);
			if (usable < worldBytes + DISK_MARGIN) {
				throw new BackupException("磁盘空间不足，至少需要 "
						+ FileOps.formatSize(worldBytes + DISK_MARGIN)
						+ "，当前可用 " + FileOps.formatSize(usable));
			}

			tempDir = parent.resolve("." + name + ".tmp-" + UUID.randomUUID());
			Files.createDirectories(tempDir);
			progress.onProgress(ProgressStage.PREPARING, 0, worldBytes);

			BackupIndex index = type == BackupType.FULL
					? createFull(worldDir, files, worldBytes, levelId, worldName, name, tempDir, progress)
					: createIncremental(worldDir, files, worldBytes, levelId, worldName, name, tempDir, progress);

			Json.write(tempDir.resolve(BackupPaths.INDEX_FILE), index);
			FileOps.move(tempDir, target);
			tempDir = null;
			return index;
		} catch (BackupException e) {
			throw e;
		} catch (IOException e) {
			throw new BackupException("创建备份失败: " + describe(e), e);
		} finally {
			if (tempDir != null) {
				FileOps.deleteQuietlyRecursive(tempDir);
			}
		}
	}

	private BackupIndex createFull(Path worldDir, List<FileOps.WorldFile> files, long worldBytes,
								   String levelId, String worldName, String name, Path tempDir,
								   ProgressListener listener) throws IOException, CancelledException {
		String id = UUID.randomUUID().toString();
		BackupIndex index = newIndex(id, name, BackupType.FULL, levelId, worldName);
		long[] done = {0L};
		listener.onProgress(ProgressStage.COPYING, 0, worldBytes);
		long copiedBytes = 0;

		for (FileOps.WorldFile file : files) {
			Path source = FileOps.resolveRelative(worldDir, file.path());
			Path destination = FileOps.resolveRelative(tempDir, file.path());
			FileOps.CopyResult result = FileOps.copyAndHash(source, destination,
					amount -> {
						done[0] += amount;
						listener.onProgress(ProgressStage.COPYING, done[0], worldBytes);
					},
					listener::isCancelled);
			index.files.put(file.path(), new FileRecord(id, name, result.size(), result.sha256()));
			copiedBytes += result.size();
		}

		index.totalBytes = copiedBytes;
		index.addedBytes = 0;
		index.totalFiles = index.files.size();
		listener.onProgress(ProgressStage.COPYING, worldBytes, worldBytes);
		return index;
	}

	private BackupIndex createIncremental(Path worldDir, List<FileOps.WorldFile> files, long worldBytes,
										  String levelId, String worldName, String name, Path tempDir,
										  ProgressListener listener) throws IOException, CancelledException {
		String id = UUID.randomUUID().toString();
		BackupIndex index = newIndex(id, name, BackupType.INCREMENTAL, levelId, worldName);
		// 增量备份只复用其他增量备份中的文件，绝不引用全量备份，
		// 这样删除全量备份后增量备份仍然可以完整回档。
		BackupRepository repository = new BackupRepository(paths, levelId, false);

		// 最坏情况下所有文件都需要复制：哈希一遍 + 复制一遍。
		long estimate = worldBytes > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : worldBytes * 2;
		long[] done = {0L};
		long addedBytes = 0;
		listener.onProgress(ProgressStage.COPYING, 0, estimate);

		for (FileOps.WorldFile file : files) {
			Path source = FileOps.resolveRelative(worldDir, file.path());
			FileOps.CopyResult hashResult = FileOps.hashStable(source,
					amount -> {
						done[0] += amount;
						listener.onProgress(ProgressStage.COPYING, done[0], estimate);
					},
					listener::isCancelled);

			BackupRepository.SourceRef reusable =
					repository.findReusable(file.path(), hashResult.sha256(), hashResult.size());
			if (reusable != null) {
				index.files.put(file.path(), new FileRecord(reusable.sourceId(), reusable.sourceName(),
						hashResult.size(), hashResult.sha256()));
				continue;
			}

			Path destination = FileOps.resolveRelative(tempDir, file.path());
			FileOps.CopyResult copied = FileOps.copyAndHash(source, destination,
					amount -> {
						done[0] += amount;
						listener.onProgress(ProgressStage.COPYING, done[0], estimate);
					},
					listener::isCancelled);
			index.files.put(file.path(), new FileRecord(id, name, copied.size(), copied.sha256()));
			addedBytes += copied.size();
		}

		index.addedBytes = addedBytes;
		index.totalBytes = 0;
		index.totalFiles = index.files.size();
		listener.onProgress(ProgressStage.COPYING, estimate, estimate);
		return index;
	}

	private static BackupIndex newIndex(String id, String name, BackupType type,
										String levelId, String worldName) {
		BackupIndex index = new BackupIndex();
		index.id = id;
		index.name = name;
		index.type = type.id();
		index.worldId = levelId;
		index.worldName = worldName;
		index.createdAt = Instant.now().toString();
		return index;
	}

	// ---------------------------------------------------------------- 查询

	public List<BackupInfo> listBackups(String levelId) {
		List<BackupInfo> result = new ArrayList<>();
		try {
			for (Path dir : allBackupDirs(levelId)) {
				BackupIndex index = BackupRepository.readIndex(dir);
				if (index != null) {
					result.add(new BackupInfo(dir, index));
				}
			}
		} catch (IOException ignored) {
			// 目录不可读时返回已经收集到的结果
		}
		result.sort(Comparator
				.comparing(BackupInfo::createdAt)
				.reversed()
				.thenComparing(BackupInfo::name));
		return result;
	}

	public Optional<BackupInfo> findBackup(String levelId, String backupId) {
		return listBackups(levelId).stream()
				.filter(info -> info.id().equals(backupId))
				.findFirst();
	}

	public boolean hasBackups(String levelId) {
		return !listBackups(levelId).isEmpty();
	}

	/** 增量备份目录的真实磁盘占用（包含索引与已删除索引留下的文件）。 */
	public long incrementalUsage(String levelId) {
		return FileOps.directorySize(paths.incrementalRoot(levelId));
	}

	public long fullUsage(String levelId) {
		return FileOps.directorySize(paths.fullRoot(levelId));
	}

	// ---------------------------------------------------------------- 删除 / 重命名

	/**
	 * 删除备份。全量备份删除整个文件夹；增量备份只删除索引文件，
	 * 保留文件以维持其他备份的引用。
	 *
	 * <p>删除全量备份前，会先把增量备份中引用该全量备份文件的记录复制到各增量备份自己的目录，
	 * 保证删除后所有增量备份仍可完整回档（同时兼容旧版本创建的、引用了全量备份的增量备份）。</p>
	 */
	public void deleteBackup(BackupInfo backup) throws BackupException {
		try {
			if (backup.type() == BackupType.FULL) {
				detachIncrementalsFrom(backup);
				FileOps.deleteRecursive(backup.directory());
			} else {
				Files.deleteIfExists(backup.directory().resolve(BackupPaths.INDEX_FILE));
			}
		} catch (BackupException e) {
			throw e;
		} catch (IOException e) {
			throw new BackupException("删除备份失败: " + describe(e), e);
		}
	}

	/**
	 * 把其他增量备份中引用 {@code full} 的文件复制到各自目录，并把索引来源改为自身。
	 * 全部复制成功后才写回索引；任何一步失败都会抛出异常，调用方不应删除全量备份。
	 */
	private void detachIncrementalsFrom(BackupInfo full) throws IOException, BackupException {
		BackupRepository repository = new BackupRepository(paths, levelIdOf(full), true);
		for (BackupInfo info : repository.backups()) {
			if (info.type() != BackupType.INCREMENTAL || info.id().equals(full.id())) {
				continue;
			}
			List<Map.Entry<String, FileRecord>> detached = new ArrayList<>();
			long bytesToCopy = 0;
			for (Map.Entry<String, FileRecord> entry : info.index().files.entrySet()) {
				FileRecord record = entry.getValue();
				Path sourceDir = repository.resolve(record.sourceId, record.sourceName);
				if (sourceDir == null || !sourceDir.toAbsolutePath().normalize()
						.equals(full.directory().toAbsolutePath().normalize())) {
					continue;
				}
				Path sourceFile = FileOps.resolveRelative(full.directory(), entry.getKey());
				if (!Files.isRegularFile(sourceFile)) {
					throw new IOException("全量备份中的文件缺失，无法安全删除: " + entry.getKey());
				}
				bytesToCopy += Files.size(sourceFile);
				detached.add(entry);
			}
			if (detached.isEmpty()) {
				continue;
			}
			long usable = FileOps.usableSpace(info.directory());
			if (usable < bytesToCopy + DISK_MARGIN) {
				throw new IOException("磁盘空间不足，无法把全量备份中的文件复制到增量备份 "
						+ info.name() + "，至少需要 " + FileOps.formatSize(bytesToCopy + DISK_MARGIN));
			}
			for (Map.Entry<String, FileRecord> entry : detached) {
				FileRecord record = entry.getValue();
				Path sourceFile = FileOps.resolveRelative(full.directory(), entry.getKey());
				Path targetFile = FileOps.resolveRelative(info.directory(), entry.getKey());
				FileOps.CopyResult copied = FileOps.copyAndHash(sourceFile, targetFile, null, null);
				if (copied.size() != record.size
						|| (record.sha256 != null && !record.sha256.equalsIgnoreCase(copied.sha256()))) {
					throw new IOException("文件校验失败，无法安全删除全量备份: " + entry.getKey());
				}
				record.sourceId = info.id();
				record.sourceName = info.name();
			}
			Json.write(info.directory().resolve(BackupPaths.INDEX_FILE), info.index());
		}
	}

	private String levelIdOf(BackupInfo backup) {
		return backup.directory().getParent().getParent().getFileName().toString();
	}

	/**
	 * 重命名备份文件夹与索引，并把其他索引中对本备份 {@code sourceName} 的引用一并更新。
	 */
	public BackupInfo renameBackup(String levelId, BackupInfo backup, String newName)
			throws BackupException {
		BackupPaths.requireValidName(newName);
		if (newName.equals(backup.name())) {
			return backup;
		}
		Path target = backup.directory().getParent().resolve(newName);
		if (Files.exists(target)) {
			throw new BackupException("备份名称已存在: " + newName);
		}
		try {
			Files.move(backup.directory(), target, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomicFailure) {
			try {
				Files.move(backup.directory(), target);
			} catch (IOException e) {
				throw new BackupException("重命名备份失败: " + describe(e), e);
			}
		}

		BackupIndex ownIndex = new BackupIndex();
		// 复用内存中的索引对象，但重新从磁盘读取以确保与磁盘一致
		try {
			BackupIndex diskIndex = BackupRepository.readIndex(target);
			if (diskIndex == null) {
				throw new BackupException("备份索引无法读取，重命名未完成");
			}
			ownIndex = diskIndex;
			ownIndex.name = newName;
			Json.write(target.resolve(BackupPaths.INDEX_FILE), ownIndex);
		} catch (IOException e) {
			throw new BackupException("重命名备份失败: " + describe(e), e);
		}

		// 更新其他索引中指向本备份的记录
		String renamedId = ownIndex.id;
		try {
			for (Path dir : allBackupDirs(levelId)) {
				BackupIndex other = BackupRepository.readIndex(dir);
				if (other == null) {
					continue;
				}
				boolean changed = false;
				for (FileRecord record : other.files.values()) {
					if (Objects.equals(record.sourceId, renamedId) && !newName.equals(record.sourceName)) {
						record.sourceName = newName;
						changed = true;
					}
				}
				if (changed) {
					Json.write(dir.resolve(BackupPaths.INDEX_FILE), other);
				}
			}
		} catch (IOException e) {
			throw new BackupException("备份已重命名，但部分引用更新失败: " + describe(e), e);
		}

		return new BackupInfo(target, ownIndex);
	}

	// ---------------------------------------------------------------- 内部工具

	private List<String> existingNames(String levelId) {
		List<String> names = new ArrayList<>();
		collectNames(paths.fullRoot(levelId), names);
		collectNames(paths.incrementalRoot(levelId), names);
		return names;
	}

	private static void collectNames(Path parent, List<String> names) {
		if (!Files.isDirectory(parent)) {
			return;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
			for (Path child : stream) {
				String name = child.getFileName().toString();
				if (Files.isDirectory(child) && !name.startsWith(".")) {
					names.add(name);
				}
			}
		} catch (IOException ignored) {
		}
	}

	private List<Path> allBackupDirs(String levelId) throws IOException {
		List<Path> dirs = new ArrayList<>();
		for (Path parent : List.of(paths.fullRoot(levelId), paths.incrementalRoot(levelId))) {
			if (!Files.isDirectory(parent)) {
				continue;
			}
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
				for (Path child : stream) {
					String name = child.getFileName().toString();
					if (Files.isDirectory(child) && !name.startsWith(".")) {
						dirs.add(child);
					}
				}
			}
		}
		dirs.sort(Path::compareTo);
		return dirs;
	}

	private static String describe(Exception e) {
		String message = e.getMessage();
		return message == null || message.isEmpty()
				? e.getClass().getSimpleName().toLowerCase(Locale.ROOT)
				: message;
	}
}
