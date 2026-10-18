package cn.qinzhikeji.easybackup.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 索引/日志 JSON 读写工具。写入时先写临时文件再替换，避免半写入文件。
 */
public final class Json {
	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	private Json() {
	}

	public static <T> T read(Path file, Class<T> type) throws IOException {
		String text = Files.readString(file, StandardCharsets.UTF_8);
		try {
			return GSON.fromJson(text, type);
		} catch (JsonParseException e) {
			throw new IOException("无法解析 JSON: " + file, e);
		}
	}

	public static void write(Path file, Object value) throws IOException {
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(value), StandardCharsets.UTF_8);
		moveReplace(tmp, file);
	}

	public static void moveReplace(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
