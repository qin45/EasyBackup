package cn.qinzhikeji.easybackup.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalSelectionTest {
	private static final String LEVEL_ID = "新的世界1";

	@TempDir
	Path temp;

	@Test
	void changedFilesAreCopiedAndUnchangedFilesStayInOldBackup() throws Exception {
		Path world = createWorld();
		BackupEngine engine = new BackupEngine(temp.resolve("easybackup"));

		BackupIndex first = engine.createBackup(world, LEVEL_ID, "新的世界1",
				BackupType.INCREMENTAL, "b1", ProgressListener.NONE);
		assertEquals(4, first.files.size());
		assertTrue(first.addedBytes > 0);

		write(world, "1.mca", "one");
		write(world, "2.mca", "two-changed");
		Files.delete(world.resolve("3.mca"));
		write(world, "4.mca", "four");

		BackupIndex second = engine.createBackup(world, LEVEL_ID, "新的世界1",
				BackupType.INCREMENTAL, "b2", ProgressListener.NONE);

		assertEquals(first.id, second.files.get("1.mca").sourceId);
		assertEquals("b1", second.files.get("1.mca").sourceName);
		assertEquals(second.id, second.files.get("2.mca").sourceId);
		assertEquals("b2", second.files.get("2.mca").sourceName);
		assertEquals(second.id, second.files.get("4.mca").sourceId);
		assertFalse(second.files.containsKey("3.mca"));
		assertEquals(4, second.totalFiles);

		long expectedAdded = Files.size(world.resolve("2.mca")) + Files.size(world.resolve("4.mca"));
		assertEquals(expectedAdded, second.addedBytes);

		Path secondDir = engine.paths().incrementalRoot(LEVEL_ID).resolve("b2");
		assertTrue(Files.exists(secondDir.resolve("2.mca")));
		assertTrue(Files.exists(secondDir.resolve("4.mca")));
		assertFalse(Files.exists(secondDir.resolve("1.mca")));
		assertFalse(Files.exists(secondDir.resolve("3.mca")));
		assertTrue(Files.exists(secondDir.resolve(BackupPaths.INDEX_FILE)));
	}

	@Test
	void renameUpdatesSourceNamesAndDeleteIncrementalKeepsFiles() throws Exception {
		Path world = createWorld();
		BackupEngine engine = new BackupEngine(temp.resolve("easybackup"));
		BackupIndex first = engine.createBackup(world, LEVEL_ID, "新的世界1",
				BackupType.INCREMENTAL, "b1", ProgressListener.NONE);
		BackupIndex second = engine.createBackup(world, LEVEL_ID, "新的世界1",
				BackupType.INCREMENTAL, "b2", ProgressListener.NONE);
		assertEquals(first.id, second.files.get("1.mca").sourceId);

		BackupInfo firstInfo = engine.findBackup(LEVEL_ID, first.id).orElseThrow();
		BackupInfo renamed = engine.renameBackup(LEVEL_ID, firstInfo, "b1-renamed");
		assertTrue(Files.isDirectory(renamed.directory()));
		assertFalse(Files.exists(engine.paths().incrementalRoot(LEVEL_ID).resolve("b1")));

		BackupInfo secondInfo = engine.findBackup(LEVEL_ID, second.id).orElseThrow();
		assertEquals("b1-renamed", secondInfo.index().files.get("1.mca").sourceName);
		assertEquals(first.id, secondInfo.index().files.get("2.mca").sourceId);

		long usageBefore = engine.incrementalUsage(LEVEL_ID);
		engine.deleteBackup(secondInfo);
		assertFalse(Files.exists(secondInfo.directory().resolve(BackupPaths.INDEX_FILE)));
		assertTrue(Files.exists(secondInfo.directory()));
		assertTrue(Files.exists(renamed.directory().resolve("2.mca")));
		assertTrue(engine.incrementalUsage(LEVEL_ID) > 0);
		assertTrue(usageBefore >= engine.incrementalUsage(LEVEL_ID));
	}

	@Test
	void suggestedNamesIncrementAcrossBothDirectories() throws Exception {
		Path world = createWorld();
		BackupEngine engine = new BackupEngine(temp.resolve("easybackup"));
		LocalDate date = LocalDate.of(2026, 10, 18);

		Files.createDirectories(engine.paths().fullRoot(LEVEL_ID)
				.resolve(BackupPaths.defaultName("新的世界1", date, 1)));
		Files.createDirectories(engine.paths().incrementalRoot(LEVEL_ID)
				.resolve(BackupPaths.defaultName("新的世界1", date, 3)));

		assertEquals("新的世界1-2026-10-18-4",
				engine.suggestName(LEVEL_ID, "新的世界1", date));
		assertEquals("新的世界1-2026-10-18-4-回档备份",
				engine.suggestRestoreBackupName(LEVEL_ID, "新的世界1", date));
		assertTrue(world.toFile().exists());
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
		write(root, relative, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static void write(Path root, String relative, byte[] content) throws IOException {
		Path file = root.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.write(file, content);
	}
}
