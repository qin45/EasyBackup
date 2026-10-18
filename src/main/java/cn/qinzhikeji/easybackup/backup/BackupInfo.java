package cn.qinzhikeji.easybackup.backup;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 备份列表展示所需的信息。
 */
public record BackupInfo(Path directory, BackupIndex index) {
	public String name() {
		return index.name;
	}

	public BackupType type() {
		return index.typeEnum();
	}

	public Instant createdAt() {
		return index.createdAtInstant();
	}

	/** 列表中显示的大小：全量为完整大小，增量为本次新增大小。 */
	public long displayBytes() {
		return index.displayBytes();
	}

	public String id() {
		return index.id;
	}
}
