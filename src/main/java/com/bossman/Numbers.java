/*
 * Bossman Mode: thousands separators for the numbers the panel, banner and chat show.
 */
package com.bossman;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Numbers
{
	/** a run of four or more digits that isn't already part of a separated or decimal number */
	private static final Pattern LONG_NUMBER = Pattern.compile("(?<![\\d.,])\\d{4,}(?![\\d,])");

	private Numbers()
	{
	}

	static String format(long n)
	{
		return String.format(Locale.ROOT, "%,d", n);
	}

	/** Drop rates and notes from the bundled data are text ("1/5000"): separate every long number in it. */
	static String withCommas(String text)
	{
		if (text == null)
		{
			return null;
		}
		Matcher m = LONG_NUMBER.matcher(text);
		StringBuffer out = new StringBuffer();
		while (m.find())
		{
			m.appendReplacement(out, format(Long.parseLong(m.group())));
		}
		m.appendTail(out);
		return out.toString();
	}
}
