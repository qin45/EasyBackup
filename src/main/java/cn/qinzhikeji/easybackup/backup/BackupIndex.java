package cn.qinzhikeji.easybackup.backup;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个备份的索引。全量与增量备份都有索引：
 * 全量索引用于增量对比与回档校验；增量索引记录每个文件实际来自哪个备份。
 */
public final class BackupIndex {
	public static final int FORMAT_VERSION = 1;

	public int formatVersion = FORMAT_VERSION;
	public String id;
	public String name;
	/** "full" 或 "incremental" */
	public String type;
	public String worldId;
	public String worldName;
	public String createdAt;
	/** 本次备份新增到磁盘的字节数（全量备份为 0）。 */
	public long addedBytes;
	/** 全量备份的完整大小；增量备份为 0。 */
	public long totalBytes;
	public int totalFiles;
	/** 相对路径（统一使用 /）-> 文件记录。 */
	public Map<String, FileRecord> files = new LinkedHashMap<>();

	public BackupIndex() {
	}

	public BackupType typeEnum() {
		return BackupType.fromId(type);
	}

	public Instant createdAtInstant() {
		try {
			return Instant.parse(createdAt);
		} catch (RuntimeException e) {
			return Instant.EPOCH;
		}
	}

	public long displayBytes() {
		return typeEnum() == BackupType.FULL ? totalBytes : addedBytes;
	}

	/**
	 * 校验索引是否足以用于读取。
	 */
	public boolean isValid() {
		if (formatVersion > FORMAT_VERSION) {
			return false;
		}
		if (id == null || id.isEmpty() || name == null || name.isEmpty()) {
			return false;
		}
		try {
			BackupType.fromId(type);
		} catch (RuntimeException e) {
			return false;
		}
		if (files == null) {
			return false;
		}
		for (Map.Entry<String, FileRecord> entry : files.entrySet()) {
			if (entry.getKey() == null || entry.getKey().isEmpty()) {
				return false;
			}
			if (entry.getValue() == null || !entry.getValue().isValid()) {
				return false;
			}
		}
		return true;
	}
}
