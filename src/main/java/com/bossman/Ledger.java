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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
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
	/**
	 * sources whose KC the game reports in chat ("Your X kill count is: N"). From the first such message on, chat is
	 * the only thing that sets their KC: counting loot as well would be one ahead, or a whole raid ahead when the
	 * chest is opened long after the completion message.
	 */
	public Set<String> kcFromChat = ConcurrentHashMap.newKeySet();

	private transient File file;
	/** set when the file on disk could not be read and was moved aside to this name (the plugin tells the player) */
	private transient File setAside;

	/**
	 * Read the ledger at {@code file}, or start an empty one if there is none. A file that exists but can't be read is
	 * never overwritten: it is renamed to {@code *.corrupt-<time>} first, so the player's progress can be recovered.
	 */
	public static Ledger load(Gson gson, File file)
	{
		Ledger l = null;
		File setAside = null;
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
			if (l == null)
			{
				setAside = new File(file.getPath() + ".corrupt-" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()));
				if (!file.renameTo(setAside))
				{
					// can't move it out of the way: refuse to save over it this session
					log.warn("could not set aside unreadable ledger {}", file);
					Ledger readOnly = new Ledger();
					readOnly.setAside = file;
					return readOnly;
				}
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
		l.kcFromChat = concurrentSet(l.kcFromChat);
		l.file = file;
		l.setAside = setAside;
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

	/**
	 * Write atomically (own temp file + rename). Synchronized, so a scheduled save and a flush on logout can't
	 * interleave; a client-thread write that lands mid-save is picked up by the next one.
	 *
	 * @return false if the write failed (the caller keeps the ledger dirty and tries again later)
	 */
	public synchronized boolean save(Gson gson)
	{
		if (file == null)
		{
			return true;   // nothing to save to (placeholder before login, or a file we refused to overwrite)
		}
		Path tmp = null;
		try
		{
			File parent = file.getParentFile();
			if (parent != null && !parent.exists())
			{
				parent.mkdirs();
			}
			tmp = Files.createTempFile(parent.toPath(), file.getName(), ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))
			{
				gson.toJson(this, w);
			}
			try
			{
				Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("could not save ledger {}", file, e);
			if (tmp != null)
			{
				try
				{
					Files.deleteIfExists(tmp);
				}
				catch (IOException ignored)
				{
				}
			}
			return false;
		}
	}

	public File getFile()
	{
		return file;
	}

	/** Where an unreadable ledger file was moved to (or the file itself, if it couldn't be moved), else null. */
	public File getSetAside()
	{
		return setAside;
	}

	public boolean isUnlocked(int itemId)
	{
		return unlocks.containsKey(itemId);
	}

	public int getKc(String source)
	{
		return kc.getOrDefault(source, 0);
	}

	/** The game reported this source's KC in chat: it is authoritative from now on. */
	public void setKcFromChat(String source, int n)
	{
		kcFromChat.add(source);
		kc.put(source, n);   // exact: it also corrects any over-count from before the first message
	}

	/** A kill (or chest) seen through loot: counted unless the game reports this source's KC itself. */
	public int countLoot(String source, int amount)
	{
		if (kcFromChat.contains(source))
		{
			return getKc(source);
		}
		return kc.merge(source, amount, Integer::sum);
	}
}
