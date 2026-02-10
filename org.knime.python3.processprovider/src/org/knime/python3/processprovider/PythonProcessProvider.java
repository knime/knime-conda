package org.knime.python3.processprovider;

import java.nio.file.Path;

/**
 * Describes an external Python process. The process can be started via the {@link ProcessBuilder} returned by
 * {@link #createProcessBuilder()}.
 * <P>
 * Implementation note: Implementors of this interface must override {@link #hashCode()}, {@link #equals(Object)}, and
 * {@link #toString()} in a value-based way.
 *
 * @author Marcel Wiedenmann, KNIME GmbH, Konstanz, Germany
 * @author Christian Dietz, KNIME GmbH, Konstanz, Germany
 * @author Benjamin Wilhelm, KNIME GmbH, Konstanz, Germany
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 * @since 5.11
 */
public interface PythonProcessProvider {

    /**
     * @return A {@link ProcessBuilder} that can be used to parameterize and start the Python process represented by
     *         this command instance.
     */
    ProcessBuilder createProcessBuilder();

    /**
     * @return The path to the Python executable. Should only be used to gather information about the Python environment
     *         without running the Python executable. Use {@link #createProcessBuilder()} to start Python processes.
     */
    Path getPythonExecutablePath();

    @Override
    int hashCode();

    @Override
    boolean equals(Object obj);

    @Override
    String toString();
}
