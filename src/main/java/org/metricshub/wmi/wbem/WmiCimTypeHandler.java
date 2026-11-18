package org.metricshub.wmi.wbem;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WMI Java Client
 * ჻჻჻჻჻჻
 * Copyright (C) 2023 - 2025 MetricsHub
 * ჻჻჻჻჻჻
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱
 */

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.COM.IUnknown;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.COM.Wbemcli;
import com.sun.jna.platform.win32.COM.Wbemcli.IWbemClassObject;
import com.sun.jna.platform.win32.Guid.REFIID;
import com.sun.jna.platform.win32.OaIdl.SAFEARRAY;
import com.sun.jna.platform.win32.Variant;
import com.sun.jna.platform.win32.Variant.VARIANT;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.metricshub.wmi.Utils;

public class WmiCimTypeHandler {

	/**
	 * Private constructor, as this class cannot be instantiated (it's pure static)
	 */
	private WmiCimTypeHandler() {}

	/**
	 * Map of functions that convert WBEM types to Java corresponding class/type
	 */
	private static final Map<Integer, Function<VARIANT, Object>> CIMTYPE_TO_CONVERTER_MAP;

	static {
		final Map<Integer, Function<VARIANT, Object>> map = new HashMap<>();

		map.put(Wbemcli.CIM_EMPTY, value -> null);

		map.put(Wbemcli.CIM_BOOLEAN, VARIANT::booleanValue);

		map.put(Wbemcli.CIM_UINT8, VARIANT::byteValue);
		map.put(Wbemcli.CIM_UINT16, VARIANT::intValue);
		map.put(Wbemcli.CIM_UINT32, WmiCimTypeHandler::convertCimUint32);
		map.put(Wbemcli.CIM_UINT64, VARIANT::stringValue);
		map.put(Wbemcli.CIM_SINT8, VARIANT::shortValue);
		map.put(Wbemcli.CIM_SINT16, VARIANT::shortValue);
		map.put(Wbemcli.CIM_SINT32, VARIANT::intValue);
		map.put(Wbemcli.CIM_SINT64, VARIANT::stringValue);

		map.put(Wbemcli.CIM_REAL32, VARIANT::floatValue);
		map.put(Wbemcli.CIM_REAL64, VARIANT::doubleValue);

		map.put(Wbemcli.CIM_CHAR16, VARIANT::shortValue);
		map.put(Wbemcli.CIM_STRING, VARIANT::stringValue);

		map.put(Wbemcli.CIM_REFERENCE, WmiCimTypeHandler::convertCimReference);
		map.put(Wbemcli.CIM_DATETIME, WmiCimTypeHandler::convertCimDateTime);

		CIMTYPE_TO_CONVERTER_MAP = Collections.unmodifiableMap(map);
	}

	private static final String CIM_OBJECT_LABEL = "CIM_OBJECT";

	/**
	 * IWbemClassObject::Get is the 5th method of the IWbemClassObject vtable
	 */
	private static final int WBEM_CLASS_OBJECT_GET_VTABLE_ID = 4;

	/**
	 * Size of a native VARIANT (16 bytes on 32-bit, 24 bytes on 64-bit)
	 */
	private static final long VARIANT_SIZE = new VARIANT().size();

	/**
	 * Offset of the value in a native VARIANT (after vt and 3 reserved WORDs)
	 */
	private static final long VARIANT_VALUE_OFFSET = 8;

	/**
	 * VariantClear() on a native VARIANT buffer (JNA's OleAuto only accepts its VARIANT structure)
	 */
	private static final com.sun.jna.Function VARIANT_CLEAR = com.sun.jna.Function.getFunction(
		"OleAut32",
		"VariantClear",
		com.sun.jna.Function.ALT_CONVENTION
	);

	/**
	 * Returned by {@link #convertScalar} when the value is not a scalar it can convert
	 */
	static final Object NOT_SCALAR = new Object();

	/**
	 * Convert a VARIANT value holding a CIM_UINT32 (i.e. an unsigned int on 32 bits).
	 * <p>
	 * Unfortunately, there seems to be a bug in either JNA or Wbemcli library which stores
	 * CIM_UINT32 values as VT_I4 (signed integer). As a result, it's converted to a signed
	 * integer, which is wrong.
	 * <p>
	 * Good news, since we know the CIM type, we can enforce reconverting to unsigned!
	 * <p>
	 * @param cimUint32Value a CIM_UINT32 value... potentially (and wrongly) stored as a VT_I4
	 * @return always a positive long
	 */
	private static long convertCimUint32(final VARIANT cimUint32Value) {
		return Integer.toUnsignedLong(cimUint32Value.intValue());
	}

	/**
	 * Convert a VARIANT value holding a CIM_DATETIME (i.e. a string in the form
	 * of <code>yyyymmddHHMMSS.mmmmmmsUUU</code>, or <code>ddddddddHHMMSS.mmmmmm:000</code>
	 * for an interval) to an OffsetDateTime or a Duration object
	 * @param value VARIANT value with a CIM_DATETIME string
	 * @return OffsetDateTime instance, or Duration instance for an interval
	 */
	static Object convertCimDateTime(final VARIANT value) {
		return Utils.convertCimDateTimeOrInterval(value.stringValue());
	}

	/**
	 * Convert a CIM_REFERENCE into a String
	 * @param value CIM_REFERENCE
	 * @return a proper String
	 */
	static String convertCimReference(final VARIANT value) {
		return WmiCimTypeHandler.convertCimReference(value.stringValue());
	}

	/**
	 * Convert a CIM_REFERENCE into a String
	 * @param reference CIM_REFERENCE
	 * @return a proper String
	 */
	static String convertCimReference(final String reference) {
		if (reference == null) {
			return null;
		}

		// Remove the "\\hostname\namespace:" prefix (i.e. anything before the first colon)
		final int colonIndex = reference.indexOf(':');
		return colonIndex > -1 ? reference.substring(colonIndex + 1) : reference;
	}

	/**
	 * Convert the WBEM SAFEARRAY value type into an array.
	 *
	 * @param array Reference to SAFEARRAY
	 * @param property The Property to retrieve. An Entry with the property
	 * name as the key and a set of sub properties to retrieve if exists.
	 * @return A Map with the property value converted as a Java object, or null if property cannot be retrieved.
	 * The key is the property name as defined in the select request.
	 * (example: DriveInfo.Name)
	 */
	static Map<String, Object> convertSafeArray(
		final VARIANT array,
		final int cimType,
		final Entry<String, Set<String>> property
	) {
		// Get the SAFEARRAY
		final SAFEARRAY safeArray = (SAFEARRAY) array.getValue();
		if (safeArray == null) {
			return Collections.singletonMap(property.getKey(), null);
		}

		safeArray.lock();

		// Get the properties of the array
		final int lowerBound = safeArray.getLBound(0);
		final int length = safeArray.getUBound(0) - lowerBound + 1;

		// Convert to a Java array
		final Object[] resultArray = new Object[length];
		for (int i = 0; i < length; i++) {
			resultArray[i] = safeArray.getElement(lowerBound + i);
		}

		safeArray.unlock();

		// Simplified conversion of the values, since SAFEARRAY.getElement()
		// did most of the job already, except for CIM_REFERENCE and CIM_DATETIME
		if (cimType == Wbemcli.CIM_REFERENCE) {
			return Collections.singletonMap(
				property.getKey(),
				Stream.of(resultArray).map(String.class::cast).map(WmiCimTypeHandler::convertCimReference).toArray()
			);
		}
		if (cimType == Wbemcli.CIM_DATETIME) {
			return Collections.singletonMap(
				property.getKey(),
				Stream.of(resultArray).map(String.class::cast).map(Utils::convertCimDateTimeOrInterval).toArray()
			);
		}
		if (cimType == Wbemcli.CIM_OBJECT) {
			if (property.getValue().isEmpty()) {
				return Collections.singletonMap(property.getKey(), new String[] { CIM_OBJECT_LABEL });
			}

			final Map<String, List<Object>> resulMap = new HashMap<>();

			for (final Object resultValue : resultArray) {
				final Optional<IWbemClassObject> maybeClassObject = getUnknownWbemClassObject(resultValue);
				if (!maybeClassObject.isPresent()) {
					continue;
				}

				final Map<String, String> subPropertiesNames = getSubPropertiesNamesFromClass(maybeClassObject.get());

				try {
					property
						.getValue()
						.stream()
						.map(subProperty -> subPropertiesNames.get(subProperty.toLowerCase()))
						.forEach(subProperty ->
							resulMap
								.computeIfAbsent(buildCimObjectSubPropertyName(property, subProperty), key -> new ArrayList<>())
								.add(
									getPropertyValue(
										maybeClassObject.get(),
										new AbstractMap.SimpleEntry<String, Set<String>>(subProperty, Collections.emptySet())
									)
										.get(subProperty)
								)
						);
				} finally {
					maybeClassObject.get().Release();
				}
			}

			return resulMap.entrySet().stream().collect(Collectors.toMap(Entry::getKey, entry -> entry.getValue().toArray()));
		}

		// Default: return the array straight away
		return Collections.singletonMap(property.getKey(), resultArray);
	}

	/**
	 * Convert the wanted values in the CIM Object structure into a Map.
	 *
	 * @param value The value of the property.
	 * @param property The CIM Object Properties to retrieve.
	 * An Entry with the CIM Object Class name as the key and a set of sub properties.
	 * @return A Map with the properties from the CIM Object and their Values,
	 * converted as a Java object, or null if property cannot be retrieved.
	 * The key is the property as defined in the select request.
	 * (example: DriveInfo.Name)
	 */
	static Map<String, Object> convertCimObject(final VARIANT value, final Entry<String, Set<String>> property) {
		final Optional<IWbemClassObject> maybeClassObject = getUnknownWbemClassObject(value.getValue());
		if (!maybeClassObject.isPresent()) {
			return property
				.getValue()
				.stream()
				.collect(
					HashMap::new,
					(map, subProperty) -> map.put(buildCimObjectSubPropertyName(property, subProperty), null),
					HashMap::putAll
				);
		}

		try {
			final Map<String, String> subPropertiesNames = getSubPropertiesNamesFromClass(maybeClassObject.get());

			return property
				.getValue()
				.stream()
				.map(subProperty -> subPropertiesNames.get(subProperty.toLowerCase()))
				.collect(
					HashMap::new,
					(map, subProperty) ->
						map.put(
							buildCimObjectSubPropertyName(property, subProperty),
							getPropertyValue(
								maybeClassObject.get(),
								new AbstractMap.SimpleEntry<String, Set<String>>(subProperty, Collections.emptySet())
							)
								.get(subProperty)
						),
					HashMap::putAll
				);
		} finally {
			maybeClassObject.get().Release();
		}
	}

	/**
	 * Get a Map of all subProperties names from the class
	 * @param wbemClassObject
	 * @return
	 */
	static Map<String, String> getSubPropertiesNamesFromClass(final IWbemClassObject wbemClassObject) {
		String[] names;
		try {
			names = wbemClassObject.GetNames(null, 0, null);
		} catch (final Throwable e) {
			names = wbemClassObject.GetNames(null, 0, null);
		}

		return Stream.of(names).collect(Collectors.toMap(String::toLowerCase, Function.identity()));
	}

	/**
	 * Build the CIM Object sub property name as it was in the WQL query.
	 * For example: "CimObjectClass.subProperty"
	 *
	 * @param property The Property to retrieve. An Entry with the property name as the key and a set of sub properties to retrieve if exists.
	 * @param subProperty The Sub property name in the CIM Object structure.
	 * @return
	 */
	static String buildCimObjectSubPropertyName(final Entry<String, Set<String>> property, final String subProperty) {
		return new StringBuilder().append(property.getKey()).append(".").append(subProperty).toString();
	}

	/**
	 * Get the WbemClassObject representing the CIM Object class from the Unknown Object request value.
	 *
	 * @param value The value of the property.
	 * @return An optional with the WbemClassObject representing the CIM Object class. empty optional otherwise.
	 */
	static Optional<IWbemClassObject> getUnknownWbemClassObject(final Object value) {
		if (value == null) {
			return Optional.empty();
		}

		final Unknown unknown = (Unknown) value;
		try {
			final PointerByReference pointerByReference = new PointerByReference();

			final HRESULT hResult = unknown.QueryInterface(new REFIID(IUnknown.IID_IUNKNOWN), pointerByReference);
			return COMUtils.FAILED(hResult)
				? Optional.empty()
				: Optional.of(new IWbemClassObject(pointerByReference.getValue()));
		} finally {
			unknown.Release();
		}
	}

	/**
	 * Convert the specified CIM value into a Java Object depending on its CIM type.
	 * @param value CIM value (VARIANT)
	 * @param cimType CIM Type
	 * @param property The Property to retrieve. An Entry with the property name
	 * as the key and a set of sub properties to retrieve if exists.
	 * @return A Map with the property value converted as a Java object,
	 * or null if property cannot be retrieved.
	 * The key is the property name as defined in the select request.
	 * (example: DriveInfo.Name)
	 */
	static Map<String, Object> convert(
		final VARIANT value,
		final int cimType,
		final Entry<String, Set<String>> property
	) {
		if (value.getValue() == null) {
			return Collections.singletonMap(property.getKey(), null);
		}

		// Array?
		if ((cimType & Wbemcli.CIM_FLAG_ARRAY) > 0) {
			return convertSafeArray(value, cimType ^ Wbemcli.CIM_FLAG_ARRAY, property);
		}

		if (cimType == Wbemcli.CIM_OBJECT) {
			return property.getValue().isEmpty()
				? Collections.singletonMap(property.getKey(), CIM_OBJECT_LABEL)
				: convertCimObject(value, property);
		}

		return Collections.singletonMap(
			property.getKey(),
			CIMTYPE_TO_CONVERTER_MAP.getOrDefault(cimType, v -> "Unsupported type").apply(value)
		);
	}

	/**
	 * Get the value of the specified property from the specified WbemClassObject
	 * @see <a href="https://docs.microsoft.com/en-us/windows/win32/api/wbemcli/nf-wbemcli-iwbemclassobject-get">IWbemClassObject::Get method (wbemcli.h)</a>
	 * @param wbemClassObject WbemClassObject
	 * @param property The Property to retrieve. An Entry with the property name as
	 * the key and a set of sub properties to retrieve if exists.
	 * @return A Map with the property value converted as a Java object,
	 * or null if property cannot be retrieved.
	 * The key is the property name as defined in the select request.
	 * (example: DriveInfo.Name)
	 */
	public static Map<String, Object> getPropertyValue(
		final IWbemClassObject wbemClassObject,
		final Entry<String, Set<String>> property
	) {
		try {
			return getPropertyValueFromWbemObject(wbemClassObject, property);
		} catch (final Throwable e) {
			// Retry
			return getPropertyValueFromWbemObject(wbemClassObject, property);
		}
	}

	/**
	 * Reads the property into a native VARIANT buffer that is freed as soon as the value is converted.
	 * <p>
	 * A JNA {@link VARIANT} structure allocates a native memory block for each pointer field of its union,
	 * every time it is created or read (about 50 blocks per value), and these blocks are only freed by the
	 * garbage collector. Scalar values are therefore read straight from the buffer, and the JNA structure
	 * is only built for arrays, objects and unexpected types.
	 */
	private static Map<String, Object> getPropertyValueFromWbemObject(
		final IWbemClassObject wbemClassObject,
		final Entry<String, Set<String>> property
	) {
		// The name is null for a (sub-)property that the class does not define: Get() then fails, and the value is null
		final String name = property.getKey();
		try (
			Memory variant = new Memory(VARIANT_SIZE);
			Memory pType = new Memory(4);
			Memory wszName = name == null ? null : new Memory((name.length() + 1L) * Native.WCHAR_SIZE)
		) {
			// Initialize value (VT_EMPTY), to make sure *VariantClear()* won't throw an exception
			// See https://docs.microsoft.com/en-us/windows/win32/api/wbemcli/nf-wbemcli-iwbemclassobject-get
			variant.clear();
			if (wszName != null) {
				wszName.setWideString(0, name);
			}

			try {
				final HRESULT hResult = (HRESULT) WmiComHelper.comInvokerInvokeNativeObject(
					wbemClassObject.getPointer(),
					WBEM_CLASS_OBJECT_GET_VTABLE_ID,
					new Object[] { wbemClassObject.getPointer(), wszName, 0, variant, pType, null },
					HRESULT.class
				);
				if (COMUtils.FAILED(hResult)) {
					return Collections.singletonMap(name, null);
				}

				// Special case for __PATH
				final boolean isPath = "__PATH".equalsIgnoreCase(name);
				final int cimType = isPath ? Wbemcli.CIM_REFERENCE : pType.getInt(0);

				final Object value = convertScalar(variant, cimType);
				if (value != NOT_SCALAR) {
					return Collections.singletonMap(name, value);
				}

				final VARIANT jnaVariant = new VARIANT(variant);
				return isPath
					? Collections.singletonMap(name, convertCimReference(jnaVariant))
					: convert(jnaVariant, cimType, property);
			} finally {
				try {
					VARIANT_CLEAR.invokeInt(new Object[] { variant });
				} catch (final Throwable t) {
					/* Do nothing -- This condition rarely happens, but it does, and there's nothing we can do about it */
				}
			}
		}
	}

	/**
	 * Converts the scalar value of a VARIANT, exactly as {@link #convert} does it through the JNA structure.
	 *
	 * @param variant Native VARIANT
	 * @param cimType CIM type of the value
	 * @return the converted value, or {@link #NOT_SCALAR} when the value must go through {@link #convert}
	 */
	static Object convertScalar(final Pointer variant, final int cimType) {
		final Object value;
		switch (variant.getShort(0)) {
			case Variant.VT_EMPTY:
			case Variant.VT_NULL:
				return null;
			case Variant.VT_BSTR:
				final Pointer bstr = variant.getPointer(VARIANT_VALUE_OFFSET);
				if (bstr == null) {
					return null;
				}
				value = new String(bstr.getByteArray(0, bstr.getInt(-4)), StandardCharsets.UTF_16LE);
				break;
			case Variant.VT_BOOL:
				return cimType == Wbemcli.CIM_BOOLEAN ? variant.getShort(VARIANT_VALUE_OFFSET) != 0 : NOT_SCALAR;
			case Variant.VT_UI1:
				// JNA's BYTE gives the unsigned value as an int, but the signed one as a float or a double
				if (cimType == Wbemcli.CIM_REAL32 || cimType == Wbemcli.CIM_REAL64) {
					return NOT_SCALAR;
				}
				value = variant.getByte(VARIANT_VALUE_OFFSET) & 0xFF;
				break;
			case Variant.VT_I2:
				value = variant.getShort(VARIANT_VALUE_OFFSET);
				break;
			case Variant.VT_I4:
				value = variant.getInt(VARIANT_VALUE_OFFSET);
				break;
			case Variant.VT_R4:
				value = variant.getFloat(VARIANT_VALUE_OFFSET);
				break;
			case Variant.VT_R8:
				value = variant.getDouble(VARIANT_VALUE_OFFSET);
				break;
			default:
				return NOT_SCALAR;
		}

		if (value instanceof String) {
			switch (cimType) {
				case Wbemcli.CIM_STRING:
				case Wbemcli.CIM_UINT64:
				case Wbemcli.CIM_SINT64:
					return value;
				case Wbemcli.CIM_REFERENCE:
					return convertCimReference((String) value);
				case Wbemcli.CIM_DATETIME:
					return Utils.convertCimDateTimeOrInterval((String) value);
				default:
					return NOT_SCALAR;
			}
		}

		final Number number = (Number) value;
		switch (cimType) {
			case Wbemcli.CIM_UINT8:
				return number.byteValue();
			case Wbemcli.CIM_UINT16:
			case Wbemcli.CIM_SINT32:
				return number.intValue();
			case Wbemcli.CIM_UINT32:
				return Integer.toUnsignedLong(number.intValue());
			case Wbemcli.CIM_SINT8:
			case Wbemcli.CIM_SINT16:
			case Wbemcli.CIM_CHAR16:
				return number.shortValue();
			case Wbemcli.CIM_REAL32:
				return number.floatValue();
			case Wbemcli.CIM_REAL64:
				return number.doubleValue();
			default:
				return NOT_SCALAR;
		}
	}
}
