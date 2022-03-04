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

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jface.preference.DirectoryFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * The preference page for Conda.
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Konstanz, Germany
 */
public final class CondaPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    private DirectoryFieldEditor m_condaInstallationDirectory;

    private Label m_condaStatus;

    private Color m_colorError;

    private Color m_colorDefault;

    /** Construct the Conda preference page. */
    public CondaPreferencePage() {
        super(GRID);
    }

    @Override
    public void init(final IWorkbench workbench) {
        setPreferenceStore(CondaPreferences.PREF_STORE);
    }

    @Override
    protected void createFieldEditors() {
        final Composite parent = getFieldEditorParent();

        // Conda directory path
        m_condaInstallationDirectory = new DirectoryFieldEditor(CondaPreferences.CONDA_DIR_PREF_KEY,
            "Path to the Conda installation directory", parent);
        // Make sure that "Return" does not close the dialog if the directory field is selected
        m_condaInstallationDirectory.getTextControl(parent).addListener(SWT.Traverse, event -> {
            checkCondaInstallDir(m_condaInstallationDirectory.getStringValue());
            if (event.detail == SWT.TRAVERSE_RETURN) {
                event.doit = false;
            }
        });
        addField(m_condaInstallationDirectory);

        // Conda directory path status
        m_condaStatus = new Label(parent, SWT.NONE);
        m_colorDefault = m_condaStatus.getForeground();
        m_colorError = new Color(parent.getDisplay(), 255, 0, 0);
        final var gridData = new GridData();
        gridData.horizontalSpan = 2;
        m_condaStatus.setLayoutData(gridData);
    }

    @Override
    protected void checkState() {
        super.checkState();
        checkCondaInstallDir(m_condaInstallationDirectory.getStringValue());
    }

    @Override
    public void propertyChange(final PropertyChangeEvent event) {
        super.propertyChange(event);
        if (event.getSource() == m_condaInstallationDirectory) {
            checkCondaInstallDir((String)event.getNewValue());
        }
    }

    /** Check the given installation directory and display the status in m_condaStatus */
    private final void checkCondaInstallDir(final String dir) {
        new Thread(() -> {
            setCondaStatus("Testing Conda installation...", false);
            try {
                final String versionString = CondaPreferences.testCondaInstallation(dir);
                setCondaStatus(versionString, false);
            } catch (final Exception ex) {
                setCondaStatus(ex.getMessage(), true);
            }
        }).start();
    }

    /** Display the text in m_condaStatus and set the color of the text based on error */
    private void setCondaStatus(final String text, final boolean error) {
        performActionOnWidgetInUiThread(m_condaStatus, () -> {
            if (error) {
                m_condaStatus.setForeground(m_colorError);
            } else {
                m_condaStatus.setForeground(m_colorDefault);
            }
            setLabelTextAndResize(m_condaStatus, text);
            return null;
        }, false);
    }

    // COPIED FROM PythonPreferenceUtils

    /**
     * @throws RuntimeException If any exception occurs while executing {@link action}. {@link SWTException SWT
     *             exceptions} caused by disposed SWT components may be suppressed.
     */
    private static <T> T performActionOnWidgetInUiThread(final Widget widget, final Callable<T> action,
        final boolean performAsync) {
        final AtomicReference<T> returnValue = new AtomicReference<>();
        final AtomicReference<RuntimeException> exception = new AtomicReference<>();
        try {
            final Consumer<Runnable> executionMethod = performAsync //
                ? widget.getDisplay()::asyncExec //
                : widget.getDisplay()::syncExec;
            executionMethod.accept(() -> {
                if (!widget.isDisposed()) {
                    try {
                        final T result = action.call();
                        returnValue.set(result);
                    } catch (final Exception ex) {
                        exception.set(new RuntimeException(ex));
                    }
                }
            });
        } catch (final SWTException ex) {
            // Display or control have been disposed - ignore.
        }
        if (exception.get() != null) {
            throw exception.get();
        }
        return returnValue.get();
    }

    /**
     * @throws SWTException The usual SWT exceptions (access to disposed widget, access from another thread).
     */
    private static void setLabelTextAndResize(final Label label, final String text) {
        final String finalText = text != null ? text : "";
        final Point oldSize = label.getShell().computeSize(SWT.DEFAULT, SWT.DEFAULT);
        label.setText(finalText);
        label.getShell().layout(true, true);
        final Point newSize = label.getShell().computeSize(SWT.DEFAULT, SWT.DEFAULT);
        // Only grow window.
        if (newSize.x > oldSize.x || newSize.y > oldSize.y) {
            label.getShell().pack();
        }
    }
}
