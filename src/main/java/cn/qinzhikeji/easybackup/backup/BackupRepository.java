package cn.qinzhikeji.easybackup.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 扫描某个存档的全部备份，提供按 ID/文件夹名解析来源的能力。
 *
 * <p>文件夹名索引包含没有索引文件的孤儿文件夹，这样删除增量索引后，
 * 引用它的其他备份仍能通过 {@code sourceName} 找回文件。</p>
 */
final class BackupRepository {
	record SourceRef(String sourceId, String sourceName, Path directory) {
		boolean isSameSource(SourceRef other) {
			return sourceId.equals(other.sourceId) && directory.equals(other.directory);
		}
	}

	private final BackupPaths paths;
	private final String levelId;
	private final boolean includeFull;
	private final List<BackupInfo> backups = new ArrayList<>();
	private final Map<String, Path> byId = new HashMap<>();
	private final Map<String, Path> byName = new HashMap<>();
	private final Map<String, Map<String, List<SourceRef>>> candidates = new HashMap<>();

	BackupRepository(BackupPaths paths, String levelId) throws IOException {
		this(paths, levelId, true);
	}

	/**
	 * @param includeFull 为 {@code false} 时只扫描增量备份目录。创建增量备份时使用，
	 *                    保证增量备份绝不引用全量备份中的文件，删除全量备份不会影响增量备份回档。
	 */
	BackupRepository(BackupPaths paths, String levelId, boolean includeFull) throws IOException {
		this.paths = paths;
		this.levelId = levelId;
		this.includeFull = includeFull;
		scan();
	}

	List<BackupInfo> backups() {
		return backups;
	}

	Optional<BackupInfo> findById(String id) {
		return backups.stream().filter(info -> info.id().equals(id)).findFirst();
	}

	Path resolve(String sourceId, String sourceName) {
		if (sourceId != null) {
			Path byIdPath = byId.get(sourceId);
			if (byIdPath != null) {
				return byIdPath;
			}
		}
		if (sourceName != null) {
			return byName.get(sourceName);
		}
		return null;
	}

	/**
	 * 查找同路径、同哈希的可复用来源。只返回来源文件确实存在的记录。
	 */
	SourceRef findReusable(String relativePath, String sha256, long expectedSize) {
		Map<String, List<SourceRef>> byHash = candidates.get(relativePath);
		if (byHash == null) {
			return null;
		}
		List<SourceRef> refs = byHash.get(sha256);
		if (refs == null) {
			return null;
		}
		for (SourceRef ref : refs) {
			try {
				Path file = FileOps.resolveRelative(ref.directory(), relativePath);
				if (Files.isRegularFile(file) && Files.size(file) == expectedSize) {
					return ref;
				}
			} catch (IOException ignored) {
				// 非法路径或读取失败，尝试下一个来源
			}
		}
		return null;
	}

	private void scan() throws IOException {
		List<Path> dirs = new ArrayList<>();
		List<Path> parents = includeFull
				? List.of(paths.fullRoot(levelId), paths.incrementalRoot(levelId))
				: List.of(paths.incrementalRoot(levelId));
		for (Path parent : parents) {
			if (!Files.isDirectory(parent)) {
				continue;
			}
			try (var stream = Files.newDirectoryStream(parent)) {
				for (Path child : stream) {
					String name = child.getFileName().toString();
					if (Files.isDirectory(child) && !name.startsWith(".")) {
						dirs.add(child);
						byName.putIfAbsent(name, child);
					}
				}
			}
		}
		dirs.sort(Path::compareTo);

		for (Path dir : dirs) {
			BackupIndex index = readIndex(dir);
			if (index == null) {
				continue;
			}
			backups.add(new BackupInfo(dir, index));
			byId.putIfAbsent(index.id, dir);
		}

		for (BackupInfo info : backups) {
			for (Map.Entry<String, FileRecord> entry : info.index().files.entrySet()) {
				FileRecord record = entry.getValue();
				Path sourceDir = resolve(record.sourceId, record.sourceName);
				if (sourceDir == null) {
					continue;
				}
				SourceRef ref = new SourceRef(record.sourceId, record.sourceName, sourceDir);
				List<SourceRef> refs = candidates
						.computeIfAbsent(entry.getKey(), key -> new HashMap<>())
						.computeIfAbsent(record.sha256, key -> new ArrayList<>());
				boolean duplicate = false;
				for (SourceRef existing : refs) {
					if (existing.isSameSource(ref)) {
						duplicate = true;
						break;
					}
				}
				if (!duplicate) {
					refs.add(ref);
				}
			}
		}
	}

	static BackupIndex readIndex(Path backupDir) {
		Path indexFile = backupDir.resolve(BackupPaths.INDEX_FILE);
		if (!Files.isRegularFile(indexFile)) {
			return null;
		}
		try {
			BackupIndex index = Json.read(indexFile, BackupIndex.class);
			if (index == null || !index.isValid()) {
				return null;
			}
			return index;
		} catch (IOException e) {
			return null;
		}
	}
}
