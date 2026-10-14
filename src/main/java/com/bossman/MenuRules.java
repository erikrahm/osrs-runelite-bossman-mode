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

	/** An item's deposit option at a bank or deposit box, the remembered Deposit-X amount ("Deposit-14") included. */
	static boolean isDeposit(String option)
	{
		return option != null && option.startsWith("Deposit-");
	}

	/** A bank or deposit box button that puts away everything carried or worn. */
	static boolean isDepositAll(String option)
	{
		return "Deposit inventory".equals(option) || "Deposit worn items".equals(option);
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
