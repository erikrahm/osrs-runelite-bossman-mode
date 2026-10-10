/*
 * Bossman Mode — the unlock ledger: every unlocked item with its provenance, per-source kill counts,
 * and rungs the player marked complete by hand. Persisted as JSON in the RuneLite profile folder.
 */
package com.bossman;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Ledger
{
	private static final Logger log = LoggerFactory.getLogger(Ledger.class);

	public static final String KIND_DROP = "drop";
	public static final String KIND_RECEIPT = "receipt";
	public static final String KIND_MANUAL = "manual";
	public static final String KIND_DEFAULT = "default";

	public static class Entry
	{
		public int itemId;
		public String name;
		public String kind;     // drop / receipt / manual / default
		public String source;   // boss or event name
		public Integer kc;      // KC at the source when it dropped
		public long time;       // epoch millis
		public String reason;   // for manual unlocks
	}

	/** item id → entry (insertion order = unlock order) */
	public Map<Integer, Entry> unlocks = new LinkedHashMap<>();
	/** source name → kills seen */
	public Map<String, Integer> kc = new HashMap<>();
	/** boss names of rungs the player marked complete by hand (no-log rungs like the Gemstone Crab) */
	public Set<String> manualComplete = new HashSet<>();

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
		if (l.unlocks == null)
		{
			l.unlocks = new LinkedHashMap<>();
		}
		if (l.kc == null)
		{
			l.kc = new HashMap<>();
		}
		if (l.manualComplete == null)
		{
			l.manualComplete = new HashSet<>();
		}
		l.file = file;
		return l;
	}

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
			Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
		int n = kc.getOrDefault(source, 0) + 1;
		kc.put(source, n);
		return n;
	}

	public int getKc(String source)
	{
		return kc.getOrDefault(source, 0);
	}

	public void setKcIfHigher(String source, int n)
	{
		if (n > kc.getOrDefault(source, 0))
		{
			kc.put(source, n);
		}
	}

	public List<Entry> recent(int n)
	{
		List<Entry> all = new ArrayList<>(unlocks.values());
		int from = Math.max(0, all.size() - n);
		List<Entry> out = new ArrayList<>(all.subList(from, all.size()));
		java.util.Collections.reverse(out);
		return out;
	}
}
