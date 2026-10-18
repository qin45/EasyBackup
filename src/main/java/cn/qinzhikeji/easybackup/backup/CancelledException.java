package cn.qinzhikeji.easybackup.backup;

/**
 * 任务被主动取消（例如备份期间世界被关闭）。
 */
public class CancelledException extends BackupException {
	public CancelledException(String message) {
		super(message);
	}
}
