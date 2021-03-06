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

import static org.glassfish.subsystem.manager.core.Logger.logger;

import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;

import javax.inject.Inject;

import org.apache.felix.bundlerepository.Capability;
import org.apache.felix.bundlerepository.DataModelHelper;
import org.apache.felix.bundlerepository.Reason;
import org.apache.felix.bundlerepository.Repository;
import org.apache.felix.bundlerepository.RepositoryAdmin;
import org.apache.felix.bundlerepository.Resolver;
import org.apache.felix.bundlerepository.Resource;
import org.apache.felix.bundlerepository.impl.CapabilityImpl;
import org.apache.felix.bundlerepository.impl.LocalRepositoryImpl;
import org.apache.felix.bundlerepository.impl.RepositoryAdminImpl;
import org.apache.felix.bundlerepository.impl.RepositoryImpl;
import org.apache.felix.bundlerepository.impl.RequirementImpl;
import org.apache.felix.bundlerepository.impl.ResourceImpl;
import org.apache.felix.utils.log.Logger;
import org.glassfish.subsystem.manager.domain.Module;
import org.glassfish.subsystem.manager.domain.Subsystem;
import org.glassfish.subsystem.manager.domain.SubsystemXmlReaderWriter;
import org.glassfish.subsystem.manager.domain.Subsystems;
import org.jvnet.hk2.annotations.Optional;
import org.jvnet.hk2.annotations.Service;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleReference;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.Version;

/**
 * @author Sanjeeb.Sahoo@Sun.COM
 * @author TangYong(tangyong@cn.fujitsu.com)
 */
class SubsystemManagerServiceImpl implements SubsystemManagerService {

	// We maintain our own repository list which we use during resolution
	// process.
	// That way, we are not affected by any repository added by user to a shared
	// instance of repository admin.
	private List<Repository> repositories = new ArrayList<Repository>();

	private Future<List<HK2AnnotationDescriptor>> futureHK2AnnotationDescriptor = null;

	private BundleContext context;
	private RepositoryAdmin repoAdmin;

	private SubsystemXmlReaderWriter subsystemParser = null;
	
	private Logger obrLogger;

	public SubsystemManagerServiceImpl(BundleContext context) {
		this.context = context;
		
		obrLogger = new Logger(context);

		subsystemParser = new SubsystemXmlReaderWriter();
	}

	@Override
	public RepositoryAdmin getRepositoryAdmin() {
		if (repoAdmin == null) {
			repoAdmin = new RepositoryAdminImpl(context, obrLogger);
			repositories.add(repoAdmin.getSystemRepository());
		}

		return repoAdmin;
	}

	@Override
	public synchronized void addRepository(URI obrUri) throws Exception {
		if (isDirectory(obrUri)) {
			setupRepository(new File(obrUri), isSynchronous());
		} else {
			// TangYong Modified
			// If not Directory, we still need to generate obr xml file and
			// defaultly, generated obr xml file name is obr.xml
			Repository repo = getRepositoryAdmin().getHelper().repository(
					obrUri.toURL());
			saveRepository(getRepositoryFile(null), repo);
			repositories.add(repo);
		}
	}

	private boolean isDirectory(URI obrUri) {
		try {
			return new File(obrUri).isDirectory();
		} catch (Exception e) {
		}

		return false;
	}

	private void setupRepository(final File repoDir, boolean synchronous)
			throws Exception {
		if (synchronous) {
			_setupRepository(repoDir);
		} else {
			Executors.newSingleThreadExecutor().submit(new Runnable() {
				@Override
				public void run() {
					try {
						_setupRepository(repoDir);
					} catch (Exception e) {
						throw new RuntimeException(e); // TODO(Sahoo): Proper
														// Exception Handling
					}
				}
			});
		}
	}

	private boolean isSynchronous() {
		String property = context
				.getProperty(Constants.INITIALIZE_OBR_SYNCHRONOUSLY);
		// default is synchronous as we are not sure if we have covered every
		// race condition in asynchronous path
		return property == null
				|| Boolean.TRUE.toString().equalsIgnoreCase(property);
	}

	private synchronized void _setupRepository(final File repoDir)
			throws Exception {
		File repoFile = getRepositoryFile(repoDir);
		final long tid = Thread.currentThread().getId();
		if (repoFile != null && repoFile.exists()) {
			long t = System.currentTimeMillis();
			updateRepository(repoFile, repoDir);
			long t2 = System.currentTimeMillis();
			logger.logp(Level.INFO, "SubsystemManagerServiceImpl",
					"_setupRepository",
					"Thread #{0}: updateRepository took {1} ms", new Object[] {
							tid, t2 - t });
		} else {
			// Scanning the whole repo dir and finding hk2 related annotations
			futureHK2AnnotationDescriptor = Executors.newSingleThreadExecutor()
					.submit(new Callable<List<HK2AnnotationDescriptor>>() {
						@Override
						public List<HK2AnnotationDescriptor> call() {
							try {
								List<File> repoFiles = findAllJars(repoDir);
								return scanHK2Annotations(repoFiles,
										buildRepoClassLoader(repoFiles));
							} catch (Exception e) {
								throw new RuntimeException(e);
							}
						}
					});

			long t = System.currentTimeMillis();
			repoFile.createNewFile();
			createRepository(repoFile, repoDir);
			long t2 = System.currentTimeMillis();
			logger.logp(Level.INFO, "SubsystemManagerServiceImpl",
					"_setupRepository",
					"Thread #{0}: createRepository took {1} ms", new Object[] {
							tid, t2 - t });
		}
	}

	private void addHK2DepsToResources() {
		if (futureHK2AnnotationDescriptor != null) {
			List<HK2AnnotationDescriptor> hK2AnnotationDescriptor;
			try {
				hK2AnnotationDescriptor = futureHK2AnnotationDescriptor.get();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			} catch (ExecutionException e) {
				throw new RuntimeException(e);
			}

			if (hK2AnnotationDescriptor.size() != 0) {
				for (HK2AnnotationDescriptor hk2AnnoDesc : hK2AnnotationDescriptor) {
					String bundleSymbolicName = hk2AnnoDesc
							.getTargetBundleSymbolicName();
					String bundleVersion = hk2AnnoDesc.getTargetBundleVersion();
					Resource resource = findResource(bundleSymbolicName,
							bundleVersion);
					if (resource != null) {
						// adding export-service
						List<String> serviceClasses = hk2AnnoDesc
								.getContractClassNames();
						for (String serviceClazz : serviceClasses) {
							CapabilityImpl capability = new CapabilityImpl(
									Capability.SERVICE);
							capability.addProperty(Capability.SERVICE,
									serviceClazz);
							((ResourceImpl) resource).addCapability(capability);
						}

						// adding import-service
						List<HK2InjectMetadata> injectionFieldMetaDatas = hk2AnnoDesc
								.getInjectionFieldMetaDatas();
						for (HK2InjectMetadata injectionFieldMetaData : injectionFieldMetaDatas) {
							RequirementImpl ri = new RequirementImpl(
									Capability.SERVICE);
							String injectionFieldClassName = injectionFieldMetaData
									.getInjectionFieldClassName();
							ri.setFilter(createServiceFilter(injectionFieldClassName));
							ri.addText("Import Service "
									+ injectionFieldClassName);
							ri.setOptional(injectionFieldMetaData.getOptional());
							// in the future, will discuss and improve it
							String mult = "";
							ri.setMultiple(!"false".equalsIgnoreCase(mult));
							((ResourceImpl) resource).addRequire(ri);
						}
					}
				}
			}
		}

	}

	private static String createServiceFilter(String injectClazz) {
		StringBuffer filter = new StringBuffer();
		filter.append("(&(");
		filter.append(Capability.SERVICE);
		filter.append("=");
		filter.append(injectClazz);
		filter.append("))");

		return filter.toString();
	}

	private File getRepositoryFile(File repoDir) {
		String extn = ".xml";
		String cacheDir = context.getProperty(Constants.HK2_CACHE_DIR);
		if (cacheDir == null) {
			return null; // caching is disabled, so don't do it.
		}

		// Defaultly, if not specifying repoDir, we will use obr.xml file
		if (repoDir == null) {
			return new File(cacheDir, "obr" + extn);
		}

		return new File(cacheDir, Constants.OBR_FILE_NAME_PREFIX
				+ repoDir.getName() + extn);
	}

	/**
	 * Create a new Repository from a directory by recurssively traversing all
	 * the jar files found there.
	 * 
	 * @param repoFile
	 * @param repoDir
	 * @return
	 * @throws IOException
	 */
	private void createRepository(File repoFile, File repoDir)
			throws IOException {
		createRepository(repoFile, repoDir, true);
	}

	private List<HK2AnnotationDescriptor> scanHK2Annotations(
			List<File> repoFiles, ClassLoader cls) {
		// building class loader
		List<HK2AnnotationDescriptor> hK2AnnotationDescriptor = null;
		String bundleSymbolicName = null;
		String bundleVersion = null;

		for (File file : repoFiles) {
			try {
				JarFile jarFile = new JarFile(file);

				Manifest mf = jarFile.getManifest();
				if (mf == null) {
					// not a valid jar
					break;
				}

				bundleSymbolicName = mf.getMainAttributes().getValue(
						"Bundle-SymbolicName");
				if (bundleSymbolicName == null) {
					// not a valid OSGi bundle
					break;
				}

				bundleVersion = mf.getMainAttributes().getValue(
						"Bundle-Version");
				if (bundleVersion == null) {
					// not a valid OSGi bundle
					break;
				}

				Enumeration<?> e = jarFile.entries();

				HK2AnnotationDescriptor hk2AnnoDesc = null;

				while (e.hasMoreElements()) {
					JarEntry je = (JarEntry) e.nextElement();

					if (je.isDirectory() || !je.getName().endsWith(".class")) {
						continue;
					}

					// -6 because of .class
					String className = je.getName().substring(0,
							je.getName().length() - 6);
					className = className.replaceAll("/", "\\.");
					Class<?> clazz = null;
					try {
						clazz = cls.loadClass(className);
					} catch (ClassNotFoundException ex) {
						logger.logp(Level.WARNING, "SubsystemManagerServiceImpl",
								"scanHK2Annotations",
								"Loading Class: {0} from Bundle: {1} failed.",
								new Object[] { className, bundleSymbolicName });
						continue;
					} catch (NoClassDefFoundError ex1) {
						logger.logp(Level.WARNING, "SubsystemManagerServiceImpl",
								"scanHK2Annotations",
								"Loading Class: {0} from Bundle: {1} failed.",
								new Object[] { className, bundleSymbolicName });
						continue;
					}

					// first, scanning class
					Annotation hk2ServiceAnnotation = clazz
							.getAnnotation(Service.class);

					if (hk2ServiceAnnotation == null) {
						// scanning must live in hk2 world, otherwise, we ignore
						// it.
						continue;
					}

					// the class has a @Service annotation
					// and we build a HK2AnnotationDescriptor instance
					if (hk2AnnoDesc == null) {
						hk2AnnoDesc = new HK2AnnotationDescriptor(
								bundleSymbolicName, bundleVersion);
					}

					hk2AnnoDesc.getContractClassNames().add(
							clazz.getCanonicalName());

					// then, scanning fields for @Inject and @Optional
					Field[] fields = clazz.getDeclaredFields();
					for (Field field : fields) {
						Annotation[] injectAnnotations = field.getAnnotations();
						boolean isInject = false;
						boolean isOptional = false;
						for (Annotation annotation : injectAnnotations) {
							if (annotation.annotationType()
									.equals(Inject.class)) {
								isInject = true;
							} else {
								if (annotation.annotationType().equals(
										Optional.class)) {
									isOptional = true;
								}
							}
						}

						if (isInject) {
							HK2InjectMetadata injectMetadata = new HK2InjectMetadata(
									field.getType().getCanonicalName(),
									isOptional);
							hk2AnnoDesc.getInjectionFieldMetaDatas().add(
									injectMetadata);
						}
					}
				}

				if (hk2AnnoDesc != null) {
					if (hK2AnnotationDescriptor == null) {
						hK2AnnotationDescriptor = new ArrayList<HK2AnnotationDescriptor>();
					}
					hK2AnnotationDescriptor.add(hk2AnnoDesc);
				}

				if (jarFile != null) {
					jarFile.close();
				}

			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		return hK2AnnotationDescriptor;

	}

	private URLClassLoader buildRepoClassLoader(List<File> repoFiles) {
		URL[] urls = new URL[repoFiles.size()];
		URLClassLoader cls = null;
		for (int i = 0; i < repoFiles.size(); i++) {
			try {
				urls[i] = new URL("jar:file:"
						+ repoFiles.get(i).getAbsolutePath() + "!/");
			} catch (MalformedURLException e) {
				e.printStackTrace();
			}
			cls = URLClassLoader.newInstance(urls, this.getClass()
					.getClassLoader());
		}
		return cls;
	}

	private void saveRepository(File repoFile, Repository repository)
			throws IOException {
		assert (repoFile != null);

		final FileWriter writer = new FileWriter(repoFile);

		getRepositoryAdmin().getHelper().writeRepository(repository, writer);
		writer.flush();
	}

	private Repository loadRepository(File repoFile) throws Exception {
		assert (repoFile != null);
		return getRepositoryAdmin().getHelper().repository(
				repoFile.toURI().toURL());
	}

	private void updateRepository(File repoFile, final File repoDir)
			throws Exception {
		Repository repository = loadRepository(repoFile);
		repositories.add(repository);
		if (isObsoleteRepo(repository, repoFile, repoDir)) {
			// scanning obsoleted jars and new jars in this repo
			final List<File> updatedJarList = obtainUpdatedJars(repository,
					repoFile, repoDir);

			// Scanning the updatedJars and finding hk2 related annotations
			futureHK2AnnotationDescriptor = Executors.newSingleThreadExecutor()
					.submit(new Callable<List<HK2AnnotationDescriptor>>() {
						@Override
						public List<HK2AnnotationDescriptor> call() {
							try {
								List<File> repoFiles = findAllJars(repoDir);
								return scanHK2Annotations(updatedJarList,
										buildRepoClassLoader(repoFiles));
							} catch (Exception e) {
								throw new RuntimeException(e);
							}
						}
					});

			if (!repoFile.delete()) {
				throw new IOException("Failed to delete "
						+ repoFile.getAbsolutePath());
			}
			logger.logp(Level.INFO, "SubsystemManagerServiceImpl",
					"updateRepository", "Recreating {0}",
					new Object[] { repoFile });

			DataModelHelper dmh = getRepositoryAdmin().getHelper();

			if (updatedJarList.size() != 0) {
				for (File updatedJar : updatedJarList) {
					JarFile jarFile;
					Manifest mf;
					try {
						jarFile = new JarFile(updatedJar);
						mf = jarFile.getManifest();
					} catch (IOException ex) {
						throw new RuntimeException(ex);
					}

					if (mf == null) {
						// not a valid jar
						break;
					}
					String bundleSymbolicName = mf.getMainAttributes()
							.getValue("Bundle-SymbolicName");
					if (bundleSymbolicName == null) {
						// not a valid OSGi bundle
						break;
					}
					String bundleVersion = mf.getMainAttributes().getValue(
							"Bundle-Version");
					if (bundleVersion == null) {
						// not a valid OSGi bundle
						break;
					}

					// First, we try to find whether having existing resource
					// with the same
					// Bundle-SymbolicName and Bundle-Version
					Resource resource = findResource(bundleSymbolicName,
							bundleVersion);
					if (resource != null) {
						// we must remove the resource and create a new resource
						// based on bundle url
						// because RepositoryImpl and API have not offered such
						// an api
						// only using java reflect api to get private
						// m_resourceSet field
						// lately, I will file an request into felix community
						Field field = repository.getClass().getDeclaredField(
								"m_resourceSet");
						field.setAccessible(true);
						HashSet<?> resourceSet = (HashSet<?>) field
								.get(repository);
						resourceSet.remove(resource);
					}

					// we create a new resource
					Resource newResource = dmh.createResource(updatedJar
							.toURI().toURL());
					((RepositoryImpl) repository).addResource(newResource);
				}

				// Then, adding hk2 deps into resources
				addHK2DepsToResources();
			}

			// finally, we must synchronize with repo dir in case that
			// 1: some resources have left repo dir
			// 2: some resources have been stale resources although bundle jar
			// name is not changed
			synchronizeWithRepoDir(repoDir, repository, updatedJarList);

			repoFile.createNewFile();

			// finally, save Repository
			saveRepository(repoFile, repository);
		}
	}

	private void synchronizeWithRepoDir(File repoDir, Repository repository,
			List<File> updatedJarList) {
		Resource[] resources = repository.getResources();
		for (int resIdx = 0; (resources != null) && (resIdx < resources.length); resIdx++) {
			Resource resource = resources[resIdx];
			String path = resource.getURI();
			// here, we must build a new URI because path obtained from obr xml
			// file is not a valid path uri.
			File file = null;
			boolean isDeleted = false;
			try {
				file = new File(new URI(path));
				if (!file.exists()) {
					// remove the resource
					isDeleted = true;
				} else {
					String bundleSymbolicName = resource.getSymbolicName();
					String bundleVersion = resource.getVersion().toString();

					// we find whether match in updated jar list
					for (File updatedJar : updatedJarList) {
						JarFile jarFile = new JarFile(updatedJar);
						Manifest mf = jarFile.getManifest();
						String bsn = mf.getMainAttributes().getValue(
								"Bundle-SymbolicName");
						String bv = mf.getMainAttributes().getValue(
								"Bundle-Version");

						if (updatedJar.getAbsolutePath().equals(
								file.getAbsolutePath())) {
							if (bsn.equals(bundleSymbolicName)
									&& bv.equals(bundleVersion)) {
								isDeleted = false;
								break;
							}

							isDeleted = true;
						}
					}
				}
			} catch (URISyntaxException e) {
				isDeleted = true;
			} catch (IOException e1) {
				throw new RuntimeException(e1);
			}

			if (isDeleted) {
				try {
					Field field = repository.getClass().getDeclaredField(
							"m_resourceSet");
					field.setAccessible(true);
					HashSet<?> resourceSet = (HashSet<?>) field.get(repository);
					resourceSet.remove(resource);
					field = repository.getClass().getDeclaredField(
							"m_resources");
					field.setAccessible(true);
					field.set(repository, null);
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}
			}
		}

	}

	private List<File> obtainUpdatedJars(Repository repository, File repoFile,
			File repoDir) {
		List<File> updatedJarList = new ArrayList<File>();
		long lastModifiedTime = repoFile.lastModified();
		for (File jar : findAllJars(repoDir)) {
			if (jar.lastModified() > lastModifiedTime) {
				updatedJarList.add(jar);
			} else {
				// comparing size
				JarFile jarFile;
				Manifest mf;
				try {
					jarFile = new JarFile(jar);
					mf = jarFile.getManifest();
				} catch (IOException ex) {
					throw new RuntimeException(ex);
				}

				if (mf == null) {
					// not a valid jar
					break;
				}
				String bundleSymbolicName = mf.getMainAttributes().getValue(
						"Bundle-SymbolicName");
				if (bundleSymbolicName == null) {
					// not a valid OSGi bundle
					break;
				}
				String bundleVersion = mf.getMainAttributes().getValue(
						"Bundle-Version");
				if (bundleVersion == null) {
					// not a valid OSGi bundle
					break;
				}

				Resource resource = findResource(bundleSymbolicName,
						bundleVersion);
				if (resource == null) {
					// 1: having more higher version bundle
					// 2: new bundle is added into repo
					updatedJarList.add(jar);
				} else {
					// comparing size
					if (jar.length() != resource.getSize()) {
						updatedJarList.add(jar);
					}
				}
			}
		}

		return updatedJarList;
	}

	private boolean isObsoleteRepo(Repository repository, File repoFile,
			File repoDir) {
		// TODO(Sahoo): Revisit this...
		// This method assumes that the cached repoFile has been created before
		// a newer jar is created.
		// So, this method does not always detect stale repoFile. Imagine the
		// following situation:
		// time t1: v1 version of jar is released.
		// time t2: v2 version of jar is released.
		// time t3: repo.xml is populated using v1 version of jar, so repo.xml
		// records a timestamp of t3 > t2.
		// time t4: v2 version of jar is unzipped on modules/ and unzip
		// maintains the timestamp of jar as t2.
		// Next time when we compare timestamp, we will see that repo.xml is
		// newer than this jar, when it is not.
		// So, we include a size check. We go for the total size check...

		long lastModifiedTime = repoFile.lastModified();
		// optimistic: see if the repoDir has been touched. dir timestamp
		// changes when files are added or removed.
		if (repoDir.lastModified() > lastModifiedTime) {
			return true;
		}

		long totalSize = 0;
		// now compare timestamp of each jar and take a sum of size of all jars.
		for (File jar : findAllJars(repoDir)) {
			if (jar.lastModified() > lastModifiedTime) {
				logger.logp(Level.INFO, "SubsystemManagerServiceImpl",
						"isObsoleteRepo", "{0} is newer than {1}",
						new Object[] { jar, repoFile });
				return true;
			}
			totalSize += jar.length();
		}
		// time stamps didn't identify any difference, so check sizes. The
		// probabibility of sizes of all jars being same
		// when some jars have changed is very very low.
		for (Resource r : repository.getResources()) {
			totalSize -= r.getSize();
		}
		if (totalSize != 0) {
			logger.logp(Level.INFO, "SubsystemManagerServiceImpl", "isObsoleteRepo",
					"Change in size detected by {0} bytes",
					new Object[] { totalSize });
			return true;
		}
		return false;
	}

	private List<File> findAllJars(File repo) {
		final List<File> files = new ArrayList<File>();
		repo.listFiles(new FileFilter() {
			@Override
			public boolean accept(File pathname) {
				if (pathname.isDirectory()) {
					pathname.listFiles(this);
				} else if (pathname.getName().endsWith("jar")) {
					files.add(pathname);
				}
				return true;
			}
		});
		return files;
	}

	private boolean resolve(final Resolver resolver, Resource resource) {
		resolver.add(resource);
		boolean resolved = resolver.resolve();
		logger.logp(Level.INFO, "SubsystemManagerServiceImpl", "resolve",
				"At the end of first pass, resolver outcome is \n: {0}",
				new Object[] { getResolverOutput(resolver) });

		return resolved;
	}

	private Bundle getBundle(Resource resource) {
		for (Bundle b : context.getBundles()) {
			final String bsn = b.getSymbolicName();
			final Version bv = b.getVersion();
			final String rsn = resource.getSymbolicName();
			final Version rv = resource.getVersion();
			boolean versionMatching = (rv == bv)
					|| (rv != null && rv.equals(bv));
			boolean nameMatching = (bsn == rsn)
					|| (bsn != null && bsn.equals(rsn));
			if (nameMatching && versionMatching)
				return b;
		}
		return null;
	}

	private Resource findResource(String name, String version) {
		final RepositoryAdmin repositoryAdmin = getRepositoryAdmin();
		if (repositoryAdmin == null) {
			logger.logp(
					Level.WARNING,
					"SubsystemManagerServiceImpl",
					"findResource",
					"OBR is not yet available, so can't find resource with name = {0} and version = {1} from repository",
					new Object[] { name, version });
			return null;
		}
		String s1 = "(symbolicname=" + name + ")";
		String s2 = "(version=" + version + ")";
		String query = (version != null) ? "(&" + s1 + s2 + ")" : s1;
		try {
			Resource[] resources = discoverResources(query);
			logger.logp(
					Level.INFO,
					"SubsystemManagerServiceImpl",
					"findResource",
					"Using the first one from the list of {0} discovered bundles shown below: {1}",
					new Object[] { resources.length, Arrays.toString(resources) });
			return resources.length > 0 ? resources[0] : null;
		} catch (InvalidSyntaxException e) {
			throw new RuntimeException(e); // TODO(Sahoo): Proper Exception
											// Handling
		}
	}

	private Resource[] discoverResources(String filterExpr)
			throws InvalidSyntaxException {
		// TODO(Sahoo): File a bug against Obr to add a suitable method to
		// Repository interface.
		// We can't use the following method, because we can't rely on the
		// RepositoryAdmin to have the correct
		// list of repositories. So, we do the discovery ourselves.
		// return getRepositoryAdmin().discoverResources(query);
		Filter filter = filterExpr != null ? getRepositoryAdmin().getHelper()
				.filter(filterExpr) : null;
		Resource[] resources;
		Repository[] repos = getRepositories();
		List<Resource> matchList = new ArrayList<Resource>();
		for (int repoIdx = 0; (repos != null) && (repoIdx < repos.length); repoIdx++) {
			resources = repos[repoIdx].getResources();
			for (int resIdx = 0; (resources != null)
					&& (resIdx < resources.length); resIdx++) {
				Properties dict = new Properties();
				dict.putAll(resources[resIdx].getProperties());
				if (filter == null || filter.match(dict)) {
					matchList.add(resources[resIdx]);
				}
			}
		}

		return matchList.toArray(new Resource[matchList.size()]);
	}

	private StringBuffer getResolverOutput(Resolver resolver) {
		Resource[] addedResources = resolver.getAddedResources();
		Resource[] requiredResources = resolver.getRequiredResources();
		Resource[] optionalResources = resolver.getOptionalResources();
		Reason[] unsatisfiedRequirements = resolver
				.getUnsatisfiedRequirements();
		StringBuffer sb = new StringBuffer("Added resources: [");
		for (Resource r : addedResources) {
			sb.append("\n").append(r.getSymbolicName()).append(", ")
					.append(r.getVersion()).append(", ").append(r.getURI());
		}
		sb.append("]\nRequired Resources: [");
		for (Resource r : requiredResources) {
			sb.append("\n").append(r.getURI());
		}

		for (Resource r : optionalResources) {
			sb.append("\n").append(r.getURI());
		}
		sb.append("]\nUnsatisfied requirements: [");
		for (Reason r : unsatisfiedRequirements) {
			sb.append("\n").append(r.getRequirement());
		}
		sb.append("]");
		return sb;
	}

	private Repository[] getRepositories() {
		return repositories.toArray(new Repository[repositories.size()]);
	}

	@Override
	public Subsystems deploySubsystems(String subSystemDefFile)
			throws IOException {
		return deploySubsystems(subSystemDefFile, null);
	}

	@Override
	public Subsystems deploySubsystems(String subSystemDefFile,
			String subSystemName) throws IOException {
		// Currently, we only support local file system and in the future,
		// We will support more options, eg. Maven Repo
		File file = new File(subSystemDefFile);

		if (!file.exists()) {
			logger.logp(
					Level.SEVERE,
					"SubsystemManagerServiceImpl",
					"deploySubsystems",
					"{0} is not exist, and please check your subsystems definition file!",
					new Object[] { subSystemDefFile });
			throw new RuntimeException(
					subSystemDefFile
							+ " is not exist, and please check your subsystems definition file!");
		}

		Subsystems subsystems = null;

		subsystems = subsystemParser.read(file);
		deploy(subsystems, subSystemName);

		return subsystems;
	}

	private void deploy(Subsystems subsystems, String subSystemName) {
		try {
			// Firstly, we reload glassfish system obr called obr-modules.xml
			// from "com.sun.enterprise.hk2.cacheDir"
			String systemOBRPath = context.getProperty(Constants.HK2_CACHE_DIR);
			File systemOBRFile = new File(systemOBRPath,
					Constants.GF_SYSTEM_OBR_NAME);
			Repository systemRepo = loadRepository(systemOBRFile);
			// We add system obr repo into repositories
			repositories.add(systemRepo);
			
			//[TangYong]2013.12.06
			//fixing https://github.com/ElasticFish/subsystem-builder/issues/20
			repositories.add(new LocalRepositoryImpl(context, obrLogger));

			// Secondly, we create user-defined obr defined subsystem definition
			// file and we need to select right subsystem name passed by
			// parameter
			List<Subsystem> list = null;
			if (subSystemName == null) {
				// get all subsystem from subsystem definition file
				list = subsystems.getSubsystem();
			} else {
				Subsystem subsystem = getSubsystem(subsystems, subSystemName);
				if (subsystem == null) {
					logger.logp(
							Level.SEVERE,
							"SubsystemManagerServiceImpl",
							"deploySubsystems",
							"{0} is not exist, and please check your inputted subsystem name!",
							new Object[] { subSystemName });
					throw new RuntimeException(
							subSystemName
									+ " is not exist, and please check your inputted subsystem name!");
				}

				list = new ArrayList<Subsystem>();
				list.add(subsystem);
			}

			// creating user-defined obr defined subsystems definition file
			List<org.glassfish.subsystem.manager.domain.Repository> repos = subsystems
					.getRepository();
			Map<File, Repository> repoMap = createUserDefinedRepos(
					subsystems.getName(), repos);

			for (Subsystem subsystem : list) {

				// Thirdly, we get Modules defined in subsystems definition file
				List<Module> modules = subsystem.getModule();
				List<Bundle> bundles = new ArrayList<Bundle>();
				for (Module module : modules) {
					// fixing
					// https://github.com/tangyong/glassfish-obr-builder/issues/20
					// author/date: tangyong/2013.01.30
					Bundle bundle = deploy(module.getName(),
							module.getVersion());

					// fixing
					// https://github.com/tangyong/glassfish-obr-builder/issues/22
					// author/date: tangyong/2013.01.30
					if (bundle == null) {
						// 1) in server.log, output error info
						// 2) throw exception and breaking subsystem deploy
						logger.logp(
								Level.SEVERE,
								"SubsystemManagerServiceImpl",
								"deploySubsystems",
								"No module or bundle matching name = {0} and version = {1} ",
								new Object[] { module.getName(),
										module.getVersion() });
						throw new RuntimeException("Subsystem: "
								+ subsystem.getName() + " deploying failed. "
								+ "No module or bundle matching name = "
								+ module.getName() + " and version = "
								+ module.getVersion());
					}

					bundles.add(bundle);
				}

				// Save Subsystems repo files and definition file into
				// glassfish-obr-builder's storage
				saveSubsystemsRepos(repoMap);
				saveSubsystemsDef(subsystems);
			}
		} catch (Exception e) {
			logger.logp(Level.SEVERE, "SubsystemManagerServiceImpl",
					"deploySubsystems",
					"Subsystems deployed failed, failed error msg={0}",
					new Object[] { e.getMessage() });

			throw new RuntimeException(e);
		}
	}

	private Subsystem getSubsystem(Subsystems subsystems, String subSystemName) {
		Subsystem result = null;
		List<Subsystem> syslist = subsystems.getSubsystem();
		for (Subsystem sys : syslist) {
			if (sys.getName().equalsIgnoreCase(subSystemName)) {
				result = sys;
				break;
			}
		}

		return result;
	}

	private Map<File, Repository> createUserDefinedRepos(String subsystemsName,
			List<org.glassfish.subsystem.manager.domain.Repository> repos)
			throws IOException {
		Map<File, Repository> repoMap = new HashMap<File, Repository>();
		for (org.glassfish.subsystem.manager.domain.Repository repo : repos) {
			String repoName = repo.getName();
			String repoPath = repo.getUri();
			File repoFile = getSubSystemRepositoryFile(subsystemsName, repoName);
			Repository repository = createRepository(repoFile, new File(
					repoPath), false);

			repoMap.put(repoFile, repository);
			repositories.add(repository);
		}

		return repoMap;
	}
	
	//TangYong Added a overridden method to use for deploying subsystem
		/**
		 * Create a new Repository from a directory by recurssively traversing all
		 * the jar files found there.
		 * 
		 * @param repoFile
		 * @param repoDir
		 * @return
		 * @throws IOException
		 */
		private Repository createRepository(File repoFile, File repoDir, boolean save)
				throws IOException {
			DataModelHelper dmh = getRepositoryAdmin().getHelper();
			List<Resource> resources = new ArrayList<Resource>();
			for (File jar : findAllJars(repoDir)) {
				Resource r = dmh.createResource(jar.toURI().toURL());

				if (r == null) {
					logger.logp(Level.WARNING, "SubsystemManagerServiceImpl",
							"createRepository", "{0} not an OSGi bundle", jar
									.toURI().toURL());
				} else {
					resources.add(r);
				}
			}
			Repository repository = dmh.repository(resources
					.toArray(new Resource[resources.size()]));
			logger.logp(Level.INFO, "SubsystemManagerServiceImpl", "createRepository",
					"Created {0} containing {1} resources.", new Object[] {
							repoFile, resources.size() });
			if (repoFile != null && save) {
				saveRepository(repoFile, repository);
			}
			return repository;
		}

	// Used for creating and saving repo files
	// seeing https://github.com/tangyong/glassfish-obr-builder/issues/26
	// author/date: tangyong/2013.2.1
	private File getSubSystemRepositoryFile(String subsystemsName,
			String repoName) {
		String extn = ".xml";
		String prefix = "subsystems";

		// obtaining obr-builder's bundle context
		BundleContext ctx = getBundleContext(this.getClass());

		File bundleBaseStorage = ctx.getDataFile("");

		if (!bundleBaseStorage.exists()) {
			return null; // caching is disabled, so don't do it.
		}

		File subsystemsBaseDir = new File(bundleBaseStorage, prefix);
		if (!subsystemsBaseDir.exists()) {
			subsystemsBaseDir.mkdirs();
		}

		File subsystemsDir = new File(subsystemsBaseDir, subsystemsName);
		if (!subsystemsDir.exists()) {
			subsystemsDir.mkdirs();
		}

		File repoBaseDir = new File(subsystemsDir, "repos");
		if (!repoBaseDir.exists()) {
			repoBaseDir.mkdirs();
		}

		return new File(repoBaseDir, Constants.OBR_FILE_NAME_PREFIX + repoName
				+ extn);
	}

	private static BundleContext getBundleContext(Class<?> clazz) {
		BundleContext bc = null;
		try {
			bc = BundleReference.class.cast(clazz.getClassLoader()).getBundle()
					.getBundleContext();
		} catch (ClassCastException cce) {
			throw cce;
		}

		return bc;
	}
	
	private synchronized Bundle deploy(String name, String version) {
		Resource resource = findResource(name, version);
		if (resource == null) {
			logger.logp(Level.INFO, "SubsystemManagerServiceImpl", "deploy",
					"No resource matching name = {0} and version = {1} ",
					new Object[] { name, version });
			return null;
		}
		if (resource.isLocal()) {
			return getBundle(resource);
		}
		
		return deploy(resource);
	}
	
	private synchronized Bundle deploy(Resource resource) {
		final Resolver resolver = getRepositoryAdmin().resolver(
				getRepositories());
		boolean resolved = resolve(resolver, resource);
		if (resolved) {
			final int flags = 0;
			resolver.deploy(flags);
			return getBundle(resource);
		} else {
			Reason[] reqs = resolver.getUnsatisfiedRequirements();
			logger.logp(Level.WARNING, "SubsystemManagerServiceImpl", "deploy",
					"Unable to satisfy the requirements: {0}",
					new Object[] { Arrays.toString(reqs) });
			return null;
		}
	}
	
	private void saveSubsystemsDef(Subsystems subsystems) throws IOException {
		String extn = ".xml";
		String prefix = "subsystems";
		
		BundleContext ctx = getBundleContext(this.getClass());
		File bundleBaseStorage = ctx.getDataFile("");

		File subsystemsBaseDir = new File(bundleBaseStorage, prefix);
		if (!subsystemsBaseDir.exists()) {
			subsystemsBaseDir.mkdirs();
		}
		
		File subsystemsDir = new File(subsystemsBaseDir, subsystems.getName());
		if (!subsystemsDir.exists()) {
			subsystemsDir.mkdirs();
		}
		
		File defBaseDir = new File(subsystemsDir, "def");
		if (!defBaseDir.exists()) {
			defBaseDir.mkdirs();
		}

		File defFile = new File(defBaseDir, "subsystems" + extn);
		
		subsystemParser.write(subsystems, defFile);	
	}

	private void saveSubsystemsRepos(Map<File, Repository> repoMap) throws IOException {
		if (repoMap.size() != 0){
			Set<File> keys = repoMap.keySet();
			for(File key : keys){
				saveRepository(key, repoMap.get(key));
			}
		}		
	}

}
