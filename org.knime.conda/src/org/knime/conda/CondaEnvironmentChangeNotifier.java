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
 *   Mar 3, 2022 (benjamin): created
 */
package org.knime.conda;

import java.util.HashSet;
import java.util.Set;

// TODO(benjamin) rename?
/**
 *
 * @author benjamin
 */
public final class CondaEnvironmentChangeNotifier {

    private CondaEnvironmentChangeNotifier() {
    }

    private static final Set<CondaEnvironmentChangeListener> ENV_CHANGE_LISTENERS = new HashSet<>();

    /**
     * Register an {@link CondaEnvironmentChangeListener} that is notified if conda environments are changed.
     *
     * @param listener
     */
    public static synchronized void registerEnvironmentChangeListener(final CondaEnvironmentChangeListener listener) {
        ENV_CHANGE_LISTENERS.add(listener);
    }

    /**
     * Remove an {@link CondaEnvironmentChangeListener} that was registered with
     * {@link #registerEnvironmentChangeListener(CondaEnvironmentChangeListener)} before.
     *
     * @param listener
     */
    public static synchronized void removeEnvironmentChangeListener(final CondaEnvironmentChangeListener listener) {
        ENV_CHANGE_LISTENERS.remove(listener);
    }

    /**
     * Notify listeners that the creation of an environment finished or failed.
     *
     * @param env the identifier of the environment
     * @param overwritten if the {@code --force} option was used and an environment with the same name could have been
     *            overwritten
     * @param success if the creation succeeded
     */
    public static void notifyEnvironmentCreated(final CondaEnvironmentIdentifier env, final boolean overwritten,
        final boolean success) {
        for (var l : ENV_CHANGE_LISTENERS) {
            l.environmentCreated(env, overwritten, success);
        }
    }

    /**
     * Notify listeners that the deletion of an environment finished or failed.
     *
     * @param env the identifier of the environment
     * @param success if the creation succeeded
     */
    public static void notifyEnvironmentDeleted(final CondaEnvironmentIdentifier env, final boolean success) {
        for (var l : ENV_CHANGE_LISTENERS) {
            l.environmentDeleted(env, success);
        }
    }

    /**
     * The interface for a listener that is notified if environments are created or deleted by the {@link Conda} class.
     */
    public static interface CondaEnvironmentChangeListener {

        // TODO(benjamin) call these methods!

        /**
         * Called after the creation of an environment finished or failed.
         *
         * @param env the identifier of the environment
         * @param overwritten if an environment with the same name was overwritten
         * @param success if the creation succeeded
         */
        default void environmentCreated(final CondaEnvironmentIdentifier env, final boolean overwritten,
            final boolean success) {
            // Do nothing by default
        }

        /**
         * Called after the deletion of an environment finished or failed.
         *
         * @param env the identifier of the environment
         * @param success if the creation succeeded
         */
        default void environmentDeleted(final CondaEnvironmentIdentifier env, final boolean success) {
            // Do nothing by default
        }
    }
}
