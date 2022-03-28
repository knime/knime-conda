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
 *   Mar 4, 2022 (benjamin): created
 */
package org.knime.conda.prefs;

import java.nio.file.Files;
import java.nio.file.Paths;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.knime.conda.Conda;
import org.knime.core.node.NodeLogger;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Utility class to access preferences related to the Conda integration.
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Konstanz, Germany
 */
public final class CondaPreferences {

    private static final NodeLogger LOGGER = NodeLogger.getLogger(CondaPreferences.class);

    private static final String PYTHON_BUNDLE_NAME = "org.knime.python2";

    private static final String CONDA_BUNDLE_NAME = FrameworkUtil.getBundle(CondaPreferences.class).getSymbolicName();

    static final ScopedPreferenceStore PREF_STORE =
        new ScopedPreferenceStore(InstanceScope.INSTANCE, CONDA_BUNDLE_NAME);

    static final String CONDA_DIR_PREF_KEY = "condaDirectoryPath";

    private CondaPreferences() {
    }

    /**
     * @return the configured path to the Conda installation directory
     */
    public static String getCondaInstallationDirectory() {
        // Return the instance scope preference if present
        String condaDir = instanceScopePrefs().get(CONDA_DIR_PREF_KEY, null);
        if (condaDir != null) {
            return condaDir;
        }

        // Check the Python default preferences
        // The Executor sets the default preferences if configured via an .epf file
        final IEclipsePreferences pythonDefaultPrefs = DefaultScope.INSTANCE.getNode(PYTHON_BUNDLE_NAME);
        condaDir = pythonDefaultPrefs.get(CONDA_DIR_PREF_KEY, null);
        if (condaDir != null) {
            LOGGER.warn(
                "Using 'org.knime.python2/condaDirectoryPath' to configure the conda installation directory is deprecated. "
                    + "Please use 'org.knime.conda/condaDirectoryPath'.");
            return condaDir;
        }

        // Return the default
        return PREF_STORE.getString(CONDA_DIR_PREF_KEY);
    }

    /**
     * Add a change listener that is notified each time a property in the Conda preferences changes.
     *
     * @param changeListener the change listener
     */
    public static void addPropertyChangeListener(final IPropertyChangeListener changeListener) {
        PREF_STORE.addPropertyChangeListener(changeListener);
    }

    /**
     * Remove a change listener that was registered with {@link #addPropertyChangeListener(IPropertyChangeListener)}.
     *
     * @param changeListener the change listener
     */
    public static void removePropertyChangeListener(final IPropertyChangeListener changeListener) {
        PREF_STORE.removePropertyChangeListener(changeListener);
    }

    /** Initialize the defaults for the Conda preferences */
    static void initDefaults() {
        initDefaultCondaInstallationDirectory();

        // Legacy support:
        // Copy the Conda installation directory preference from
        // the Python preferences if it is set there.
        copyCondaPrefFromPython();
    }

    /**
     * Test the Conda installation at the given path.
     *
     * @param condaDirectoryPath the path to the conda installation
     * @return the version string of conda at the given directory
     * @throws Exception if the installation test fails. The message of the exception can be shown to the user.
     */
    static String testCondaInstallation(final String condaDirectoryPath) throws Exception {
        final var conda = new Conda(condaDirectoryPath);
        var condaVersionString = conda.getVersionString();
        try {
            condaVersionString = "Conda version: " + Conda.condaVersionStringToVersion(condaVersionString).toString();
        } catch (final IllegalArgumentException ex) {
            // Ignore and use raw version string.
        }
        return condaVersionString;
    }

    /** Get the instance scope preferences for org.knime.conda (will not default to default prefs like PREF_STORE) */
    private static IEclipsePreferences instanceScopePrefs() {
        return InstanceScope.INSTANCE.getNode(CONDA_BUNDLE_NAME);
    }

    /** Copy the Conda installation dir from the Python instance preferences if necessary */
    private static void copyCondaPrefFromPython() {
        // CASES:
        // Configured in Conda prefs: Do nothing
        // Configured in Python prefs: Copy to Conda prefs
        // Not configured anywhere: Do noting

        final IEclipsePreferences condaInstancePrefs = instanceScopePrefs();
        final String condaDirCondaInstance = condaInstancePrefs.get(CONDA_DIR_PREF_KEY, null);
        if (condaDirCondaInstance != null) {
            return;
        }

        // Check if the setting is set in the Python instance preferences
        final IEclipsePreferences pythonInstancePrefs = InstanceScope.INSTANCE.getNode(PYTHON_BUNDLE_NAME);
        final String condaDirPythonInstance = pythonInstancePrefs.get(CONDA_DIR_PREF_KEY, null);
        if (condaDirPythonInstance != null) {
            // Use the conda dir from the Python preferences
            condaInstancePrefs.put(CONDA_DIR_PREF_KEY, condaDirPythonInstance);
            LOGGER.info("Copied the path to the conda installation from the Python preferences. Using '"
                + condaDirPythonInstance + "'.");

            // Flush the preference store
            try {
                condaInstancePrefs.flush();
            } catch (final BackingStoreException ex) {
                LOGGER.warn("Failed to flush the conda preferences with the updated path to conda installation.", ex);
            }

            // Remove the conda installation dir preference from the Python preferences
            pythonInstancePrefs.remove(CONDA_DIR_PREF_KEY);
            try {
                pythonInstancePrefs.flush();
            } catch (final BackingStoreException ex) {
                LOGGER.warn("Failed to flush the Python preferences with the removed path to conda installation.", ex);
            }
        }
    }

    /** Initialize the conda installation directory in the default scope */
    private static void initDefaultCondaInstallationDirectory() {
        final var defaultCondaInstallDir = getDefaultCondaInstallationDirectory();
        LOGGER.debug("Initializing default Conda installation directory with '" + defaultCondaInstallDir + "'.");
        PREF_STORE.setDefault(CONDA_DIR_PREF_KEY, defaultCondaInstallDir);
    }

    /** @return The default value for the Conda installation directory path config entry. */
    private static String getDefaultCondaInstallationDirectory() {
        try {
            final String condaRoot = System.getenv("CONDA_ROOT");
            if (condaRoot != null) {
                return Paths.get(condaRoot).toString();
            }
        } catch (final Exception ex) { // NOSONAR Optional step
            // Ignore and continue with fallback.
        }
        try {
            // CONDA_EXE, if available, points to <CONDA_ROOT>/{bin|Scripts}/conda(.exe). We want <CONDA_ROOT>.
            final String condaExe = System.getenv("CONDA_EXE");
            if (condaExe != null) {
                return Paths.get(condaExe).getParent().getParent().toString();
            }
        } catch (final Exception ex) { // NOSONAR Optional step
            // Ignore and continue with fallback.
        }
        try {
            final String userHome = System.getProperty("user.home");
            if (userHome != null) {

                // Try "~/anaconda3/"
                final var anaconda3Path = Paths.get(userHome, "anaconda3");
                if (Files.isDirectory(anaconda3Path)) {
                    return anaconda3Path.toString();
                }

                // Try "~/miniconda3/"
                final var miniconda3Path = Paths.get(userHome, "miniconda3");
                if (Files.isDirectory(miniconda3Path)) {
                    return miniconda3Path.toString();
                }
            }
        } catch (final Exception ex) { // NOSONAR Optional step
            // Ignore and continue with fallback.
        }
        return "";
    }
}
