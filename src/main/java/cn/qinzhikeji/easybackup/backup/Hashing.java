package cn.qinzhikeji.easybackup.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Hashing {
	private Hashing() {
	}

	public static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	public static String sha256(Path file) throws IOException {
		MessageDigest digest = sha256Digest();
		byte[] buffer = new byte[64 * 1024];
		try (var in = Files.newInputStream(file)) {
			int read;
			while ((read = in.read(buffer)) >= 0) {
				digest.update(buffer, 0, read);
			}
		}
		return toHex(digest.digest());
	}

	public static String toHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			builder.append(Character.forDigit((b >> 4) & 0xF, 16));
			builder.append(Character.forDigit(b & 0xF, 16));
		}
		return builder.toString();
	}
}
