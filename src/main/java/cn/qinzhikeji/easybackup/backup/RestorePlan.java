package cn.qinzhikeji.easybackup.backup;

import java.nio.file.Path;
import java.util.List;

/**
 * 回档预检结果。
 *
 * @param criticalError 关键文件缺失/损坏时的原因；为 {@code null} 表示可以继续
 */
public record RestorePlan(BackupInfo backup,
						  List<RestoreEntry> entries,
						  List<String> missingFiles,
						  String criticalError,
						  long totalBytes) {

	public boolean canContinue() {
		return criticalError == null;
	}

	public record RestoreEntry(String path, Path source, long size, String sha256) {
	}
}
