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
package org.glassfish.subsystem.command;

import com.sun.enterprise.config.serverbeans.*;

import java.io.File;

import org.glassfish.api.I18n;
import org.glassfish.api.Param;
import org.glassfish.api.admin.*;
import org.glassfish.hk2.api.PerLookup;
import org.jvnet.hk2.annotations.Service;

import org.glassfish.subsystem.manager.core.SubsystemManagerService;

/**
 * 
 * @author Jeremy Lv
 * @author Tang Yong
 */
@Service(name = "add-subsystem")
@I18n("add.subsystem")
@PerLookup
@ExecuteOn({ RuntimeType.DAS })
@RestEndpoints({ @RestEndpoint(configBean = Domain.class, opType = RestEndpoint.OpType.POST, path = "add-subsystem", description = "Subsystem Add") })
public class AddSubsystemCommand implements AdminCommand {

	@Param(optional = true)
	File path = null;

	@Param(primary = true)
	String name = null;
	
	@Override
	public void execute(AdminCommandContext context) {
		try {
			SubsystemManagerService service = CommandUtil
					.getObrHandlerService();

			if (path == null) {
				// Defaultly, this will add system-wide subsystem for GlassFish
				// itself
				// then, we will search subsystem definition file in
				// ${com.sun.aas.installRootURI}/config/
				String defaultPath = CommandUtil.getDefaultSubsystemDef();
				if (defaultPath == null) {
					throw new RuntimeException(
							"default subsystem def file for GF is not set");
				}

				service.deploySubsystems(defaultPath, name);
			} else {
				service.deploySubsystems(path.getCanonicalPath(), name);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
