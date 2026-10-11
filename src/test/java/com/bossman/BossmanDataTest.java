package com.bossman;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.*;

public class BossmanDataTest
{
	@Test
	public void bundledDataLoads()
	{
		BossmanData d = new BossmanData();
		d.load(new Gson());
		assertTrue(d.getFreeItems().size() > 50);
		assertTrue(d.isNpcSource("Brutus"));
		assertTrue(d.isNpcSource("calvar'ion"));
		assertFalse(d.isNpcSource("Hill Giant"));
		assertTrue(d.isEventSource("Barrows"));
		assertFalse(d.isEventSource("Brimstone Chest"));
		assertEquals(67, d.orderedRungs().size());
		assertEquals("Brutus", d.orderedRungs().get(0).boss);
		assertTrue(d.orderedRungs().get(5).isFirstKillOnly()); // The Mimic
		assertEquals(1351, (int) d.getTools().groups.get("general").get(0).id); // bronze axe
	}
}
