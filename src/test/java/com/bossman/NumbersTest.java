package com.bossman;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class NumbersTest
{
	@Test
	public void numbersOfFourOrMoreDigitsGetCommas()
	{
		assertEquals("999", Numbers.format(999));
		assertEquals("1,054", Numbers.format(1054));
		assertEquals("10,000", Numbers.format(10000));
		assertEquals("1,210,421", Numbers.format(1210421));
	}

	@Test
	public void ratesAndNotesInTextGetCommas()
	{
		assertEquals("Drop Rate: 1/5,000 - expected by KC 5,000", Numbers.withCommas("Drop Rate: 1/5000 - expected by KC 5000"));
		assertEquals("1/128", Numbers.withCommas("1/128"));
		assertEquals("4/180", Numbers.withCommas("4/180"));
		assertEquals("1/10,000", Numbers.withCommas("1/10,000"));
		assertEquals("Always", Numbers.withCommas("Always"));
		assertEquals("1/1,345.5", Numbers.withCommas("1/1345.5"));
	}
}
