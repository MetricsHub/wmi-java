package org.sentrysoftware.wmi.windows.remote;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WMI Java Client
 * ჻჻჻჻჻჻
 * Copyright (C) 2023 - 2024 Sentry Software
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

import org.sentrysoftware.wmi.Utils;
import org.sentrysoftware.wmi.exceptions.WindowsRemoteException;
import org.sentrysoftware.wmi.exceptions.WqlQuerySyntaxException;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WindowsRemoteProcessUtils {

	private WindowsRemoteProcessUtils() { }

	private static final String DEFAULT_CODESET = "1252";
	private static final Charset DEFAULT_CHARSET = Charset.forName("windows-1252");

	/**
	 * Windows CodeSet to java.nio.charset Charset Code map.
	 *
	 * @see <a href="https://en.wikipedia.org/wiki/Windows_code_page">Windows code page</a>
	 * @see <a href="https://docs.oracle.com/javase/8/docs/technotes/guides/intl/encoding.doc.html">
	 * Supported Encodings</a>
	 *
	 */
	private static final Map<String, Charset> CODESET_MAP;
	static {
		final Map<String, Charset> map = new HashMap<>();
		addToCharsetMap(map, "1250", "windows-1250");
		addToCharsetMap(map, "1251", "windows-1251");
		map.put("1252", DEFAULT_CHARSET);
		addToCharsetMap(map, "1253", "windows-1253");
		addToCharsetMap(map, "1254", "windows-1254");
		addToCharsetMap(map, "1255", "windows-1255");
		addToCharsetMap(map, "1256", "windows-1256");
		addToCharsetMap(map, "1257", "windows-1257");
		addToCharsetMap(map, "1258", "windows-1258");
		addToCharsetMap(map, "874", "x-windows-874");
		addToCharsetMap(map, "932", "Shift_JIS");
		addToCharsetMap(map, "936", "GBK");
		if (!addToCharsetMap(map, "949", "EUC-KR")) {
			addToCharsetMap(map, "949", "x-windows-949");
		}
		if (!addToCharsetMap(map, "950", "Big5")) {
			addToCharsetMap(map, "950", "x-windows-950");
		}
		addToCharsetMap(map, "951", "Big5-HKSCS");
		map.put("28591", StandardCharsets.ISO_8859_1);
		map.put("20127", StandardCharsets.US_ASCII);
		map.put("65001", StandardCharsets.UTF_8);
		map.put("1200", StandardCharsets.UTF_16LE);
		map.put("1201", StandardCharsets.UTF_16BE);

		CODESET_MAP = Collections.unmodifiableMap(map);
	}
		
	/**
	 * Adds a charset to the provided map if it is supported by the current JVM
	 * 
	 * @param map         the map to which the charset will be added.
	 * @param key         the key with which the specified charset is to be
	 *                    associated
	 * @param charsetName the name of the charset to be added.
	 */
	private static boolean addToCharsetMap(Map<String, Charset> map, String key, String charsetName) {
		if (Charset.isSupported(charsetName)) {
			map.put(key, Charset.forName(charsetName));
			return true;
		}
		return false;
	}

	/**
	 * Get the CharSet from the Win32_OperatingSystem CodeSet. (if not found by default Latin-1 windows-1252)
	 *
	 * @param windowsRemoteExecutor WindowsRemoteExecutor instance
	 * @param timeout Timeout in milliseconds.
	 * @return the encoding charset from Win32_OperatingSystem
	 * @throws TimeoutException To notify userName of timeout
	 * @throws WqlQuerySyntaxException On WQL syntax errors
	 * @throws WindowsRemoteException For any problem encountered on remote
	 * @see <a href="https://docs.microsoft.com/en-us/windows/win32/cimwin32prov/win32-operatingsystem">
	 * Win32_OperatingSystem class</a>
	 *
	 */
	public static Charset getWindowsEncodingCharset(
			final WindowsRemoteExecutor windowsRemoteExecutor,
			final long timeout) throws TimeoutException, WqlQuerySyntaxException, WindowsRemoteException {

		if (windowsRemoteExecutor == null || timeout < 1) {
			return DEFAULT_CHARSET;
		}

		final List<Map<String, Object>> result = windowsRemoteExecutor.executeWql(
				"SELECT CodeSet FROM Win32_OperatingSystem",
				timeout);

		final String codeSet = result.stream()
				.map(row -> (String) row.get("CodeSet"))
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(DEFAULT_CODESET);

		return CODESET_MAP.getOrDefault(codeSet, DEFAULT_CHARSET);
	}


	/**
	 * Builds a new output file name, with 99.9999999% chances of being unique
	 * on the remote system
	 * @return file name
	 */
	public static String buildNewOutputFileName() {
		return String.format("SEN_%s_%d_%d",
				Utils.getComputerName(),
				Utils.getCurrentTimeMillis(),
				(long) (Math.random() * 1000000));
	}

	/**
	 * Copy the local files to the share and update the command with their path as seen in the remote system.
	 *
	 * @param command The command (mandatory)
	 * @param localFiles The local files to copy list
	 * @param uncSharePath The UNC path of the share
	 * @param remotePath The remote path
	 * @return The updated command.
	 * @throws IOException If an I/O error occurs.
	 */
	public static String copyLocalFilesToShare(
			final String command,
			final List<String> localFiles,
			final String uncSharePath,
			final String remotePath) throws IOException {

		Utils.checkNonNull(command, "command");

		if (localFiles == null || localFiles.isEmpty()) {
			return command;
		}

		Utils.checkNonNull(uncSharePath, "uncSharePath");
		Utils.checkNonNull(remotePath, "remotePath");

		try {
			return localFiles.stream()
					.reduce(
							command,
							(cmd, localFile) -> {
								try {
									final Path localFilePath = Paths.get(localFile);
									final Path remoteFilePath = copyToShare(localFilePath, uncSharePath, remotePath);

									return caseInsensitiveReplace(cmd, localFile, remoteFilePath.toString());

								} catch (final IOException e) {
									throw new RuntimeException(e);
								}
							});
		} catch (final Exception e) {
			if (e.getCause() instanceof IOException) {
				throw (IOException) e.getCause();
			}
			throw e;
		}
	}

	/**
	 * Copy a file to the share.
	 * <p>
	 * If the same file is already present on the share, the copy is not performed.
	 * The "last-modified" time is used to determine whether the file needs to be
	 * copied or not.
	 * <p>
	 * @param localFilePath The path to the file to copy
	 * @param uncSharePath The UNC path of the share
	 * @param remotePath The remote path
	 * @return the path to the copied file, as seen in the remote system
	 * @throws IOException If an I/O error occurs.
	 */
	static Path copyToShare(
			final Path localFilePath,
			final String uncSharePath,
			final String remotePath) throws IOException {

		final Path targetUncPath = Paths.get(uncSharePath, localFilePath.getFileName().toString());
		final Path targetRemotePath = Paths.get(remotePath, localFilePath.getFileName().toString());

		if (Files.exists(targetUncPath)) {
			final FileTime sourceFileTime = Files.getLastModifiedTime(localFilePath);
			final FileTime targetFileTime = Files.getLastModifiedTime(targetUncPath);
			if (sourceFileTime.compareTo(targetFileTime) <= 0) {
				// File is already present on the target, simply skip the copy operation
				return targetRemotePath;
			}
		}

		// Copy
		Files.copy(
				localFilePath,
				targetUncPath,
				StandardCopyOption.COPY_ATTRIBUTES,
				StandardCopyOption.REPLACE_EXISTING);

		// Return the path to the copied file, as seen in the remote system
		return targetRemotePath;
	}

	/**
	 * Perform a case-insensitive replace of all occurrences of <em>target</em> string with
	 * specified <em>replacement</em>
	 * <p>
	 * Similar to <code>String.replace(target, replacement)</code>
	 * <p>
	 * @param string The string to parse
	 * @param target The string to replace
	 * @param replacement The replacement string
	 * <p>
	 * @return updated string
	 */
	static String caseInsensitiveReplace(final String string, final String target, final String replacement) {
		return string == null || target == null ? string :
			Pattern.compile(target, Pattern.LITERAL | Pattern.CASE_INSENSITIVE)
			.matcher(string)
			.replaceAll(Matcher.quoteReplacement(replacement == null ? Utils.EMPTY : replacement));
	}
}
