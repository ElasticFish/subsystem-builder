package org.glassfish.featureManage.featureAdd;

import org.glassfish.obrbuilder.ObrHandlerService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleReference;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.util.tracker.ServiceTracker;

public class Util {

	public static final long OBRHADNDLESERVICE_TIMEOUT = 1; // in ms
	
	public static ObrHandlerService getObrHandlerService()
			throws InvalidSyntaxException, InterruptedException {
		BundleContext bc = getBundleContext(Util.class);

		ServiceTracker st = null;

		st = new ServiceTracker(bc, bc.createFilter("(objectClass="
				+ ObrHandlerService.class.getName() + ")"), null);

		st.open();

		ObrHandlerService service = (ObrHandlerService) st
				.waitForService(Util.OBRHADNDLESERVICE_TIMEOUT);
		return service;
	}

	public static BundleContext getBundleContext(Class<?> clazz) {
		BundleContext bc = null;
		try {
			bc = BundleReference.class.cast(clazz.getClassLoader()).getBundle()
					.getBundleContext();
		} catch (ClassCastException cce) {
			throw cce;
		}

		return bc;
	}
}
