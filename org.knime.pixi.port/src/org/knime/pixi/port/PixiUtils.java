package org.knime.pixi.port;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
	 * Resolves the project directory for a Pixi environment based on the provided lock file content. If a hash
	 * collision occurs (same hash but different content), appends a numeric suffix to find a free directory.
	 *
	 * @param lockFileContent the content of the pixi.lock file
	 * @return the path to the resolved project directory
	 * @throws IOException if an I/O error occurs while creating directories or reading files
	 * @throws IllegalArgumentException if lockFileContent is null or blank
	 */
	public static Path resolveProjectDirectory(final String lockFileContent) throws IOException {
		if (lockFileContent == null || lockFileContent.isBlank()) {
			throw new IllegalArgumentException("pixi.lock content is empty.");
		}

		final Path baseProjectDir = PixiEnvMapping.resolvePixiEnvDirectory(lockFileContent);
		return resolveWithCollisionHandling(baseProjectDir, lockFileContent);
	}

	/**
	 * Resolves a project directory, handling potential hash collisions by appending suffixes.
	 *
	 * @param baseDir the base directory path derived from the lock file hash
	 * @param lockFileContent the expected lock file content
	 * @return a directory path that either doesn't exist yet or contains matching content
	 * @throws IOException if an I/O error occurs
	 */
	private static Path resolveWithCollisionHandling(final Path baseDir, final String lockFileContent)
		throws IOException {
		Path candidateDir = baseDir;
		int suffix = 0;

		while (suffix < 10) { // Arbitrary limit to prevent infinite loops. We should never have more than a few collisions in practice.
			Files.createDirectories(candidateDir);
			final Path lockFilePath = candidateDir.resolve("pixi.lock");

			// Directory is free to use if no lock file exists yet
			if (!Files.exists(lockFilePath)) {
				return candidateDir;
			}

			// Check if existing lock file matches (no collision)
			final String existingContent = Files.readString(lockFilePath, StandardCharsets.UTF_8);
			if (existingContent.equals(lockFileContent)) {
				return candidateDir;
			}

			// Hash collision detected - try with numeric suffix
			candidateDir = baseDir.resolveSibling(baseDir.getFileName() + "_" + suffix);
			suffix++;
		}
		throw new IOException("Failed to resolve a unique project directory after 10 attempts.");
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
