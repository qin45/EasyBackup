package cn.qinzhikeji.easybackup.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupIndexTest {
	@TempDir
	Path temp;

	@Test
	void indexJsonRoundTripKeepsUnicodePathsAndRecords() throws Exception {
		BackupIndex index = new BackupIndex();
		index.id = "6f1c9a7e-1111-2222-3333-444455556666";
		index.name = "新的世界1-2026-10-18-1";
		index.type = BackupType.INCREMENTAL.id();
		index.worldId = "新的世界1";
		index.worldName = "新的世界1";
		index.createdAt = "2026-10-18T13:45:12Z";
		index.addedBytes = 1234;
		index.totalFiles = 2;
		index.files.put("level.dat", new FileRecord(index.id, index.name, 100, "aa"));
		index.files.put("region/世界/é.mca", new FileRecord("other", "来源-备份", 200, "bb"));

		Path file = temp.resolve("easybackup.index.json");
		Json.write(file, index);

		BackupIndex read = Json.read(file, BackupIndex.class);
		assertTrue(read.isValid());
		assertEquals(index.id, read.id);
		assertEquals(index.name, read.name);
		assertTrue(read.files.containsKey("region/世界/é.mca"));
		assertEquals("来源-备份", read.files.get("region/世界/é.mca").sourceName);
		assertEquals(200, read.files.get("region/世界/é.mca").size);
		assertEquals("bb", read.files.get("region/世界/é.mca").sha256);
		assertEquals(1234, read.addedBytes);
		assertEquals(BackupType.INCREMENTAL, read.typeEnum());
		assertTrue(Files.exists(file));
	}

	@Test
	void invalidIndexIsRejected() {
		BackupIndex index = new BackupIndex();
		index.id = "id";
		index.name = "name";
		index.type = "unknown";
		assertFalse(index.isValid());

		BackupIndex valid = new BackupIndex();
		valid.id = "id";
		valid.name = "name";
		valid.type = "full";
		assertTrue(valid.isValid());
	}

	@Test
	void namesAreSanitizedAndValidated() {
		assertEquals("abcd", BackupPaths.sanitizeName("a<b>c:d"));
		assertEquals("name", BackupPaths.sanitizeName("  name...  "));
		assertFalse(BackupPaths.isValidName("a/b"));
		assertFalse(BackupPaths.isValidName(".."));
		assertFalse(BackupPaths.isValidName(" trailing "));
		assertTrue(BackupPaths.isValidName("新的世界1-2026-10-18-1"));

		String longName = "x".repeat(BackupPaths.MAX_NAME_LENGTH + 10);
		assertEquals(BackupPaths.MAX_NAME_LENGTH, BackupPaths.sanitizeName(longName).length());
	}

	@Test
	void defaultNameAndSequenceParsing() {
		LocalDate date = LocalDate.of(2026, 10, 18);
		assertEquals("新的世界1-2026-10-18-1", BackupPaths.defaultName("新的世界1", date, 1));
		assertEquals(3, BackupPaths.sequenceOf("新的世界1-2026-10-18-3", date));
		assertEquals(3, BackupPaths.sequenceOf("新的世界1-2026-10-18-3-回档备份", date));
		assertEquals(0, BackupPaths.sequenceOf("新的世界1-2026-10-19-3", date));
		assertEquals(0, BackupPaths.sequenceOf("随便改的名字", date));
	}
}
