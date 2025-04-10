package org.knime.actionprovider;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.p2.engine.spi.ProvisioningAction;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

public class ExampleAction extends ProvisioningAction {
	private static final Bundle bundle = FrameworkUtil.getBundle(ProvisioningAction.class);

	public ExampleAction() {
	}

	@Override
	public IStatus execute(Map<String, Object> parameters) {

		try {
			var directory = (String) parameters.get("directory");
			var text = (String) parameters.get("text");

			var file = Paths.get(directory, "action_file.txt");
			Files.writeString(file, text);
		} catch (Throwable e) {
			e.printStackTrace();
			return error("Running action failed", e);
		}

		return Status.OK_STATUS;
	}

	@Override
	public IStatus undo(Map<String, Object> parameters) {
		// I guess we should delete the file again... But whatever...
		return Status.OK_STATUS;
	}

	private static Status error(final String message, final Throwable throwable) {
		return new Status(IStatus.ERROR, bundle.getSymbolicName(), message, throwable);
	}

}
