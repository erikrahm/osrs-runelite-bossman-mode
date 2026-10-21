/*
 * Bossman Mode: reads the game's kill-count messages. No client state, so the real message wording is tested in
 * KcMessagesTest.
 */
package com.bossman;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class KcMessages
{
	/** A KC the game reported: stored under {@code key} (the source, or "Source (Mode)" for a raid mode), credited to {@code source}. */
	static final class Kc
	{
		final String source;
		final String key;
		final int count;

		Kc(String source, String key, int count)
		{
			this.source = source;
			this.key = key;
			this.count = count;
		}
	}

	/**
	 * "Your completed Chambers of Xeric count is: 12", "Your completed Theatre of Blood: Hard Mode count is: 3".
	 * Matched before {@link #KC}, which would otherwise read "completed Chambers of Xeric" as the name.
	 */
	private static final Pattern RAID = Pattern.compile("Your completed (.+?) count is:? ?([\\d,]+)", Pattern.CASE_INSENSITIVE);
	/**
	 * "Your Vorkath kill count is: 14", "Your Gauntlet completion count is: 2", "Your Lunar Chest count is: 5",
	 * "Your Barrows chest count is: 3" (the name keeps "chest"; aliases map "barrows chest" to Barrows).
	 */
	private static final Pattern KC = Pattern.compile("Your (.+?) (?:kill |completion )?count is:? ?([\\d,]+)", Pattern.CASE_INSENSITIVE);
	/** a collection log header line: "Wintertodt kills: 410", "Gauntlet completions: 7" */
	private static final Pattern CLOG_LINE = Pattern.compile("^(.+?)(?: kills| completions| count)?: ?([\\d,]+)$", Pattern.CASE_INSENSITIVE);
	/** a raid mode after the raid's name: "...: Hard Mode", "... Challenge Mode" */
	private static final Pattern MODE = Pattern.compile("^(.+?)(?::\\s*|\\s+)((?:Challenge|Hard|Entry|Expert) Mode)$", Pattern.CASE_INSENSITIVE);

	private KcMessages()
	{
	}

	/**
	 * The KC in a game message, or null if it has none or names no source.
	 *
	 * @param canonicalSource name as the game writes it → the source it counts (null if none)
	 */
	static Kc parse(String message, Function<String, String> canonicalSource)
	{
		Matcher m = RAID.matcher(message);
		if (!m.find())
		{
			m = KC.matcher(message);
			if (!m.find())
			{
				return null;
			}
		}
		int n;
		try
		{
			n = Integer.parseInt(m.group(2).replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
		// the game writes names with non-breaking spaces at times
		String name = m.group(1).replace(' ', ' ').trim();
		String mode = null;
		Matcher mm = MODE.matcher(name);
		if (mm.matches())
		{
			name = mm.group(1).trim();
			mode = capitalise(mm.group(2));
		}
		String source = canonicalSource.apply(name);
		if (source == null)
		{
			return null;
		}
		return new Kc(source, mode == null ? source : source + " (" + mode + ")", n);
	}

	/** The KC on a collection log header line, or null if the line isn't one or names no source. */
	static Kc parseCollectionLog(String line, Function<String, String> canonicalSource)
	{
		Matcher m = CLOG_LINE.matcher(line.replace('\u00A0', ' ').trim());
		if (!m.matches() || m.group(1).equalsIgnoreCase("Obtained"))
		{
			return null;
		}
		String source = canonicalSource.apply(m.group(1).trim());
		if (source == null)
		{
			return null;
		}
		try
		{
			return new Kc(source, source, Integer.parseInt(m.group(2).replace(",", "")));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private static String capitalise(String mode)
	{
		String w = mode.trim().toLowerCase(java.util.Locale.ROOT);
		return Character.toUpperCase(w.charAt(0)) + w.substring(1, w.indexOf(' ')) + " Mode";
	}
}
