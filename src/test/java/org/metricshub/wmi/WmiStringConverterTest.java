package org.metricshub.wmi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

	@Test
	void testConvertDateTimeAndInterval() {
		final OffsetDateTime dateTime = OffsetDateTime.of(1975, 3, 24, 19, 30, 00, 0, ZoneOffset.ofHours(1));
		final Duration interval = Duration.ofDays(1485).plusHours(5).plusMinutes(1).plusSeconds(6);

		final WmiStringConverter epochConverter = new WmiStringConverter();
		assertEquals("164917800", epochConverter.convert(dateTime));
		assertEquals("128322066", epochConverter.convert(interval));
		assertEquals("128322066|0", epochConverter.convert(new Object[] { interval, Duration.ZERO }));

		final WmiStringConverter textConverter = new WmiStringConverter("|", false);
		assertEquals("Mon Mar 24 19:30:00 1975", textConverter.convert(dateTime));
		assertEquals("128322066", textConverter.convert(interval));
	}
}
