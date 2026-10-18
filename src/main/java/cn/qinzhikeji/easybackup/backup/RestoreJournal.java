package cn.qinzhikeji.easybackup.backup;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 回档日志。用于在游戏意外关闭后判断回档进行到哪一步并恢复。
 */
public final class RestoreJournal {
	public static final int FORMAT_VERSION = 1;

	public int formatVersion = FORMAT_VERSION;
	public String levelId;
	public String levelName;
	/** 世界目录的绝对路径（便于崩溃后不依赖游戏目录推断）。 */
	public String worldPath;
	public String backupId;
	public String backupName;
	public String backupType;
	/** 相对于 easybackup/&lt;存档ID&gt;/ 的目录名。 */
	public String tempWorldDir;
	/** 相对于 easybackup/&lt;存档ID&gt;/ 的目录名；COPYING 阶段可能为 null。 */
	public String oldWorldDir;
	public String phase = Phase.COPYING.name();
	public String createdAt = Instant.now().toString();
	public String error;

	public RestoreJournal() {
	}

	public enum Phase {
		COPYING,
		SWAPPING,
		SWAPPED
	}

	public Phase phase() {
		return Phase.valueOf(phase);
	}

	public void setPhase(Phase phase) {
		this.phase = phase.name();
	}

	public Path levelRoot(BackupPaths paths) {
		return paths.levelRoot(levelId);
	}

	public Path worldPath() {
		return worldPath == null ? null : Path.of(worldPath);
	}

	public Path tempPath(BackupPaths paths) {
		return tempWorldDir == null ? null : levelRoot(paths).resolve(tempWorldDir);
	}

	public Path oldPath(BackupPaths paths) {
		return oldWorldDir == null ? null : levelRoot(paths).resolve(oldWorldDir);
	}

	public boolean isValid() {
		return levelId != null && !levelId.isEmpty()
				&& worldPath != null && !worldPath.isEmpty()
				&& backupId != null && !backupId.isEmpty()
				&& tempWorldDir != null && !tempWorldDir.isEmpty()
				&& phase != null
				&& isOneOfPhases();
	}

	private boolean isOneOfPhases() {
		try {
			Phase.valueOf(phase);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
