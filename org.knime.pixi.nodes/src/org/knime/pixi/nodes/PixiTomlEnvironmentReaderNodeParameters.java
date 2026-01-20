package org.knime.pixi.nodes;

import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import org.knime.conda.envbundling.environment.CondaEnvironmentRegistry;
import org.knime.core.node.NodeLogger;
import org.knime.core.webui.node.dialog.defaultdialog.internal.file.FileReaderWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.file.FileSelection;
import org.knime.core.webui.node.dialog.defaultdialog.internal.file.FileSelectionWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.file.SingleFileSelectionMode;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.layout.After;
import org.knime.node.parameters.layout.Layout;
import org.knime.node.parameters.updates.Effect;
import org.knime.node.parameters.updates.Effect.EffectType;
import org.knime.node.parameters.updates.EffectPredicate;
import org.knime.node.parameters.updates.EffectPredicateProvider;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.choices.ChoicesProvider;
import org.knime.node.parameters.widget.choices.Label;
import org.knime.node.parameters.widget.choices.StringChoicesProvider;
import org.knime.node.parameters.widget.choices.ValueSwitchWidget;

/**
 * Node Parameters for the Pixi TOML Environment Reader node
 *
 * @author Marc Lehner, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
@SuppressWarnings("restriction")
final class PixiTomlEnvironmentReaderNodeParameters implements NodeParameters {

    static final class SourceSelectionSection {
    }

    @After(SourceSelectionSection.class)
    static final class SingleFileSelectionSection {
    }

    @After(SourceSelectionSection.class)
    static final class BundledEnvironmentSelectionSection {
    }

    enum EnvironmentSource {
        @Label("File")
        FILE,
            @Label("Environment from the bundling folder")
        BUNDLING_ENVIRONMENT
    }

    @Widget(title = "Environment source",
        description = "Choose whether to select a pixi.toml file from the file system or use a bundled environment.")
    @Layout(SourceSelectionSection.class)
    @ValueReference(EnvironmentSourceRef.class)
    @ValueSwitchWidget
    EnvironmentSource m_environmentSource = EnvironmentSource.FILE;

    @Widget(title = "pixi.toml file",
        description = "Select the pixi.toml file to read the environment specification from.")
    @FileSelectionWidget(value = SingleFileSelectionMode.FILE)
    @FileReaderWidget(fileExtensions = {"toml"})
    @Layout(SingleFileSelectionSection.class)
    @Effect(predicate = IsFileSourceSelected.class, type = EffectType.SHOW)
    FileSelection m_tomlFile = new FileSelection();

    @Widget(title = "Bundled environment",
        description = "Select a pixi environment from the bundled environments directory.")
    @ChoicesProvider(BundledPixiEnvironmentChoicesProvider.class)
    @Layout(BundledEnvironmentSelectionSection.class)
    @Effect(predicate = IsBundledEnvironmentSourceSelected.class, type = EffectType.SHOW)
    String m_bundledEnvironment;

    interface EnvironmentSourceRef extends ParameterReference<EnvironmentSource> {
    }

    static final class IsFileSourceSelected implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(EnvironmentSourceRef.class).isOneOf(EnvironmentSource.FILE);
        }
    }

    static final class IsBundledEnvironmentSourceSelected implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(EnvironmentSourceRef.class).isOneOf(EnvironmentSource.BUNDLING_ENVIRONMENT);
        }
    }

    /**
     * Provider that lists all pixi environments from the bundling folder that contain a pixi.toml file.
     */
    static final class BundledPixiEnvironmentChoicesProvider implements StringChoicesProvider {

        private static final NodeLogger LOGGER =
            NodeLogger.getLogger(BundledPixiEnvironmentChoicesProvider.class);

        @Override
        public List<String> choices(final NodeParametersInput context) {
            try {
                // Use CondaEnvironmentRegistry to get all bundled environments
                // Then filter for those that contain a pixi.toml file
                return CondaEnvironmentRegistry.getEnvironments().values().stream()
                    .filter(env -> Files.exists(env.getPath().resolve("pixi.toml")))
                    .map(env -> env.getName())
                    .sorted()
                    .collect(Collectors.toList());

            } catch (Exception e) {
                LOGGER.error("Failed to list bundled pixi environments: " + e.getMessage(), e);
                return List.of();
            }
        }
    }
}
