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

import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.locks.ReentrantLock;

import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.knime.conda.Conda;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.NodeDialogPane;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.node.NotConfigurableException;
import org.knime.core.node.defaultnodesettings.DialogComponent;
import org.knime.core.node.defaultnodesettings.DialogComponentBoolean;
import org.knime.core.node.defaultnodesettings.DialogComponentButtonGroup;
import org.knime.core.node.defaultnodesettings.DialogComponentString;
import org.knime.core.node.defaultnodesettings.SettingsModelBoolean;
import org.knime.core.node.defaultnodesettings.SettingsModelString;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.util.ThreadUtils;

/**
 * @author Marcel Wiedenmann, KNIME GmbH, Konstanz, Germany
 */
final class CondaEnvironmentPropagationNodeDialog extends NodeDialogPane {

    private static final String NORMAL = "populated";

    private static final String ERROR = "error";

    private final JPanel m_panel = new JPanel(new CardLayout());

    private final CondaEnvironmentsList m_environmentsList =
        new CondaEnvironmentsList(CondaEnvironmentPropagationNodeModel.createCondaEnvironmentNameModel());

    private final CondaPackagesTable m_packagesTable =
        new CondaPackagesTable(CondaEnvironmentPropagationNodeModel.createPackagesConfig(), m_environmentsList);

    private final DialogComponentButtonGroup m_validationMethodSelection = new DialogComponentButtonGroup(
        CondaEnvironmentPropagationNodeModel.createEnvironmentValidationMethodModel(), "Environment validation", true,
        new String[]{"Check name only", "Check name and packages", "Always overwrite existing environment"},
        CondaEnvironmentPropagationNodeModel.createEnvironmentValidationMethodKeys());

    private final DialogComponentString m_outputVariableNameInput;

    private final DialogComponentBoolean m_preserveIncompleteEnvsCheckbox;

    private final SettingsModelString m_sourceOsModel = CondaEnvironmentPropagationNodeModel.createSourceOsModel();

    private final JTextArea m_errorLabel = new JTextArea("");

    private final ReentrantLock m_refreshVsSaveLock = new ReentrantLock();

    private Conda m_conda;

    public CondaEnvironmentPropagationNodeDialog() {
        final JPanel panel = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        panel.add(m_environmentsList.getComponent(), gbc);

        gbc.gridy++;
        gbc.weighty = 1;
        gbc.gridwidth = 2;
        panel.add(m_packagesTable.getComponent(), gbc);

        gbc.gridy++;
        gbc.weighty = 0;
        panel.add(getFirstComponent(m_validationMethodSelection, Box.class), gbc);

        final SettingsModelString outputVariableNameModel =
            CondaEnvironmentPropagationNodeModel.createOutputVariableNameModel();
        m_outputVariableNameInput = new DialogComponentString(outputVariableNameModel, "Output variable name");
        gbc.gridy++;
        panel.add(createOutputVariableNameInputPanel(m_outputVariableNameInput), gbc);

        m_panel.add(panel, NORMAL);

        final SettingsModelBoolean preserveIncompleteEnvsModel =
            CondaEnvironmentPropagationNodeModel.createPreserveIncompleteEnvsModel();
        m_preserveIncompleteEnvsCheckbox = new DialogComponentBoolean(preserveIncompleteEnvsModel,
            "Preserve a possibly incomplete Conda environment if the creation fails");
        gbc.gridy++;
        panel.add(getFirstComponent(m_preserveIncompleteEnvsCheckbox, JCheckBox.class), gbc);

        final JPanel errorPanel = new JPanel(new GridBagLayout());
        final GridBagConstraints errorGbc = new GridBagConstraints();
        errorGbc.anchor = GridBagConstraints.CENTER;
        errorGbc.fill = GridBagConstraints.BOTH;
        m_errorLabel.setEditable(false);
        final JLabel dummy = new JLabel();
        m_errorLabel.setBackground(dummy.getBackground());
        m_errorLabel.setFont(dummy.getFont());
        m_errorLabel.setForeground(Color.RED);
        errorPanel.add(m_errorLabel, errorGbc);

        m_panel.add(errorPanel, ERROR);

        addTab("Options", m_panel, false);

        m_environmentsList.getEnvironmentNameModel().addChangeListener(e -> refreshPackages());
    }

    private static Component createOutputVariableNameInputPanel(final DialogComponentString outputVariableNameInput) {
        final JPanel panel = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.ipadx = 5;
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(getFirstComponent(outputVariableNameInput, JLabel.class), gbc);
        gbc.gridx++;
        gbc.weightx = 1;
        panel.add(getFirstComponent(outputVariableNameInput, JTextField.class), gbc);
        return panel;
    }

    @Override
    protected void loadSettingsFrom(final NodeSettingsRO settings, final PortObjectSpec[] specs)
        throws NotConfigurableException {
        ((CardLayout)m_panel.getLayout()).show(m_panel, NORMAL);
        m_environmentsList.setToInitializingView();
        m_packagesTable.setToUninitializedView();

        m_environmentsList.loadSettingsFrom(settings);
        m_packagesTable.loadSettingsFrom(settings);
        m_validationMethodSelection.loadSettingsFrom(settings, specs);
        m_outputVariableNameInput.loadSettingsFrom(settings, specs);
        m_preserveIncompleteEnvsCheckbox.loadSettingsFrom(settings, specs);
        try {
            m_sourceOsModel.loadSettingsFrom(settings);
        } catch (InvalidSettingsException ex) {
            throw new NotConfigurableException(ex.getMessage(), ex);
        }

        ThreadUtils.threadWithContext(new EnvironmentsAndPackagesInitializer()).start();
    }

    private void refreshPackages() {
        if (m_packagesTable.allowsRefresh()) {
            ThreadUtils.threadWithContext(() -> {
                m_refreshVsSaveLock.lock();
                try {
                    m_packagesTable.refreshPackages();
                } finally {
                    m_refreshVsSaveLock.unlock();
                }
            }).start();
        }
    }

    @Override
    protected void saveSettingsTo(final NodeSettingsWO settings) throws InvalidSettingsException {
        if (m_refreshVsSaveLock.tryLock()) {
            try {
                m_environmentsList.saveSettingsTo(settings);
                m_packagesTable.saveSettingsTo(settings);
                m_validationMethodSelection.saveSettingsTo(settings);
                m_outputVariableNameInput.saveSettingsTo(settings);
                m_preserveIncompleteEnvsCheckbox.saveSettingsTo(settings);
                m_sourceOsModel.saveSettingsTo(settings);
            } finally {
                m_refreshVsSaveLock.unlock();
            }
        } else {
            throw new InvalidSettingsException(
                "Please wait until collecting the environments and packages is complete before applying the settings.");
        }
    }

    @Override
    public synchronized void onClose() {
        m_conda = null;
    }

    static <T extends Component> T getFirstComponent(final DialogComponent dialogComponent,
        final Class<T> componentClass) {
        for (final Component c : dialogComponent.getComponentPanel().getComponents()) {
            if (componentClass.isInstance(c)) {
                @SuppressWarnings("unchecked")
                final T safe = (T)c;
                return safe;
            }
        }
        return null;
    }

    static void invokeOnEDT(final Runnable r) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
            } else {
                SwingUtilities.invokeAndWait(r);
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (final InvocationTargetException ex) {
            NodeLogger.getLogger(CondaEnvironmentPropagationNodeDialog.class).error(ex);
        }
    }

    private final class EnvironmentsAndPackagesInitializer implements Runnable {

        @Override
        public void run() {
            m_refreshVsSaveLock.lock();
            try {
                final Conda conda = getOrCreateConda();
                m_environmentsList.initializeEnvironments(conda);
                m_packagesTable.initializePackages(conda);
            } catch (final InvalidSettingsException ex) {
                invokeOnEDT(() -> {
                    m_errorLabel.setText(ex.getMessage());
                    ((CardLayout)m_panel.getLayout()).show(m_panel, ERROR);
                });
                NodeLogger.getLogger(CondaEnvironmentPropagationNodeDialog.class).warn(ex.getMessage(), ex);
            } finally {
                m_refreshVsSaveLock.unlock();
            }
        }

        /**
         * Validating the Conda installation takes some time, so cache the instance once it has been created.
         */
        private synchronized Conda getOrCreateConda() throws InvalidSettingsException {
            if (m_conda == null) {
                m_conda = CondaEnvironmentPropagationNodeModel.createConda();
            }
            return m_conda;
        }
    }
}
