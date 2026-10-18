package cn.qinzhikeji.easybackup.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreEngineTest {
	private static final String LEVEL_ID = "新的世界1";
	private static final byte[] LEVEL_DAT = {0x1F, (byte) 0x8B, 0x08, 0x00, 0x00};

	@TempDir
	Path temp;

	@Test
	void fullRestoreReplacesWorldAndKeepsOldWorldUntilCompletion() throws Exception {
		Path world = createWorld();
		Path root = temp.resolve("easybackup");
		BackupEngine engine = new BackupEngine(root);
		BackupIndex index = engine.createBackup(world, LEVEL_ID, "新的世界1",
				BackupType.FULL, "full", ProgressListener.NONE);
		BackupInfo info = engine.findBackup(LEVEL_ID, index.id).orElseThrow();

		write(world, "1.mca", "changed");
		Files.delete(world.resolve("2.mca"));
		write(world, "5.mca", "extra");

		RestoreEngine restore = new RestoreEngine(root);
		RestorePlan plan = restore.createPlan(LEVEL_ID, info);
		assertTrue(plan.canContinue());
		assertTrue(plan.missingFiles().isEmpty());
		assertNull(plan.criticalError());

		RestoreJournal journal = restore.restore(LEVEL_ID, "新的世界1", world, plan, ProgressListener.NONE);
		assertEquals(RestoreJournal.Phase.SWAPPED, journal.phase());
		assertEquals("one", Files.readString(world.resolve("1.mca")));
		assertEquals("two", Files.readString(world.resolve("2.mca")));
		assertFalse(Files.exists(world.resolve("5.mca")));
		assertTrue(Files.exists(world.resolve("level.dat")));

		Path old = journal.oldPath(restore.paths());
		assertNotNull(old);
		assertTrue(Files.isDirectory(old));
		assertEquals("changed", Files.readString(old.resolve("1.mca")));

		restore.completeRestore(journal);
		assertFalse(Files.exists(old));
		assertTrue(restore.readJournal(LEVEL_ID).isEmpty());
	}

	@Test
	void rollbackRestoresPreRestoreWorld() throws Exception {
		Path world = createWorld();
		Path root = temp.resolve("easybackup");
		BackupEngine engine = new BackupEngine(root);
		BackupIndex index = engine.createBackup(world, LEVEL_ID, "新的世界1",
				BackupType.FULL, "full", ProgressListener.NONE);
		BackupInfo info = engine.findBackup(LEVEL_ID, index.id).orElseThrow();
		write(world, "1.mca", "changed");

		RestoreEngine restore = new RestoreEngine(root);
		RestoreJournal journal = restore.restore(LEVEL_ID, "新的世界1", world,
				restore.createPlan(LEVEL_ID, info), ProgressListener.NONE);
		assertEquals("one", Files.readString(world.resolve("1.mca")));

		restore.rollback(journal);
		assertEquals("changed", Files.readString(world.resolve("1.mca")));
		assertTrue(restore.readJournal(LEVEL_ID).isEmpty());
	}

	@Test
	void missingFilesAreReportedAndLevelDatIsCritical() throws Exception {
		Path world = createWorld();
		Path root = temp.resolve("easybackup");
		BackupEngine engine = new BackupEngine(root);
		BackupIndex index = engine.createBackup(world, LEVEL_ID, "新的世界1",
				BackupType.FULL, "full", ProgressListener.NONE);
		BackupInfo info = engine.findBackup(LEVEL_ID, index.id).orElseThrow();
		RestoreEngine restore = new RestoreEngine(root);

		Files.delete(info.directory().resolve("2.mca"));
		RestorePlan plan = restore.createPlan(LEVEL_ID, info);
		assertTrue(plan.canContinue());
		assertTrue(plan.missingFiles().contains("2.mca"));

		Files.delete(info.directory().resolve("level.dat"));
		RestorePlan critical = restore.createPlan(LEVEL_ID, info);
		assertFalse(critical.canContinue());
		assertNotNull(critical.criticalError());
		assertTrue(critical.missingFiles().contains("level.dat"));
	}

	@Test
	void unsafeIndexPathAbortsPreflight() throws Exception {
		Path world = createWorld();
		Path root = temp.resolve("easybackup");
		BackupEngine engine = new BackupEngine(root);
		BackupIndex index = engine.createBackup(world, LEVEL_ID, "新的世界1",
				BackupType.FULL, "full", ProgressListener.NONE);
		BackupInfo info = engine.findBackup(LEVEL_ID, index.id).orElseThrow();
		index.files.put("../evil.mca", new FileRecord(index.id, index.name, 1, "00"));
		Json.write(info.directory().resolve(BackupPaths.INDEX_FILE), index);

		BackupInfo reloaded = engine.findBackup(LEVEL_ID, index.id).orElseThrow();
		RestoreEngine restore = new RestoreEngine(root);
		assertThrows(BackupException.class, () -> restore.createPlan(LEVEL_ID, reloaded));
	}

	@Test
	void journalRoundTripPreservesPhase() throws Exception {
		RestoreJournal journal = new RestoreJournal();
		journal.levelId = LEVEL_ID;
		journal.levelName = "新的世界1";
		journal.worldPath = temp.resolve("saves").resolve(LEVEL_ID).toAbsolutePath().toString();
		journal.backupId = "backup-id";
		journal.backupName = "full";
		journal.backupType = "full";
		journal.tempWorldDir = ".restore_tmp_test";
		journal.oldWorldDir = ".restore_old_test";
		journal.setPhase(RestoreJournal.Phase.SWAPPING);

		Path file = temp.resolve("journal.json");
		Json.write(file, journal);
		RestoreJournal read = Json.read(file, RestoreJournal.class);
		assertTrue(read.isValid());
		assertEquals(RestoreJournal.Phase.SWAPPING, read.phase());
		assertEquals(journal.worldPath, read.worldPath);
	}

	private Path createWorld() throws IOException {
		Path world = temp.resolve("saves").resolve(LEVEL_ID);
		Files.createDirectories(world);
		write(world, "level.dat", LEVEL_DAT);
		write(world, "1.mca", "one");
		write(world, "2.mca", "two");
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
