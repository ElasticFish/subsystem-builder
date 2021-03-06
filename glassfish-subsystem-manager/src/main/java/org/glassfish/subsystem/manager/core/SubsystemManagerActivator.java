/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.subsystem.manager.core;

import java.net.URI;
import java.util.logging.Level;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import static org.glassfish.subsystem.manager.core.Logger.logger;

/**
 * @author TangYong(tangyong@cn.fujitsu.com)
 */
public class SubsystemManagerActivator implements BundleActivator {

	private BundleContext bctx;
	ServiceRegistration registration = null;

	public void start(BundleContext context) throws Exception {
		this.bctx = context;

		String gfModuleRepoPath = context
				.getProperty(Constants.GF_MODULE_REPOSITORIES);

		createGFObrRepository(gfModuleRepoPath);

		// Register ObrHandlerServiceFactory into OSGi Registry
		registration = context.registerService(
				SubsystemManagerService.class.getName(),
				new SubsystemManagerServiceFactory(), null);
	}

	public void stop(BundleContext context) throws Exception {
		if (registration != null) {
			registration.unregister();
			registration = null;
		}
	}

	private void createGFObrRepository(String repositoryUris) {
		if (repositoryUris != null) {
			for (String s : repositoryUris.split("\\s")) {
				URI repoURI = URI.create(s);
				SubsystemManagerService subsystemManagerService = new SubsystemManagerServiceImpl(
						bctx);
				try {
					subsystemManagerService.addRepository(repoURI);
				} catch (Exception e) {
					e.printStackTrace();
					logger.logp(
							Level.SEVERE,
							"ObrBuilderActivator",
							"createGFObrRepository",
							"Creating Glassfish OBR Repository failed, RepoURI: {0}",
							new Object[] { repoURI });
				}
			}
		}
	}
}
