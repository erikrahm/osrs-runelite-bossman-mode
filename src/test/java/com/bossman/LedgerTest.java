package com.bossman;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LedgerTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final Gson gson = new Gson();

	/** The plugin gets a Filepath from getPluginDirectory(); tests root one at a temporary folder. */
	private Filepath ledgerFile() throws Exception
	{
		return Filepath.Unchecked.getRooted(tmp.newFolder("standard-123").toPath()).joinSegment("bossman-mode-ledger.json");
	}

	private static File file(Filepath f)
	{
		return Filepath.Unchecked.getFile(f);
	}

	private static String read(Filepath f) throws Exception
	{
		return new String(Files.readAllBytes(file(f).toPath()), StandardCharsets.UTF_8);
	}

	private static void write(Filepath f, String text) throws Exception
	{
		Files.write(file(f).toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void everyFieldSurvivesASaveAndLoad() throws Exception
	{
		Filepath f = ledgerFile();
		Ledger l = Ledger.load(gson, f);
		Ledger.Entry e = new Ledger.Entry();
		e.itemId = 4151;
		e.name = "Abyssal whip";
		e.kind = Ledger.KIND_DROP;
		e.source = "Abyssal Sire";
		e.kc = 12;
		l.unlocks.put(e.itemId, e);
		l.setKcFromChat("Chambers of Xeric", "Chambers of Xeric (Challenge Mode)", 4);
		l.countLoot("Vorkath", 1);
		l.manualComplete.add("Gemstone Crab");
		l.backlog.add("Vorkath|Draconic visage");
		l.addCaDone("Noxious Foe");
		assertTrue(l.save(gson));

		Ledger back = Ledger.load(gson, f);
		assertEquals("Abyssal whip", back.unlocks.get(4151).name);   // integer map keys come back as integers
		assertEquals(Integer.valueOf(12), back.unlocks.get(4151).kc);
		assertEquals(4, back.getKc("Chambers of Xeric (Challenge Mode)"));
		assertTrue(back.kcFromChat.contains("Chambers of Xeric"));
		assertEquals(1, back.getKc("Vorkath"));
		assertTrue(back.manualComplete.contains("Gemstone Crab"));
		assertTrue(back.backlog.contains("Vorkath|Draconic visage"));
		assertTrue(back.isCaDone("noxious foe"));
		assertNull(back.getSetAside());
	}

	@Test
	public void collectionsAreConcurrentAfterLoading() throws Exception
	{
		Filepath f = ledgerFile();
		write(f, "{\"unlocks\":{},\"kc\":{},\"backlog\":[\"a|b\"]}");
		Ledger l = Ledger.load(gson, f);
		assertTrue(l.unlocks instanceof ConcurrentHashMap);   // whatever Gson built
		assertTrue(l.kc instanceof ConcurrentHashMap);
		assertTrue(l.backlog.getClass().getName().contains("Concurrent"));
		assertNotNull(l.caDone);   // missing in the file: still usable
	}

	@Test
	public void aCorruptFileIsSetAsideNotOverwritten() throws Exception
	{
		Filepath f = ledgerFile();
		write(f, "{\"unlocks\": {");
		Ledger l = Ledger.load(gson, f);
		assertNotNull(l.getSetAside());
		assertEquals("{\"unlocks\": {", read(l.getSetAside()));   // the player's bytes are kept
		assertTrue(l.unlocks.isEmpty());
		l.markDirty();
		assertTrue(l.saveIfDirty(gson));
		assertTrue(f.exists());                                      // a fresh ledger saves next to it
		assertEquals("{\"unlocks\": {", read(l.getSetAside()));
	}

	@Test
	public void nullsInAHandEditedFileAreDropped() throws Exception
	{
		Filepath f = ledgerFile();
		write(f, "{\"backlog\":[null,\"a|b\"],\"caDone\":[null],\"kc\":{\"Vorkath\":null}}");
		Ledger l = Ledger.load(gson, f);
		assertNull(l.getSetAside());
		assertEquals(1, l.backlog.size());
		assertTrue(l.caDone.isEmpty());
		assertEquals(0, l.getKc("Vorkath"));
	}

	@Test
	public void aFileThatCantBeMovedAsideIsNeverWritten() throws Exception
	{
		File dir = tmp.newFolder("locked");
		Filepath f = Filepath.Unchecked.getRooted(dir.toPath()).joinSegment("bossman-mode-ledger.json");
		write(f, "not json");
		assertTrue(dir.setWritable(false));   // no rename possible in a read-only folder
		try
		{
			Ledger l = Ledger.load(gson, f);
			assertEquals(f, l.getSetAside());
			assertNull(l.getFile());
			l.markDirty();
			assertTrue(l.saveIfDirty(gson));  // a no-op...
			assertEquals("not json", read(f)); // ...that leaves the bytes alone
		}
		finally
		{
			dir.setWritable(true);
		}
	}

	@Test
	public void saveIfDirtyWritesOnlyWhenSomethingChanged() throws Exception
	{
		Filepath f = ledgerFile();
		Ledger l = Ledger.load(gson, f);
		assertTrue(l.saveIfDirty(gson));
		assertFalse(f.exists());
		l.markDirty();
		assertTrue(l.saveIfDirty(gson));
		assertTrue(f.exists());
		assertFalse(l.isDirty());
	}

	@Test
	public void discardDeletesAndDetaches() throws Exception
	{
		Filepath f = ledgerFile();
		Ledger l = Ledger.load(gson, f);
		assertTrue(l.save(gson));
		assertTrue(l.discard());
		assertFalse(f.exists());
		l.markDirty();
		assertTrue(l.saveIfDirty(gson));
		assertFalse(f.exists());   // a save after the reset can't bring the file back
	}

	@Test
	public void caTitlesMatchAcrossSpellings()
	{
		Ledger l = new Ledger();
		l.addCaDone("Whack-a-Mole");
		assertTrue(l.isCaDone("whack-a-mole."));
		l.addCaDone("Can’t Touch Me");
		assertTrue(l.isCaDone("Can't  touch me"));
	}

	@Test
	public void collectionLogKcFillsOnlySourcesChatDoesntReport()
	{
		Ledger l = new Ledger();
		l.countLoot("Reward cart (Wintertodt)", 1);
		assertTrue(l.setKcFromCollectionLog("Reward cart (Wintertodt)", 410));
		assertEquals(410, l.getKc("Reward cart (Wintertodt)"));
		assertFalse("unchanged", l.setKcFromCollectionLog("Reward cart (Wintertodt)", 410));
		l.setKcFromChat("Vorkath", "Vorkath", 20);
		assertFalse(l.setKcFromCollectionLog("Vorkath", 3));
		assertEquals(20, l.getKc("Vorkath"));
	}
}
