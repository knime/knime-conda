package org.knime.pixi.port;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.knime.conda.envinstall.pixi.PixiBinary.CallResult;

/**
 * Utilities for working with Pixi environments
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.11
 */
public final class PixiUtils {
	private PixiUtils() {
	}

	/**
	 * Resolves the project directory for a Pixi environment based on the provided
	 * manifest text. If the manifest text is empty or null, an
	 * IllegalArgumentException is thrown. If the manifest text is valid, the method
	 * creates the necessary directories and writes the manifest content to a
	 * "pixi.toml" file within the resolved project directory.
	 * 
	 * @param manifestText the content of the pixi.toml manifest file
	 * @return the path to the resolved project directory
	 * @throws IOException if an I/O error occurs while creating directories or
	 *                     writing the manifest file
	 */
	public static Path resolveProjectDirectory(final String manifestText) throws IOException {
		// Otherwise, use the preference-based directory and write manifest
		if (manifestText == null || manifestText.isBlank()) {
			throw new IllegalArgumentException("pixi.toml manifest text is empty.");
		}

		final Path projectDir = PixiEnvMapping.resolvePixiEnvDirectory(manifestText);
		Files.createDirectories(projectDir);

		final Path manifestPath = projectDir.resolve("pixi.toml");
		// Only write if file doesn't exist, or overwrite with the provided content
		if (!Files.exists(manifestPath)) {
			Files.writeString(manifestPath, manifestText, StandardCharsets.UTF_8, StandardOpenOption.CREATE);
		} else {
			// TODO a toml with the hash already exists. We should check if the content is
			// the same.
			// If not, we need to create a new directory.
		}
		return projectDir;
	}

	/**
	 * Formats a message from the given CallResult, including the exit code, stdout,
	 * and stderr.
	 * 
	 * @param callResult the result of a call to the Pixi binary, containing the
	 *                   exit code, stdout,
	 * @return a formatted message summarizing the call result, including the exit
	 *         code, stdout, and stderr
	 */
	@SuppressWarnings("restriction")
	public static String getMessageFromCallResult(final CallResult callResult) {
		final String stdout = callResult.stdout() == null ? "" : callResult.stdout();
		final String stderr = callResult.stderr() == null ? "" : callResult.stderr();

		return "pixi install failed (exit code " + callResult.returnCode() + ").\n"
				+ (stderr.isBlank() ? "" : "---- stderr ----\n" + stderr + "\n")
				+ (stdout.isBlank() ? "" : "---- stdout ----\n" + stdout + "\n");
	}
}
