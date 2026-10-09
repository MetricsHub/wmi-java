package org.metricshub.wmi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WmiStringConverterTest {

	@Test
	void testConvertArray() {
		final WmiStringConverter converter = new WmiStringConverter();
		assertEquals("192.0.2.10|fe80::1", converter.convert(new String[] { "192.0.2.10", "fe80::1" }));
		assertEquals("fr-FR", converter.convert(new String[] { "fr-FR" }));
		assertEquals("", converter.convert(new String[0]));
		assertEquals("", converter.convert(null));
		assertEquals("ab|c", converter.convert(new String[] { "a|b", "c" }));
		assertEquals("2|True", converter.convert(new Object[] { 2, Boolean.TRUE }));
		assertEquals("a, b", new WmiStringConverter(", ", true).convert(new String[] { "a", "b" }));
	}
}
