package com.bossman;

import org.junit.Test;
import static org.junit.Assert.*;

public class CombatTasksTest
{
	@Test
	public void taskNIsBitNMod32OfWordNDiv32()
	{
		int[] bits = new int[21];
		bits[0] = 1;                 // task 0
		bits[0] |= 1 << 31;          // task 31
		bits[1] = 1;                 // task 32
		bits[20] = 1 << 31;          // task 671, the last one 21 words can hold
		assertTrue(CombatTasks.isComplete(bits, 0));
		assertTrue(CombatTasks.isComplete(bits, 31));
		assertTrue(CombatTasks.isComplete(bits, 32));
		assertTrue(CombatTasks.isComplete(bits, 671));
		assertFalse(CombatTasks.isComplete(bits, 1));
		assertFalse(CombatTasks.isComplete(bits, 33));
	}

	@Test
	public void idsOutsideTheWordsAreNotDone()
	{
		int[] bits = new int[21];
		java.util.Arrays.fill(bits, -1);
		assertFalse(CombatTasks.isComplete(bits, 672));
		assertFalse(CombatTasks.isComplete(bits, -1));
	}
}
