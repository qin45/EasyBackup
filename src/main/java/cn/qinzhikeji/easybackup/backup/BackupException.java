package cn.qinzhikeji.easybackup.backup;

/**
 * 备份或回档失败。消息面向玩家展示。
 */
public class BackupException extends Exception {
	public BackupException(String message) {
		super(message);
	}

	public BackupException(String message, Throwable cause) {
		super(message, cause);
	}
}
