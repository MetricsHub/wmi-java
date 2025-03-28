package org.metricshub.wmi.windows.remote.share;

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

import org.metricshub.wmi.windows.remote.WindowsRemoteExecutor;

@FunctionalInterface
public interface ShareRemoteDirectoryConsumer<W extends WindowsRemoteExecutor, R, S, T> {
	/**
	 * Share the remote directory on the host.
	 *
	 * @param windowsRemoteExecutor WindowsRemoteExecutor instance.
	 * @param remotePath The remote path.
	 * @param shareName The Share Name.
	 * @param timeout Timeout in milliseconds.
	 */
	void apply(W windowsRemoteExecutor, R remotePath, S shareName, T timeout);
}
