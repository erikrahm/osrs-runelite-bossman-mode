package com.bossman;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class MenuRulesTest
{
	private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList("wield", "wear", "eat", "drink"));

	private static boolean strips(String option)
	{
		return MenuRules.strips(option, true, true, true, BLOCKED);
	}

	@Test
	public void sellingAndBuyingAreBlocked()
	{
		assertTrue(strips("Offer"));
		assertTrue(strips("Buy"));
		assertTrue(strips("Buy 1"));
		assertTrue(strips("Buy 50"));
		assertTrue(strips("Buy-X"));
	}

	@Test
	public void configuredActionsAreBlockedWhateverTheirCase()
	{
		assertTrue(strips("Wield"));
		assertTrue(strips("Drink"));
	}

	@Test
	public void everyDepositOptionIsADeposit()
	{
		for (String o : new String[]{"Deposit-1", "Deposit-5", "Deposit-10", "Deposit-X", "Deposit-All", "Deposit-14"})
		{
			assertTrue(o, MenuRules.isDeposit(o));
		}
		assertFalse(MenuRules.isDeposit("Withdraw-1"));
		assertFalse(MenuRules.isDeposit("Deposit inventory"));
		assertTrue(MenuRules.isDepositAll("Deposit inventory"));
		assertTrue(MenuRules.isDepositAll("Deposit worn items"));
		assertFalse(MenuRules.isDepositAll("Deposit-All"));
	}

	@Test
	public void holdingAnItemIsNeverBlockedByDefault()
	{
		assertFalse(strips("Examine"));
		assertFalse(strips("Drop"));
		assertFalse(strips("Deposit-All"));
		assertFalse(strips("Buyer"));   // not a buy option
		assertFalse(strips(null));
	}

	@Test
	public void eachRestrictionHasItsOwnSwitch()
	{
		assertFalse(MenuRules.strips("Offer", false, true, true, BLOCKED));
		assertFalse(MenuRules.strips("Buy 1", true, false, true, BLOCKED));
		assertFalse(MenuRules.strips("Wield", true, true, false, BLOCKED));
	}

	@Test
	public void configListsAreTrimmedLowerCasedAndSkipBlanks()
	{
		assertEquals(new HashSet<>(Arrays.asList("hill giant", "3029")), MenuRules.parseList(" Hill Giant , 3029,"));
		assertTrue(MenuRules.parseList(null).isEmpty());
	}
}
