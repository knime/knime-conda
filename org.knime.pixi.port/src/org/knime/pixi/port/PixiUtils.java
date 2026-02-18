package org.knime.pixi.port;

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
     * Formats a message from the given CallResult, including the exit code, stdout, and stderr.
     *
     * @param callResult the result of a call to the Pixi binary, containing the exit code, stdout,
     * @return a formatted message summarizing the call result, including the exit code, stdout, and stderr
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
