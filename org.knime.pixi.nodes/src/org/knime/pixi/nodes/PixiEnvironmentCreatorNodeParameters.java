package org.knime.pixi.nodes;

import java.io.StringWriter;
import java.util.Arrays;

import org.knime.core.webui.node.dialog.defaultdialog.internal.button.ButtonWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.CancelableActionHandler;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.array.ArrayWidget;
import org.knime.node.parameters.updates.Effect;
import org.knime.node.parameters.updates.Effect.EffectType;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.ValueProvider;
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.choices.Label;
import org.knime.node.parameters.widget.message.TextMessage;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.IndentStyle;
import com.electronwill.nightconfig.toml.TomlWriter;

/**
 * Node Parameters describing the dialog for the Pixi Environment Creator node
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
@SuppressWarnings("restriction")
final class PixiEnvironmentCreatorNodeParameters implements NodeParameters {

    @Widget(title = "Packages", description = "Specify the packages to include in the environment")
    @ArrayWidget(elementLayout = ArrayWidget.ElementLayout.HORIZONTAL_SINGLE_LINE, addButtonText = "Add package")
    @ValueReference(PackageArrayRef.class)
    PackageSpec[] m_packages = new PackageSpec[]{
        new PackageSpec("python", PackageSource.CONDA, "3.14", "3.14"),
        new PackageSpec("knime-python-base", PackageSource.CONDA, "5.9", "5.9")
    };

    /**
     * Used by the execute() method to run "pixi install"
     *
     * @return the pixi toml content as string
     */
    String buildPixiToml() {
        return buildTomlFromPackages(m_packages);
    }

    // Button that triggers lock file generation and stores the result
    @Widget(title = "Check compatibility",
        description = "Click to check whether this pixi environment can be constructed on all selected operating systems")
    @ButtonWidget(actionHandler = PixiLockActionHandler.class, updateHandler = PixiLockUpdateHandler.class)
    @ValueReference(ButtonFieldRef.class)
    String m_PixiLockButton;

    @Widget(title = "Update dependencies",
        description = "Click to update all dependencies to their latest compatible versions and update the lock file")
    @ButtonWidget(actionHandler = PixiUpdateActionHandler.class, updateHandler = PixiUpdateUpdateHandler.class)
    @ValueReference(ButtonFieldRef.class)
    String m_PixiUpdateButton;

    // Hidden field that stores lock file content from button, reset to empty when packages change
    @ValueReference(PixiLockFileRef.class)
    @ValueProvider(ResetLockFileProvider.class)
    String m_pixiLockFileContent = "";

    @TextMessage(LockFileValidationProvider.class)
    @Effect(predicate = LockFileIsEmpty.class, type = EffectType.SHOW)
    Void m_lockFileValidationMessage;

    interface PackageArrayRef extends ParameterReference<PackageSpec[]> {
    }

    interface ButtonFieldRef extends ParameterReference<String> {
    }

    interface PixiLockFileRef extends ParameterReference<String> {
    }

    static final class PackageSpec implements NodeParameters {

        @Widget(title = "Name", description = "Package name")
        String m_name = "";

        @Widget(title = "Source", description = "Package source (conda or pip)")
        PackageSource m_source = PackageSource.CONDA;

        @Widget(title = "Min version", description = "Minimum version (inclusive, optional)")
        String m_minVersion = "";

        @Widget(title = "Max version", description = "Maximum version (exclusive, optional)")
        String m_maxVersion = "";

        // Default constructor for serialization
        PackageSpec() {
        }

        PackageSpec(final String name, final PackageSource source, final String minVersion, final String maxVersion) {
            m_name = name;
            m_source = source;
            m_minVersion = minVersion;
            m_maxVersion = maxVersion;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PackageSpec other)) {
                return false;
            }
            return java.util.Objects.equals(m_name, other.m_name)
                && m_source == other.m_source
                && java.util.Objects.equals(m_minVersion, other.m_minVersion)
                && java.util.Objects.equals(m_maxVersion, other.m_maxVersion);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(m_name, m_source, m_minVersion, m_maxVersion);
        }
    }

    enum PackageSource {
        @Label("Conda")
        CONDA,
        @Label("Pip")
        PIP
    }

    static final class PixiLockActionHandler
        extends PixiParameterUtils.AbstractPixiLockActionHandler<PackagesContent> {

        PixiLockActionHandler() {
            super("[PixiPackages]");
        }

        @Override
        protected String getManifestContent(final PackagesContent contentGetter) {
            return buildTomlFromPackages(contentGetter.m_packages);
        }

        @Override
        protected String prepareManifestContent(final String content) {
            return content; // Already TOML from buildTomlFromPackages
        }
    }

    static final class PixiLockUpdateHandler extends CancelableActionHandler.UpdateHandler<String, PackagesContent> {
    }

    static final class PixiUpdateActionHandler
        extends PixiParameterUtils.AbstractPixiUpdateActionHandler<PackagesContent> {

        PixiUpdateActionHandler() {
            super("[PixiPackages]");
        }

        @Override
        protected String getManifestContent(final PackagesContent contentGetter) {
            return buildTomlFromPackages(contentGetter.m_packages);
        }

        @Override
        protected String prepareManifestContent(final String content) {
            return content; // Already TOML from buildTomlFromPackages
        }
    }

    static final class PixiUpdateUpdateHandler extends CancelableActionHandler.UpdateHandler<String, PackagesContent> {
    }

    static final class PackagesContent {
        @ValueReference(PackageArrayRef.class)
        PackageSpec[] m_packages;
    }

    static final class ResetLockFileProvider extends PixiParameterUtils.AbstractResetLockFileProvider<PackageArrayRef, ButtonFieldRef> {
        @Override
        protected Class<PackageArrayRef> getContentRefClass() {
            return PackageArrayRef.class;
        }

        @Override
        protected Class<ButtonFieldRef> getButtonFieldRefClass() {
            return ButtonFieldRef.class;
        }

        @Override
        protected boolean contentEquals(final Object content1, final Object content2) {
            // Custom comparison for PackageSpec arrays
            if (content1 instanceof PackageSpec[] arr1 && content2 instanceof PackageSpec[] arr2) {
                return Arrays.equals(arr1, arr2);
            }
            return super.contentEquals(content1, content2);
        }
    }

    static final class LockFileValidationProvider extends PixiParameterUtils.AbstractLockFileValidationWithEffectProvider<PixiLockFileRef> {
        @Override
        protected Class<PixiLockFileRef> getLockFileRefClass() {
            return PixiLockFileRef.class;
        }
    }

    static final class LockFileIsEmpty extends PixiParameterUtils.AbstractLockFileIsEmptyPredicate<PixiLockFileRef> {
        @Override
        protected Class<PixiLockFileRef> getLockFileRefClass() {
            return PixiLockFileRef.class;
        }
    }

    private static String buildTomlFromPackages(final PackageSpec[] packages) {
        Config config = Config.inMemory();

        // [workspace] section.
        // Note: Need to use CommentedConfig here so that the toml lib contains a section header which pixi needs
        CommentedConfig workspace = CommentedConfig.inMemory();
        workspace.set("channels", Arrays.asList("knime", "conda-forge"));
        workspace.set("platforms", Arrays.asList("win-64", "linux-64", "osx-64", "osx-arm64"));
        config.set("workspace", workspace);

        // [dependencies] section for conda packages
        CommentedConfig dependencies = CommentedConfig.inMemory();
        for (PackageSpec pkg : packages) {
            if (pkg.m_name == null || pkg.m_name.isBlank() || pkg.m_source == PackageSource.PIP) {
                continue;
            }
            dependencies.set(pkg.m_name, formatVersionConstraint(pkg));
        }
        config.set("dependencies", dependencies);

        // [pypi-dependencies] section for pip packages
        CommentedConfig pypiDependencies = CommentedConfig.inMemory();
        boolean hasPipPackages = false;
        for (PackageSpec pkg : packages) {
            if (pkg.m_name != null && !pkg.m_name.isBlank() && pkg.m_source == PackageSource.PIP) {
                pypiDependencies.set(pkg.m_name, formatVersionConstraint(pkg));
                hasPipPackages = true;
            }
        }
        if (hasPipPackages) {
            config.set("pypi-dependencies", pypiDependencies);
        }

        // Write to string
        StringWriter writer = new StringWriter();
        TomlWriter tomlWriter = new TomlWriter();
        tomlWriter.setIndent(IndentStyle.SPACES_2);
        tomlWriter.setWriteTableInlinePredicate(path -> false); // Never write tables inline
        tomlWriter.write(config, writer);
        return writer.toString();
    }

    private static String formatVersionConstraint(final PackageSpec pkg) {
        if (pkg.m_minVersion != null && !pkg.m_minVersion.isBlank()) {
            if (pkg.m_maxVersion != null && !pkg.m_maxVersion.isBlank()) {
                return ">=" + pkg.m_minVersion + ",<=" + pkg.m_maxVersion;
            } else {
                return ">=" + pkg.m_minVersion;
            }
        } else if (pkg.m_maxVersion != null && !pkg.m_maxVersion.isBlank()) {
            return "<=" + pkg.m_maxVersion;
        } else {
            return "*";
        }
    }
}
