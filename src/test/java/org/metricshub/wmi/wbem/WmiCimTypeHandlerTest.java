package org.metricshub.wmi.wbem;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

import com.sun.jna.platform.win32.COM.Wbemcli;
import com.sun.jna.platform.win32.COM.Wbemcli.IWbemClassObject;
import com.sun.jna.platform.win32.OleAuto;
import com.sun.jna.platform.win32.Variant;
import com.sun.jna.platform.win32.Variant.VARIANT;
import com.sun.jna.platform.win32.Variant.VARIANT.ByReference;
import com.sun.jna.platform.win32.WTypes.BSTR;
import com.sun.jna.platform.win32.WinDef.BYTE;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.metricshub.wmi.Utils;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@EnabledOnOs(OS.WINDOWS)
class WmiCimTypeHandlerTest {

	@Test
	void testConvertCimDateTime() {
		assertNull(WmiCimTypeHandler.convertCimDateTime(new ByReference()));

		ByReference invalid = new ByReference(new VARIANT("invalid"));
		assertThrows(IllegalArgumentException.class, () -> WmiCimTypeHandler.convertCimDateTime(invalid));

		OffsetDateTime t1 = OffsetDateTime.of(1975, 3, 24, 19, 30, 00, 0, ZoneOffset.ofHours(1));
		String t1CimString = t1.format(Utils.WBEM_CIM_DATETIME_FORMATTER) + ".000000+060";
		ByReference cimDateTime1 = new ByReference(new VARIANT(t1CimString));
		OffsetDateTime convertedT1 = (OffsetDateTime) WmiCimTypeHandler.convertCimDateTime(cimDateTime1);
		assertEquals(t1, convertedT1);
		assertEquals(164917800, convertedT1.toEpochSecond());

		String t2CimString = t1.format(Utils.WBEM_CIM_DATETIME_FORMATTER) + ".000000+059";
		ByReference cimDateTime2 = new ByReference(new VARIANT(t2CimString));
		OffsetDateTime t2 = (OffsetDateTime) WmiCimTypeHandler.convertCimDateTime(cimDateTime2);
		assertEquals(60, t2.toEpochSecond() - t1.toEpochSecond());

		ByReference cimInterval = new ByReference(new VARIANT("00001485050106.000000:000"));
		assertEquals(
			Duration.ofDays(1485).plusHours(5).plusMinutes(1).plusSeconds(6),
			WmiCimTypeHandler.convertCimDateTime(cimInterval)
		);
	}

	@Test
	void testConvertCimReference() {
		assertNull(WmiCimTypeHandler.convertCimReference(new ByReference()));

		{
			ByReference ref = new ByReference(new VARIANT("test"));
			assertEquals("test", WmiCimTypeHandler.convertCimReference(ref));
		}

		{
			ByReference ref = new ByReference(new VARIANT("\\\\hostname\\root\\cimv2:test:colon"));
			assertEquals("test:colon", WmiCimTypeHandler.convertCimReference(ref));
		}
	}

	@Test
	void testConvert() {
		assertEquals(
			Collections.singletonMap("property", null),
			WmiCimTypeHandler.convert(
				new ByReference(),
				1,
				new AbstractMap.SimpleEntry<String, Set<String>>("property", Collections.emptySet())
			)
		);

		ByReference r = new ByReference(new VARIANT(5));
		assertEquals(
			Collections.singletonMap("property", 5),
			WmiCimTypeHandler.convert(
				r,
				Wbemcli.CIM_SINT32,
				new AbstractMap.SimpleEntry<String, Set<String>>("property", Collections.emptySet())
			)
		);
	}

	@Test
	void testConvertCIM_UINT32() {
		// Make sure CIM_UINT32 are returned as long
		{
			long testLong = 1L << (31 + 12345); // a value between 2^31 and 2^32 - 1
			ByReference r = new ByReference(new VARIANT(testLong));
			assertEquals(
				Collections.singletonMap("uint32Property", testLong),
				WmiCimTypeHandler.convert(
					r,
					Wbemcli.CIM_UINT32,
					new AbstractMap.SimpleEntry<String, Set<String>>("uint32Property", Collections.emptySet())
				)
			);
		}

		// Test the special case where CIM_UINT32 is actually stored as... a signed integer!
		// A positive long must be returned
		{
			// -1 == 2^32 - 1
			ByReference r = new ByReference(new VARIANT(-1));
			assertEquals(
				Collections.singletonMap("uint32Property", (1L << 32) - 1),
				WmiCimTypeHandler.convert(
					r,
					Wbemcli.CIM_UINT32,
					new AbstractMap.SimpleEntry<String, Set<String>>("uint32Property", Collections.emptySet())
				)
			);
		}
	}

	@Test
	void testConvertCimObject() {
		final ByReference value = Mockito.mock(ByReference.class);

		final AbstractMap.SimpleEntry<String, Set<String>> property = new AbstractMap.SimpleEntry<String, Set<String>>(
			"DriveInfo",
			Arrays.asList("name", "numberpaths", "serialnumber").stream().collect(Collectors.toSet())
		);

		final AbstractMap.SimpleEntry<String, Set<String>> nameSubProperty = new AbstractMap.SimpleEntry<
			String,
			Set<String>
		>("Name", Collections.emptySet());

		final AbstractMap.SimpleEntry<String, Set<String>> numberPathsSubProperty = new AbstractMap.SimpleEntry<
			String,
			Set<String>
		>("NumberPaths", Collections.emptySet());

		final AbstractMap.SimpleEntry<String, Set<String>> serialNumberSubProperty = new AbstractMap.SimpleEntry<
			String,
			Set<String>
		>("SerialNumber", Collections.emptySet());

		final Map<String, String> subPropertiesNames = new HashMap<>();
		subPropertiesNames.put("name", "Name");
		subPropertiesNames.put("numberpaths", "NumberPaths");
		subPropertiesNames.put("serialnumber", "SerialNumber");

		final IWbemClassObject wbemClassObject = Mockito.mock(IWbemClassObject.class);

		// Case getUnknownWbemClassObject empty
		try (final MockedStatic<WmiCimTypeHandler> mockedWmiCimTypeHandler = Mockito.mockStatic(WmiCimTypeHandler.class)) {
			mockedWmiCimTypeHandler
				.when(() -> WmiCimTypeHandler.getUnknownWbemClassObject(any()))
				.thenReturn(Optional.empty());
			mockedWmiCimTypeHandler
				.when(() -> WmiCimTypeHandler.buildCimObjectSubPropertyName(eq(property), anyString()))
				.thenCallRealMethod();
			mockedWmiCimTypeHandler.when(() -> WmiCimTypeHandler.convertCimObject(value, property)).thenCallRealMethod();

			final Map<String, Object> expected = new HashMap<>();
			expected.put("DriveInfo.name", null);
			expected.put("DriveInfo.numberpaths", null);
			expected.put("DriveInfo.serialnumber", null);

			assertEquals(expected, WmiCimTypeHandler.convertCimObject(value, property));
		}

		try (final MockedStatic<WmiCimTypeHandler> mockedWmiCimTypeHandler = Mockito.mockStatic(WmiCimTypeHandler.class)) {
			mockedWmiCimTypeHandler
				.when(() -> WmiCimTypeHandler.getUnknownWbemClassObject(any()))
				.thenReturn(Optional.of(wbemClassObject));

			mockedWmiCimTypeHandler
				.when(() -> WmiCimTypeHandler.getSubPropertiesNamesFromClass(wbemClassObject))
				.thenReturn(subPropertiesNames);

			mockedWmiCimTypeHandler
				.when(() -> WmiCimTypeHandler.getPropertyValue(wbemClassObject, numberPathsSubProperty))
				.thenReturn(Collections.singletonMap("NumberPaths", 5));

			mockedWmiCimTypeHandler
				.when(() -> WmiCimTypeHandler.getPropertyValue(wbemClassObject, nameSubProperty))
				.thenReturn(Collections.singletonMap("Name", "MPIO Disk0"));

			mockedWmiCimTypeHandler
				.when(() -> WmiCimTypeHandler.getPropertyValue(wbemClassObject, serialNumberSubProperty))
				.thenReturn(Collections.singletonMap("SerialNumber", "624A937068B04CE438B46F8A00011BBB"));

			mockedWmiCimTypeHandler
				.when(() -> WmiCimTypeHandler.buildCimObjectSubPropertyName(eq(property), anyString()))
				.thenCallRealMethod();
			mockedWmiCimTypeHandler.when(() -> WmiCimTypeHandler.convertCimObject(value, property)).thenCallRealMethod();

			final Map<String, Object> expected = new HashMap<>();
			expected.put("DriveInfo.Name", "MPIO Disk0");
			expected.put("DriveInfo.NumberPaths", 5);
			expected.put("DriveInfo.SerialNumber", "624A937068B04CE438B46F8A00011BBB");

			assertEquals(expected, WmiCimTypeHandler.convertCimObject(value, property));
		}
	}

	@Test
	void testConvertScalarReturnsWhatConvertReturns() {
		final VARIANT vtNull = new VARIANT();
		vtNull.setVarType((short) Variant.VT_NULL);
		final List<VARIANT> values = Arrays.asList(
			new VARIANT(),
			vtNull,
			new VARIANT(new BSTR()),
			new VARIANT(""),
			new VARIANT("abc"),
			new VARIANT("\\\\HOST\\root\\cimv2:Win32_Process.Handle=\"4\""),
			new VARIANT("20261118123456.123456+060"),
			new VARIANT("00001485050106.000000:000"),
			new VARIANT("18446744073709551615"),
			new VARIANT(true),
			new VARIANT(false),
			new VARIANT(new BYTE(200)),
			new VARIANT(new BYTE(5)),
			new VARIANT((short) -5),
			new VARIANT(Short.MAX_VALUE),
			new VARIANT(-1),
			new VARIANT(123),
			new VARIANT(1.5f),
			new VARIANT(-2.25d),
			new VARIANT((byte) -3),
			new VARIANT('x'),
			new VARIANT(1L << 40)
		);
		final int[] cimTypes = {
			Wbemcli.CIM_EMPTY,
			Wbemcli.CIM_SINT8,
			Wbemcli.CIM_UINT8,
			Wbemcli.CIM_SINT16,
			Wbemcli.CIM_UINT16,
			Wbemcli.CIM_SINT32,
			Wbemcli.CIM_UINT32,
			Wbemcli.CIM_SINT64,
			Wbemcli.CIM_UINT64,
			Wbemcli.CIM_REAL32,
			Wbemcli.CIM_REAL64,
			Wbemcli.CIM_BOOLEAN,
			Wbemcli.CIM_STRING,
			Wbemcli.CIM_DATETIME,
			Wbemcli.CIM_REFERENCE,
			Wbemcli.CIM_CHAR16,
			Wbemcli.CIM_OBJECT,
			Wbemcli.CIM_FLAG_ARRAY | Wbemcli.CIM_SINT32,
			999
		};
		final Map.Entry<String, Set<String>> property = new AbstractMap.SimpleEntry<>("p", Collections.emptySet());

		int converted = 0;
		try {
			for (final VARIANT value : values) {
				value.write();
				for (final int cimType : cimTypes) {
					final String context = "vt=" + value.getVarType().intValue() + " cimType=" + cimType;
					final Object actual = outcome(() -> WmiCimTypeHandler.convertScalar(value.getPointer(), cimType));
					if (actual == WmiCimTypeHandler.NOT_SCALAR) {
						continue;
					}
					final Object expected = outcome(() -> WmiCimTypeHandler.convert(value, cimType, property).get("p"));
					if (expected instanceof Throwable || actual instanceof Throwable) {
						// Both paths must fail the same way (the caller retries, then lets the exception go)
						assertEquals(String.valueOf(expected), String.valueOf(actual), context);
						continue;
					}
					assertEquals(expected, actual, context);
					assertEquals(
						expected == null ? null : expected.getClass(),
						actual == null ? null : actual.getClass(),
						context
					);
					converted++;
				}
			}
		} finally {
			values.forEach(OleAuto.INSTANCE::VariantClear);
		}

		assertTrue(converted > 70, converted + " values converted without the JNA structure");
		final VARIANT minusOne = new VARIANT(-1);
		minusOne.write();
		assertEquals(4294967295L, WmiCimTypeHandler.convertScalar(minusOne.getPointer(), Wbemcli.CIM_UINT32));
	}

	/**
	 * @return the value returned by the supplier, or the exception it threw
	 */
	private static Object outcome(final Supplier<Object> supplier) {
		try {
			return supplier.get();
		} catch (final Throwable t) {
			return t;
		}
	}
}
