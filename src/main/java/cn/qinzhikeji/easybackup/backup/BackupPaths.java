package cn.qinzhikeji.easybackup.backup;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * easybackup 目录布局与备份命名规则。
 */
public final class BackupPaths {
	public static final String FULL_DIR = "Full backup";
	public static final String INCREMENTAL_DIR = "Incremental backup";
	public static final String INDEX_FILE = "easybackup.index.json";
	public static final String JOURNAL_FILE = ".restore.journal.json";
	public static final int MAX_NAME_LENGTH = 64;
	public static final String RESTORE_SUFFIX = "-回档备份";

	private static final Pattern SEQUENCE_PATTERN =
			Pattern.compile(".*-(\\d{4}-\\d{2}-\\d{2})-(\\d+)(?:-回档备份(?:-\\d+)?)?$");
	private static final Pattern INVALID_CHARS = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1F]");
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

	private final Path root;

	public BackupPaths(Path easyBackupRoot) {
		this.root = easyBackupRoot.toAbsolutePath().normalize();
	}

	public Path root() {
		return root;
	}

	public Path levelRoot(String levelId) {
		return root.resolve(validateLevelId(levelId));
	}

	public Path fullRoot(String levelId) {
		return levelRoot(levelId).resolve(FULL_DIR);
	}

	public Path incrementalRoot(String levelId) {
		return levelRoot(levelId).resolve(INCREMENTAL_DIR);
	}

	public Path journal(String levelId) {
		return levelRoot(levelId).resolve(JOURNAL_FILE);
	}

	public static String validateLevelId(String levelId) {
		if (levelId == null || levelId.isEmpty()
				|| levelId.equals(".") || levelId.equals("..")
				|| levelId.indexOf('/') >= 0 || levelId.indexOf('\\') >= 0
				|| levelId.indexOf(':') >= 0) {
			throw new IllegalArgumentException("非法的存档 ID: " + levelId);
		}
		return levelId;
	}

	/**
	 * 清洗名称：去掉 Windows 非法字符、首尾空格与结尾的点，并限制长度。
	 */
	public static String sanitizeName(String name) {
		if (name == null) {
			return "";
		}
		String cleaned = INVALID_CHARS.matcher(name).replaceAll("");
		cleaned = cleaned.strip();
		while (cleaned.endsWith(".")) {
			cleaned = cleaned.substring(0, cleaned.length() - 1).strip();
		}
		if (cleaned.length() > MAX_NAME_LENGTH) {
			cleaned = cleaned.substring(0, MAX_NAME_LENGTH).strip();
			while (cleaned.endsWith(".")) {
				cleaned = cleaned.substring(0, cleaned.length() - 1).strip();
			}
		}
		return cleaned;
	}

	public static boolean isValidName(String name) {
		if (name == null) {
			return false;
		}
		if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
			return false;
		}
		if (!name.equals(name.strip()) || name.endsWith(".")) {
			return false;
		}
		if (name.equals(".") || name.equals("..")) {
			return false;
		}
		return !INVALID_CHARS.matcher(name).find();
	}

	/**
	 * 默认备份名：{@code 存档名-yyyy-MM-dd-顺序码}。
	 */
	public static String defaultName(String worldName, LocalDate date, int sequence) {
		String base = sanitizeName(worldName);
		if (base.isEmpty()) {
			base = "world";
		}
		return base + "-" + DATE_FORMAT.format(date) + "-" + Math.max(1, sequence);
	}

	public static String dateString(LocalDate date) {
		return DATE_FORMAT.format(date);
	}

	/**
	 * 从已有目录名中解析日期对应的最大顺序码；不匹配时返回 0。
	 */
	public static int sequenceOf(String name, LocalDate date) {
		if (name == null) {
			return 0;
		}
		Matcher matcher = SEQUENCE_PATTERN.matcher(name);
		if (!matcher.matches() || !matcher.group(1).equals(dateString(date))) {
			return 0;
		}
		try {
			return Integer.parseInt(matcher.group(2));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * 校验名称并返回它；非法时抛出带玩家可读消息的异常。
	 */
	public static String requireValidName(String name) throws BackupException {
		if (name == null || name.isEmpty()) {
			throw new BackupException("备份名称不能为空");
		}
		if (name.length() > MAX_NAME_LENGTH) {
			throw new BackupException("备份名称不能超过 " + MAX_NAME_LENGTH + " 个字符");
		}
		if (name.equals(".") || name.equals("..") || !isValidName(name)) {
			throw new BackupException("备份名称包含非法字符或格式不正确");
		}
		return name;
	}
}
