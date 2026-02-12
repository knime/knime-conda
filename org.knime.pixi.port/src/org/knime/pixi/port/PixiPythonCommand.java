/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 * History
 *   Jan 13, 2026 (Marc Lehner): created
 */
package org.knime.pixi.port;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.externalprocessprovider.ExternalProcessProvider;

/**
 * Pixi-specific implementation of {@link ExternalProcessProvider}. Executes
 * Python processes via {@code pixi run python} to ensure proper environment
 * activation and variable setup.
 * <P>
 * This command resolves the pixi binary and constructs a command line that
 * invokes Python through pixi's environment runner, which handles all necessary
 * environment setup automatically.
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 * @since 5.11
 */
public final class PixiPythonCommand implements ExternalProcessProvider {

	private final Path m_pixiTomlPath;

	private final String m_pixiEnvironmentName;

	/**
	 * Constructs a {@link ExternalProcessProvider} that describes a Python process
	 * run via pixi in the environment identified by the given pixi.toml manifest
	 * file.<br>
	 * The validity of the given arguments is not tested.
	 *
	 * @param pixiTomlPath    The path to the pixi.toml manifest file that describes
	 *                        the environment.
	 * @param environmentName The name of the environment within the pixi project
	 *                        (e.g., "default").
	 */
	private PixiPythonCommand(final Path pixiTomlPath, final String environmentName) {
		m_pixiTomlPath = Objects.requireNonNull(pixiTomlPath, "pixiTomlPath must not be null");
		m_pixiEnvironmentName = Objects.requireNonNull(environmentName, "environmentName must not be null");
	}

	/**
	 * Constructs a {@link ExternalProcessProvider} that describes a Python process
	 * run via pixi in the default environment identified by the given pixi.toml
	 * manifest file.<br>
	 * The validity of the given arguments is not tested.
	 *
	 * @param pixiTomlPath The path to the pixi.toml manifest file that describes
	 *                     the environment.
	 */
	PixiPythonCommand(final Path pixiTomlPath) {
		this(pixiTomlPath, "default");
	}

	@Override
	public ProcessBuilder createProcessBuilder() {
		try {
			final String pixiBinaryPath = PixiBinary.getPixiBinaryPath();
			final List<String> command = new ArrayList<>();
			command.add(pixiBinaryPath);
			command.add("run");
			command.add("--as-is"); // --no-install + --frozen -> fail if env not ready, but don't try to install it
			command.add("--manifest-path");
			command.add(m_pixiTomlPath.toString());
			command.add("--environment");
			command.add(m_pixiEnvironmentName);
			command.add("--no-progress");
			command.add("python");
			return new ProcessBuilder(command);
		} catch (PixiBinaryLocationException ex) {
			throw new IllegalStateException(
					"Could not locate pixi binary. Please ensure the pixi bundle is properly installed.", ex);
		}
	}

	@Override
	public Path getExecutablePath() {
		// Resolve the actual Python executable path within the environment
		// This is used for informational purposes only, not for execution
		final Path projectDir = m_pixiTomlPath.getParent();
		final Path envDir = projectDir.resolve(".pixi").resolve("envs").resolve(m_pixiEnvironmentName);
		final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

		// Return the path even if it doesn't exist yet - the environment might not be
		// installed
		return isWindows ? envDir.resolve("python.exe") : envDir.resolve("bin").resolve("python");
	}

	@Override
	public int hashCode() {
		return Objects.hash(m_pixiTomlPath, m_pixiEnvironmentName);
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null || getClass() != obj.getClass()) {
			return false;
		}
		final PixiPythonCommand other = (PixiPythonCommand) obj;
		return Objects.equals(m_pixiTomlPath, other.m_pixiTomlPath)
				&& Objects.equals(m_pixiEnvironmentName, other.m_pixiEnvironmentName);
	}

	@Override
	public String toString() {
		return "pixi run --manifest-path " + m_pixiTomlPath + " --environment " + m_pixiEnvironmentName
				+ " --no-progress python";
	}
}
