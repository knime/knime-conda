/*
 * ------------------------------------------------------------------------
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
 * ------------------------------------------------------------------------
 *
 * History
 *   Sep 25, 2014 (Patrick Winter): created
 */
package org.knime.conda.nodes.envprop;

import java.util.List;
import java.util.Optional;

import org.knime.conda.CondaEnvironmentIdentifier;
import org.knime.core.node.NodeDialogPane;
import org.knime.core.node.NodeFactory;
import org.knime.core.node.NodeView;
import org.knime.core.webui.node.dialog.NodeDialog;
import org.knime.core.webui.node.dialog.NodeDialogFactory;
import org.knime.core.webui.node.dialog.NodeDialogManager;
import org.knime.core.webui.node.dialog.SettingsType;
import org.knime.core.webui.node.dialog.defaultdialog.DefaultNodeDialog;

/**
 * @author Marcel Wiedenmann, KNIME GmbH, Konstanz, Germany
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 */
public final class CondaEnvironmentPropagationNodeFactory extends NodeFactory<CondaEnvironmentPropagationNodeModel>
    implements NodeDialogFactory {

    private static final boolean HAS_WEBUI_DIALOG = //
        "js".equals(System.getProperty("org.knime.scripting.ui.mode")) // feature flag for new Scripting dialogs
            || Boolean.getBoolean("java.awt.headless"); // headless (remote workflow editing) -> we enforce webUI dialog

    /** Provides a function to select an default environment from a list of environments. */
    @FunctionalInterface
    public interface DefaultCondaEnvironmentSelector {
        /**
         * Select a default environment from a list of environments. The default environment will be selected by default
         * in the Conda Environment Propagation node.
         *
         * @param environments the list of all selectable environments
         * @return the environment to select by default or {@code Optional#empty()} if no environment fits
         */
        Optional<CondaEnvironmentIdentifier> selectDefaultEnvironment(List<CondaEnvironmentIdentifier> environments);
    }

    /**
     * Add a default environment selector. The default environment will be selected by default when opening the dialog
     * of the Conda Environment Propagation node. Note that the selectors will be considered in the order in which they
     * are added.
     *
     * @param selector a {@link DefaultCondaEnvironmentSelector}
     */
    public static void addDefaultCondaEnvironmentSelector(final DefaultCondaEnvironmentSelector selector) {
        CondaEnvironmentPropagationNodeModel.DEFAULT_ENV_SELECTORS.add(selector);
    }

    @Override
    public CondaEnvironmentPropagationNodeModel createNodeModel() {
        return new CondaEnvironmentPropagationNodeModel();
    }

    /**
     * @since 5.10
     */
    @Override
    public boolean hasNodeDialog() {
        return HAS_WEBUI_DIALOG;
    }

    @Override
    public boolean hasDialog() {
        return true;
    }

    @Override
    protected NodeDialogPane createNodeDialogPane() {
        if (HAS_WEBUI_DIALOG) {
            return NodeDialogManager.createLegacyFlowVariableNodeDialog(createNodeDialog());
        } else {
            return new CondaEnvironmentPropagationNodeDialog();
        }
    }

    /**
     * @since 5.10
     */
    @Override
    public NodeDialog createNodeDialog() {
        return new DefaultNodeDialog(SettingsType.MODEL, CondaEnvironmentPropagationNodeParameters.class);
    }

    @Override
    public int getNrNodeViews() {
        return 0;
    }

    @Override
    public NodeView<CondaEnvironmentPropagationNodeModel> createNodeView(final int viewIndex,
        final CondaEnvironmentPropagationNodeModel nodeModel) {
        return null;
    }
}
