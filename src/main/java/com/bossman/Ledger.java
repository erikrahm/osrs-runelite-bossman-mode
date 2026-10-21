/*
 * Bossman Mode: the unlock ledger. Every unlocked item with its provenance, per-source kill counts, rungs the
 * player marked complete by hand, the backlog and completed combat tasks. Persisted as JSON at the path
 * BossmanModePlugin.ledgerFile() gives (the plugin's data directory, <world type>-<account hash>/). All file access
 * goes through RuneLite's Filepath, which keeps it inside that directory.
 *
 * Mutated on the client thread, read on the Swing thread by the panel: every collection is concurrent so
 * a rebuild never trips over a write. Saving is the plugin's job (debounced); this class does the I/O and keeps
 * its own "has unsaved changes" flag, so one ledger's pending save can never be mistaken for another's.
 */
package com.bossman;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.client.util.Filepath;
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
		public String reason;   // why it unlocked when it wasn't a plain drop (receipt rule note, collection-log import)
	}

	/** item id → entry. Declared as Map so Gson can read a file holding nulls; {@link #normalize} makes it concurrent. */
	public Map<Integer, Entry> unlocks = new ConcurrentHashMap<>();
	/**
	 * KC key → kills seen. The key is the source name, or "Source (Mode)" for a raid mode the game counts on its own
	 * ("Chambers of Xeric (Challenge Mode)"); {@link #getKcAllModes} adds the modes up.
	 */
	public Map<String, Integer> kc = new ConcurrentHashMap<>();
	/** boss names of rungs the player marked complete by hand (no-log rungs like the Gemstone Crab) */
	public Set<String> manualComplete = ConcurrentHashMap.newKeySet();
	/** boss name of the skilling rung the player is doing before the ladder's next boss; null follows the ladder */
	public volatile String focus;
	/** collection-log items moved to the backlog by hand, as "boss|item" (they stop gating the ladder) */
	public Set<String> backlog = ConcurrentHashMap.newKeySet();
	/** completed combat task titles as {@link #caKey}s, synced from the game's completion varps */
	public Set<String> caDone = ConcurrentHashMap.newKeySet();
	/**
	 * sources whose KC the game reports in chat ("Your X kill count is: N"). From the first such message on, chat is
	 * the only thing that sets their KC: counting loot as well would be one ahead, or a whole raid ahead when the
	 * chest is opened long after the completion message.
	 */
	public Set<String> kcFromChat = ConcurrentHashMap.newKeySet();

	private transient Filepath file;
	/** set when the file on disk could not be read and was moved aside to this name (the plugin tells the player) */
	private transient Filepath setAside;
	private final transient AtomicBoolean dirty = new AtomicBoolean();

	/**
	 * Read the ledger at {@code file}, or start an empty one if there is none. A file that exists but can't be read is
	 * never overwritten: it is renamed to {@code *.corrupt-<time>} first, so the player's progress can be recovered.
	 */
	public static Ledger load(Gson gson, Filepath file)
	{
		Ledger l = null;
		Filepath setAside = null;
		if (file.exists())
		{
			try (Reader r = file.openBufferedReader())
			{
				l = gson.fromJson(r, Ledger.class);
				if (l != null)
				{
					l.normalize();
				}
			}
			catch (Exception e)
			{
				log.warn("could not read ledger {}", file, e);
				l = null;
			}
			if (l == null)
			{
				setAside = file.getParent().joinSegment(file.getFileName() + ".corrupt-" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()));
				try
				{
					file.moveTo(setAside);
				}
				catch (IOException e)
				{
					// can't move it out of the way: refuse to save over it this session
					log.warn("could not set aside unreadable ledger {}", file, e);
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
		l.file = file;
		l.setAside = setAside;
		return l;
	}

	/**
	 * Gson builds whatever it likes for interface-typed fields, and a hand-edited file may hold nulls: re-home
	 * everything in concurrent collections, dropping nulls, with combat task titles lower-cased for lookup.
	 */
	private void normalize()
	{
		ConcurrentMap<Integer, Entry> u = new ConcurrentHashMap<>();
		if (unlocks != null)
		{
			unlocks.forEach((k, v) ->
			{
				if (k != null && v != null)
				{
					u.put(k, v);
				}
			});
		}
		unlocks = u;
		ConcurrentMap<String, Integer> k = new ConcurrentHashMap<>();
		if (kc != null)
		{
			kc.forEach((key, v) ->
			{
				if (key != null && v != null)
				{
					k.put(key, v);
				}
			});
		}
		kc = k;
		manualComplete = concurrentSet(manualComplete, false);
		backlog = concurrentSet(backlog, false);
		caDone = concurrentSet(caDone, true);   // keys: older files hold raw titles
		kcFromChat = concurrentSet(kcFromChat, false);
	}

	private static Set<String> concurrentSet(Set<String> s, boolean lowerCase)
	{
		Set<String> out = ConcurrentHashMap.newKeySet();
		if (s != null)
		{
			for (String x : s)
			{
				if (x != null)
				{
					out.add(lowerCase ? caKey(x) : x);
				}
			}
		}
		return out;
	}

	/** Note a change that hasn't been written yet. */
	public void markDirty()
	{
		dirty.set(true);
	}

	public boolean isDirty()
	{
		return dirty.get();
	}

	/**
	 * Write the ledger if it has unsaved changes.
	 *
	 * @return false if a write was needed and failed (the ledger stays dirty)
	 */
	public synchronized boolean saveIfDirty(Gson gson)
	{
		if (!dirty.getAndSet(false))
		{
			return true;
		}
		if (save(gson))
		{
			return true;
		}
		dirty.set(true);
		return false;
	}

	/**
	 * Write atomically (own temp file + rename). Synchronized, so a scheduled save and a flush on logout can't
	 * interleave; a client-thread write that lands mid-save is picked up by the next one.
	 *
	 * @return false if the write failed
	 */
	public synchronized boolean save(Gson gson)
	{
		if (file == null)
		{
			return true;   // nothing to save to (placeholder before login, or a file we refused to overwrite)
		}
		Filepath tmp = null;
		try
		{
			Filepath parent = file.getParent();
			parent.createDirectories();
			tmp = parent.createTempFile(file.getFileName(), ".tmp");
			try (Writer w = tmp.openBufferedWriter())
			{
				gson.toJson(this, w);
			}
			try
			{
				tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
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
					tmp.deleteIfExists();
				}
				catch (IOException ignored)
				{
				}
			}
			return false;
		}
	}

	/**
	 * Delete the file and detach this ledger from it, under the save lock, so a save already under way can't write
	 * the file back afterwards (!bmreset).
	 *
	 * @return false if the file exists and couldn't be deleted
	 */
	public synchronized boolean discard()
	{
		if (file != null)
		{
			try
			{
				file.deleteIfExists();
			}
			catch (IOException e)
			{
				log.warn("could not delete ledger {}", file, e);
				return false;
			}
		}
		file = null;
		dirty.set(false);
		return true;
	}

	public Filepath getFile()
	{
		return file;
	}

	/** Where an unreadable ledger file was moved to (or the file itself, if it couldn't be moved), else null. */
	public Filepath getSetAside()
	{
		return setAside;
	}

	public boolean isUnlocked(int itemId)
	{
		return unlocks.containsKey(itemId);
	}

	public int getKc(String key)
	{
		return kc.getOrDefault(key, 0);
	}

	/** The source's KC plus every raid mode counted separately for it ("Chambers of Xeric (Challenge Mode)"). */
	public int getKcAllModes(String source)
	{
		int total = 0;
		String modePrefix = source + " (";
		for (Map.Entry<String, Integer> e : kc.entrySet())
		{
			if (e.getKey().equals(source) || e.getKey().startsWith(modePrefix))
			{
				total += e.getValue();
			}
		}
		return total;
	}

	/** The game reported a KC in chat for {@code source} (stored under {@code key}: the source, or one of its raid modes). Authoritative from now on. */
	public void setKcFromChat(String source, String key, int n)
	{
		kcFromChat.add(source);
		kc.put(key, n);   // exact: it also corrects any over-count from before the first message
	}

	/** A kill (or chest) seen through loot: counted unless the game reports this source's KC itself. */
	/**
	 * The KC a collection log tab shows, for a source whose KC the game never puts in chat in a form the plugin reads
	 * (Wintertodt's reward claims). Exact, like chat; a source that does report in chat keeps its chat count.
	 */
	public boolean setKcFromCollectionLog(String source, int n)
	{
		if (kcFromChat.contains(source))
		{
			return false;
		}
		Integer old = kc.put(source, n);
		return old == null || old != n;
	}

	public int countLoot(String source, int amount)
	{
		if (!kcFromChat.contains(source))
		{
			kc.merge(source, amount, Integer::sum);
		}
		return getKcAllModes(source);
	}

	public boolean isCaDone(String taskTitle)
	{
		return taskTitle != null && caDone.contains(caKey(taskTitle));
	}

	public boolean addCaDone(String taskTitle)
	{
		return caDone.add(caKey(taskTitle));
	}

	/**
	 * A combat task title as compared: the game's cache and the wiki-scraped ladder spell titles independently, so
	 * case, curly apostrophes, spacing and a trailing full stop are ignored.
	 */
	static String caKey(String title)
	{
		String t = title.toLowerCase(Locale.ROOT).replace('\u2019', '\'').replace('\u2018', '\'').replaceAll("\\s+", " ").trim();
		return t.endsWith(".") ? t.substring(0, t.length() - 1).trim() : t;
	}
}
