package cn.qinzhikeji.easybackup.backup;

/**
 * 索引中的单个文件记录，描述文件内容及其实际来源备份。
 */
public final class FileRecord {
	public String sourceId;
	public String sourceName;
	public long size;
	public String sha256;

	public FileRecord() {
	}

	public FileRecord(String sourceId, String sourceName, long size, String sha256) {
		this.sourceId = sourceId;
		this.sourceName = sourceName;
		this.size = size;
		this.sha256 = sha256;
	}

	public String sourceId() {
		return sourceId;
	}

	public String sourceName() {
		return sourceName;
	}

	public long size() {
		return size;
	}

	public String sha256() {
		return sha256;
	}

	public boolean isValid() {
		return sourceId != null && !sourceId.isEmpty()
				&& sourceName != null && !sourceName.isEmpty()
				&& size >= 0
				&& sha256 != null && !sha256.isEmpty();
	}
}
