package cn.qinzhikeji.easybackup.backup;

/**
 * 备份类型。序列化到索引中的 {@code type} 字段时使用小写名称。
 */
public enum BackupType {
	FULL("full"),
	INCREMENTAL("incremental");

	private final String id;

	BackupType(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public static BackupType fromId(String id) {
		if (id != null) {
			for (BackupType type : values()) {
				if (type.id.equalsIgnoreCase(id)) {
					return type;
				}
			}
		}
		throw new IllegalArgumentException("Unknown backup type: " + id);
	}
}
