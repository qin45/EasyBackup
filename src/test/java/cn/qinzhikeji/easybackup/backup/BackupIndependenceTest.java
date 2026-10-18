package cn.qinzhikeji.easybackup.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全量备份与增量备份必须彼此独立：增量备份不引用全量备份的文件，
 * 删除全量备份后增量备份仍然可以完整回档。
 */
class BackupIndependenceTest {
	private static final String LEVEL_ID = "world";

	@TempDir
	Path temp;

	@Test
	void incrementalBackupNeverReferencesFullBackup() throws Exception {
		Path world = createWorld();
		BackupEngine engine = new BackupEngine(temp.resolve("easybackup"));

		BackupIndex full = engine.createBackup(world, LEVEL_ID, "world",
				BackupType.FULL, "f1", ProgressListener.NONE);
		BackupIndex incremental = engine.createBackup(world, LEVEL_ID, "world",
				BackupType.INCREMENTAL, "i1", ProgressListener.NONE);

		assertFalse(incremental.files.isEmpty());
		for (FileRecord record : incremental.files.values()) {
			assertEquals(incremental.id, record.sourceId);
			assertEquals("i1", record.sourceName);
		}
		Path incrementalDir = engine.paths().incrementalRoot(LEVEL_ID).resolve("i1");
		for (String relative : List.of("level.dat", "1.mca", "2.mca", "3.mca")) {
			assertTrue(Files.isRegularFile(incrementalDir.resolve(relative)), relative);
		}

		BackupInfo fullInfo = engine.findBackup(LEVEL_ID, full.id).orElseThrow();
		engine.deleteBackup(fullInfo);
		assertFalse(Files.exists(fullInfo.directory()));

		BackupInfo incrementalInfo = engine.findBackup(LEVEL_ID, incremental.id).orElseThrow();
		RestorePlan plan = new RestoreEngine(temp.resolve("easybackup"))
				.createPlan(LEVEL_ID, incrementalInfo);
		assertTrue(plan.missingFiles().isEmpty(), plan.missingFiles().toString());
		assertTrue(plan.canContinue());
		assertEquals(4, plan.entries().size());
	}

	@Test
	void deletingFullBackupCopiesFilesReferencedByLegacyIncrementals() throws Exception {
		Path world = createWorld();
		Path root = temp.resolve("easybackup");
		BackupEngine engine = new BackupEngine(root);

		BackupIndex full = engine.createBackup(world, LEVEL_ID, "world",
				BackupType.FULL, "f1", ProgressListener.NONE);
		BackupIndex incremental = engine.createBackup(world, LEVEL_ID, "world",
				BackupType.INCREMENTAL, "i1", ProgressListener.NONE);

		// 模拟旧版本创建的增量备份：索引指向全量备份，自己的目录里没有这些文件。
		Path incrementalDir = engine.paths().incrementalRoot(LEVEL_ID).resolve("i1");
		Path incrementalIndexFile = incrementalDir.resolve(BackupPaths.INDEX_FILE);
		BackupIndex legacy = Json.read(incrementalIndexFile, BackupIndex.class);
		for (var entry : legacy.files.entrySet()) {
			Files.deleteIfExists(incrementalDir.resolve(entry.getKey()));
			entry.getValue().sourceId = full.id;
			entry.getValue().sourceName = "f1";
		}
		Json.write(incrementalIndexFile, legacy);

		BackupInfo fullInfo = engine.findBackup(LEVEL_ID, full.id).orElseThrow();
		engine.deleteBackup(fullInfo);
		assertFalse(Files.exists(fullInfo.directory()));

		BackupInfo incrementalInfo = engine.findBackup(LEVEL_ID, incremental.id).orElseThrow();
		for (var entry : incrementalInfo.index().files.entrySet()) {
			assertEquals(incremental.id, entry.getValue().sourceId);
			assertEquals("i1", entry.getValue().sourceName);
			assertTrue(Files.isRegularFile(incrementalDir.resolve(entry.getKey())),
					entry.getKey());
		}
		RestorePlan plan = new RestoreEngine(root).createPlan(LEVEL_ID, incrementalInfo);
		assertTrue(plan.missingFiles().isEmpty(), plan.missingFiles().toString());
		assertTrue(plan.canContinue());
	}

	private Path createWorld() throws IOException {
		Path world = temp.resolve("saves").resolve(LEVEL_ID);
		Files.createDirectories(world);
		write(world, "level.dat", new byte[]{0x1F, (byte) 0x8B, 0x08, 0x00, 0x00});
		write(world, "1.mca", "one");
		write(world, "2.mca", "two");
		write(world, "3.mca", "three");
		return world;
	}

	private static void write(Path root, String relative, String content) throws IOException {
		write(root, relative, content.getBytes(StandardCharsets.UTF_8));
	}

	private static void write(Path root, String relative, byte[] content) throws IOException {
		Path file = root.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.write(file, content);
	}
}
