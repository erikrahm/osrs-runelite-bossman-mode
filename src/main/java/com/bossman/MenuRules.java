/*
 * Bossman Mode: which menu options a locked item loses. No client state, so it is tested in MenuRulesTest.
 */
package com.bossman;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.runelite.client.util.Text;

final class MenuRules
{
	private MenuRules()
	{
	}

	/**
	 * Whether a locked item's menu option should be removed: 'Offer' on the GE (no selling), 'Buy' in shops (exactly
	 * "Buy", "Buy 10", "Buy-X"), and the configured use-actions. Everything else (Examine, Drop, Deposit, ...)
	 * stays unless the player adds it to the blocked actions.
	 */
	static boolean strips(String option, boolean restrictGeSell, boolean restrictShops, boolean blockActions, Set<String> blockedActions)
	{
		if (option == null)
		{
			return false;
		}
		if ("Offer".equals(option))
		{
			return restrictGeSell;
		}
		if (option.equals("Buy") || option.startsWith("Buy ") || option.startsWith("Buy-"))
		{
			return restrictShops;
		}
		return blockActions && blockedActions.contains(option.toLowerCase(Locale.ROOT));
	}

	/** A comma-separated config list, each entry through Text.standardize (blanks dropped). */
	static Set<String> parseList(String csv)
	{
		Set<String> out = new HashSet<>();
		if (csv != null)
		{
			for (String s : csv.split(","))
			{
				String t = Text.standardize(s);
				if (!t.isEmpty())
				{
					out.add(t);
				}
			}
		}
		return out;
	}
}
