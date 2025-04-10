package org.knime;

import org.knime.core.node.NodeLogger;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {
	
	private static final NodeLogger LOGGER = null;

	@Override
	public void start(BundleContext context) throws Exception {
		LOGGER.warn("ACTION CONSUMER PLUGIN ACTIVATED");
		// TODO check for the file
	}

	@Override
	public void stop(BundleContext context) throws Exception {
	}
}
