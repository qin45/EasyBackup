package cn.qinzhikeji.easybackup.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * 文件复制、移动、删除等底层操作。
 */
public final class FileOps {
	/** 会话锁文件由游戏在加载世界时重新创建，且可能处于锁定状态，备份时跳过。 */
	public static final String SESSION_LOCK = "session.lock";

	private static final int BUFFER_SIZE = 64 * 1024;
	private static final int MAX_STABLE_ATTEMPTS = 3;

	private FileOps() {
	}

	public record CopyResult(long size, String sha256) {
	}

	public record WorldFile(String path, long size) {
	}

	/**
	 * 复制文件并在读取过程中计算 SHA-256。复制前后检查大小与修改时间，
	 * 若源文件在复制过程中被修改则重试，避免得到半写内容。
	 */
	public static CopyResult copyAndHash(Path source, Path target, LongConsumer progress,
										 BooleanSupplier cancelled) throws IOException, CancelledException {
		Path parent = target.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		IOException lastError = null;
		for (int attempt = 0; attempt < MAX_STABLE_ATTEMPTS; attempt++) {
			long sizeBefore;
			long modifiedBefore;
			try {
				sizeBefore = Files.size(source);
				modifiedBefore = Files.getLastModifiedTime(source).toMillis();
			} catch (IOException e) {
				throw e;
			}
			MessageDigest digest = Hashing.sha256Digest();
			long copied = 0;
			boolean failed = false;
			try (InputStream in = Files.newInputStream(source);
				 OutputStream out = Files.newOutputStream(target)) {
				byte[] buffer = new byte[BUFFER_SIZE];
				int read;
				while ((read = in.read(buffer)) >= 0) {
					if (cancelled != null && cancelled.getAsBoolean()) {
						throw new CancelledException("备份已取消");
					}
					out.write(buffer, 0, read);
					digest.update(buffer, 0, read);
					copied += read;
					if (progress != null) {
						progress.accept(read);
					}
				}
			} catch (CancelledException e) {
				deleteQuietly(target);
				throw e;
			} catch (IOException e) {
				failed = true;
				lastError = e;
			}
			if (failed) {
				deleteQuietly(target);
				sleepQuietly(50);
				continue;
			}
			long sizeAfter;
			long modifiedAfter;
			try {
				sizeAfter = Files.size(source);
				modifiedAfter = Files.getLastModifiedTime(source).toMillis();
			} catch (IOException e) {
				deleteQuietly(target);
				throw e;
			}
			if (sizeBefore == copied && sizeAfter == sizeBefore && modifiedAfter == modifiedBefore) {
				return new CopyResult(copied, Hashing.toHex(digest.digest()));
			}
			deleteQuietly(target);
			lastError = new IOException("源文件在复制过程中发生变化: " + source);
			sleepQuietly(50);
		}
		deleteQuietly(target);
		throw lastError != null ? lastError : new IOException("复制失败: " + source);
	}

	/**
	 * 计算文件 SHA-256，并确保读取期间文件大小/修改时间未变化。
	 */
	public static CopyResult hashStable(Path source, LongConsumer progress,
										BooleanSupplier cancelled) throws IOException, CancelledException {
		IOException lastError = null;
		for (int attempt = 0; attempt < MAX_STABLE_ATTEMPTS; attempt++) {
			long sizeBefore = Files.size(source);
			long modifiedBefore = Files.getLastModifiedTime(source).toMillis();
			MessageDigest digest = Hashing.sha256Digest();
			long readTotal = 0;
			try (InputStream in = Files.newInputStream(source)) {
				byte[] buffer = new byte[BUFFER_SIZE];
				int read;
				while ((read = in.read(buffer)) >= 0) {
					if (cancelled != null && cancelled.getAsBoolean()) {
						throw new CancelledException("备份已取消");
					}
					digest.update(buffer, 0, read);
					readTotal += read;
					if (progress != null) {
						progress.accept(read);
					}
				}
			}
			long sizeAfter = Files.size(source);
			long modifiedAfter = Files.getLastModifiedTime(source).toMillis();
			if (sizeBefore == readTotal && sizeAfter == sizeBefore && modifiedAfter == modifiedBefore) {
				return new CopyResult(readTotal, Hashing.toHex(digest.digest()));
			}
			lastError = new IOException("源文件在读取过程中发生变化: " + source);
			sleepQuietly(50);
		}
		throw lastError != null ? lastError : new IOException("读取失败: " + source);
	}

	/**
	 * 列出世界目录中的所有普通文件（不跟随符号链接），返回按路径排序的相对路径与大小。
	 */
	public static List<WorldFile> listWorldFiles(Path root) throws IOException {
		List<WorldFile> files = new ArrayList<>();
		if (!Files.isDirectory(root)) {
			throw new IOException("存档目录不存在: " + root);
		}
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (attrs.isRegularFile() && !attrs.isSymbolicLink()) {
					String name = file.getFileName().toString();
					if (!SESSION_LOCK.equals(name)) {
						files.add(new WorldFile(toRelative(root, file), attrs.size()));
					}
				}
				return FileVisitResult.CONTINUE;
			}
		});
		files.sort(Comparator.comparing(WorldFile::path));
		return files;
	}

	public static String toRelative(Path root, Path file) {
		return root.relativize(file).toString().replace('\\', '/');
	}

	/**
	 * 把索引中的相对路径安全地解析到备份目录内。拒绝绝对路径、盘符、{@code ..} 与空片段。
	 */
	public static Path resolveRelative(Path root, String relative) throws IOException {
		if (relative == null || relative.isEmpty()) {
			throw new IOException("非法的相对路径: " + relative);
		}
		if (relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0) {
			throw new IOException("非法的相对路径: " + relative);
		}
		if (relative.startsWith("/")) {
			throw new IOException("非法的相对路径: " + relative);
		}
		String[] parts = relative.split("/", -1);
		for (String part : parts) {
			if (part.isEmpty() || part.equals(".") || part.equals("..")) {
				throw new IOException("非法的相对路径: " + relative);
			}
		}
		Path normalizedRoot = root.toAbsolutePath().normalize();
		Path resolved;
		try {
			resolved = normalizedRoot.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
		} catch (InvalidPathException e) {
			throw new IOException("非法的相对路径: " + relative, e);
		}
		if (!resolved.startsWith(normalizedRoot)) {
			throw new IOException("非法的相对路径: " + relative);
		}
		return resolved;
	}

	/**
	 * 移动文件/目录；优先原子移动，失败时退化为复制后删除。
	 */
	public static void move(Path source, Path target) throws IOException {
		if (Files.exists(target)) {
			throw new IOException("目标已存在: " + target);
		}
		Path parent = target.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
			return;
		} catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
			// fall through
		} catch (IOException e) {
			// Windows 上跨卷/被占用时也退化为复制，但仅当目标尚未创建
			if (Files.exists(target)) {
				throw e;
			}
		}
		try {
			copyRecursive(source, target);
		} catch (IOException e) {
			deleteQuietlyRecursive(target);
			throw e;
		}
		deleteRecursive(source);
	}

	public static void copyRecursive(Path source, Path target) throws IOException {
		if (Files.isSymbolicLink(source)) {
			throw new IOException("不支持复制符号链接: " + source);
		}
		if (Files.isDirectory(source)) {
			Files.walkFileTree(source, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					if (attrs.isSymbolicLink()) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					Files.createDirectories(target.resolve(source.relativize(dir).toString()));
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (attrs.isRegularFile() && !attrs.isSymbolicLink()) {
						Files.copy(file, target.resolve(source.relativize(file).toString()),
								StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} else {
			Path parent = target.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static void deleteRecursive(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}
		if (!Files.isDirectory(path)) {
			deleteWithRetry(path);
			return;
		}
		Files.walkFileTree(path, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				deleteWithRetry(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc != null) {
					throw exc;
				}
				deleteWithRetry(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void deleteWithRetry(Path path) throws IOException {
		IOException lastError = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				Files.deleteIfExists(path);
				return;
			} catch (IOException e) {
				lastError = e;
				sleepQuietly(50);
			}
		}
		throw lastError;
	}

	public static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
		}
	}

	public static void deleteQuietlyRecursive(Path path) {
		try {
			deleteRecursive(path);
		} catch (IOException ignored) {
		}
	}

	public static long directorySize(Path root) {
		if (root == null || !Files.isDirectory(root)) {
			return 0L;
		}
		long[] total = {0L};
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (attrs.isRegularFile()) {
						total[0] += attrs.size();
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ignored) {
		}
		return total[0];
	}

	public static long usableSpace(Path location) {
		try {
			FileStore store = Files.getFileStore(location);
			return store.getUsableSpace();
		} catch (IOException e) {
			return Long.MAX_VALUE;
		}
	}

	public static String formatSize(long bytes) {
		if (bytes < 1024L) {
			return bytes + " B";
		}
		double value = bytes;
		String[] units = {"KB", "MB", "GB", "TB"};
		int unit = -1;
		do {
			value /= 1024.0;
			unit++;
		} while (value >= 1024.0 && unit < units.length - 1);
		return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
	}

	/**
	 * 校验 level.dat 是否是 gzip 压缩的 NBT 或未压缩 NBT。
	 */
	public static boolean isValidLevelDat(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return false;
		}
		try (InputStream in = Files.newInputStream(file)) {
			byte[] head = in.readNBytes(2);
			if (head.length < 1) {
				return false;
			}
			if (head[0] == (byte) 0x1F && head.length > 1 && head[1] == (byte) 0x8B) {
				return true;
			}
			return head[0] == 0x0A;
		} catch (IOException e) {
			return false;
		}
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
