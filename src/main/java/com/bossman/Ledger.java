/*
 * Bossman Mode — the unlock ledger: every unlocked item with its provenance, per-source kill counts,
 * rungs the player marked complete by hand, the backlog and completed combat tasks.
 * Persisted as JSON in the RuneLite profile folder.
 *
 * Mutated on the client thread, read on the Swing thread by the panel: every collection is concurrent so
 * a rebuild never trips over a write. Saving is the plugin's job (debounced), this class only does the I/O.
 */
package com.bossman;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Ledger
{
	private static final Logger log = LoggerFactory.getLogger(Ledger.class);

	public static final String KIND_DROP = "drop";
	public static final String KIND_RECEIPT = "receipt";
	public static final String KIND_DEFAULT = "default";
	public static final String KIND_CLOG = "clog";       // imported from the in-game collection log (obtained before the plugin)

	public static class Entry
	{
		public int itemId;
		public String name;
		public String kind;     // drop / receipt / default / clog
		public String source;   // boss or event name
		public Integer kc;      // KC at the source when it dropped
		public long time;       // epoch millis
		public String reason;   // for default / clog unlocks
	}

	/** item id → entry */
	public ConcurrentMap<Integer, Entry> unlocks = new ConcurrentHashMap<>();
	/** source name → kills seen */
	public ConcurrentMap<String, Integer> kc = new ConcurrentHashMap<>();
	/** boss names of rungs the player marked complete by hand (no-log rungs like the Gemstone Crab) */
	public Set<String> manualComplete = ConcurrentHashMap.newKeySet();
	/** collection-log items moved to the backlog by hand, as "boss|item" (they stop gating the ladder) */
	public Set<String> backlog = ConcurrentHashMap.newKeySet();
	/** combat achievement task names seen completed in chat */
	public Set<String> caDone = ConcurrentHashMap.newKeySet();

	private transient File file;

	public static Ledger load(Gson gson, File file)
	{
		Ledger l = null;
		if (file.exists())
		{
			try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
			{
				l = gson.fromJson(r, Ledger.class);
			}
			catch (Exception e)
			{
				log.warn("could not read ledger {}", file, e);
			}
		}
		if (l == null)
		{
			l = new Ledger();
		}
		// Gson builds whatever it likes for interface-typed fields; re-home everything in concurrent collections
		l.unlocks = concurrent(l.unlocks);
		l.kc = concurrent(l.kc);
		l.manualComplete = concurrentSet(l.manualComplete);
		l.backlog = concurrentSet(l.backlog);
		l.caDone = concurrentSet(l.caDone);
		l.file = file;
		return l;
	}

	private static <K, V> ConcurrentMap<K, V> concurrent(Map<K, V> m)
	{
		ConcurrentMap<K, V> out = new ConcurrentHashMap<>();
		if (m != null)
		{
			out.putAll(m);
		}
		return out;
	}

	private static <T> Set<T> concurrentSet(Set<T> s)
	{
		Set<T> out = ConcurrentHashMap.newKeySet();
		if (s != null)
		{
			out.addAll(s);
		}
		return out;
	}

	/** Write atomically (temp file + rename). Safe to call while the client thread keeps writing: a write that lands mid-save is picked up by the next one. */
	public void save(Gson gson)
	{
		if (file == null)
		{
			return;
		}
		try
		{
			File parent = file.getParentFile();
			if (parent != null && !parent.exists())
			{
				parent.mkdirs();
			}
			File tmp = new File(file.getPath() + ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8))
			{
				gson.toJson(this, w);
			}
			Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			log.warn("could not save ledger {}", file, e);
		}
	}

	public File getFile()
	{
		return file;
	}

	public boolean isUnlocked(int itemId)
	{
		return unlocks.containsKey(itemId);
	}

	public int incrementKc(String source)
	{
		return kc.merge(source, 1, Integer::sum);
	}

	public int getKc(String source)
	{
		return kc.getOrDefault(source, 0);
	}

	public void setKcIfHigher(String source, int n)
	{
		kc.merge(source, n, Math::max);
	}
}
