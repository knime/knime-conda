package org.knime.externalprocessprovider;

import java.nio.file.Path;

/**
 * Describes an external process. The process can be started via the
 * {@link ProcessBuilder} returned by {@link #createProcessBuilder()}.
 * <P>
 * Implementation note: Implementors of this interface must override
 * {@link #hashCode()}, {@link #equals(Object)}, and {@link #toString()} in a
 * value-based way.
 *
 * @author Marcel Wiedenmann, KNIME GmbH, Konstanz, Germany
 * @author Christian Dietz, KNIME GmbH, Konstanz, Germany
 * @author Benjamin Wilhelm, KNIME GmbH, Konstanz, Germany
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @author Ali Marvi, KNIME GmbH, Konstanz, Germany
 * @since 5.11
 */
public interface ExternalProcessProvider {

	/**
	 * @return A {@link ProcessBuilder} that can be used to parameterize and start
	 *         the process represented by this command instance.
	 */
	ProcessBuilder createProcessBuilder();

	/**
	 * @return The path to the (Python/R/...) executable. Should only be used to
	 *         gather information about the executable without running the
	 *         executable. Use {@link #createProcessBuilder()} to start a processes,
	 *         because it could include environment activation or other necessary
	 *         setup steps.
	 */
	Path getExecutablePath();

	@Override
	int hashCode();

	@Override
	boolean equals(Object obj);

	@Override
	String toString();
}
