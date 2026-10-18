package cn.qinzhikeji.easybackup.backup;

/**
 * 备份/回档进度回调。可能在后台线程被调用。
 */
public interface ProgressListener {
	ProgressListener NONE = (stage, done, total) -> {
	};

	void onProgress(ProgressStage stage, long done, long total);

	default boolean isCancelled() {
		return false;
	}
}
