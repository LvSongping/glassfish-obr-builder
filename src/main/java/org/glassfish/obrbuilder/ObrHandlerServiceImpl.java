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

package org.glassfish.obrbuilder;

import static org.glassfish.obrbuilder.Logger.logger;

import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.logging.Level;

import org.apache.felix.bundlerepository.DataModelHelper;
import org.apache.felix.bundlerepository.Reason;
import org.apache.felix.bundlerepository.Repository;
import org.apache.felix.bundlerepository.RepositoryAdmin;
import org.apache.felix.bundlerepository.Resolver;
import org.apache.felix.bundlerepository.Resource;
import org.glassfish.obrbuilder.subsystem.Module;
import org.glassfish.obrbuilder.subsystem.Subsystem;
import org.glassfish.obrbuilder.subsystem.SubsystemXmlReaderWriter;
import org.glassfish.obrbuilder.subsystem.Subsystems;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.BundleReference;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import org.osgi.util.tracker.ServiceTracker;

/**
 * @author Sanjeeb.Sahoo@Sun.COM
 * @author TangYong(tangyong@cn.fujitsu.com)
 */
class ObrHandlerServiceImpl extends ServiceTracker implements ObrHandlerService {

	// We maintain our own repository list which we use during resolution
	// process.
	// That way, we are not affected by any repository added by user to a shared
	// instance of repository admin.
	private List<Repository> repositories = new ArrayList<Repository>();

	private SubsystemXmlReaderWriter subsystemParser = null;

	public ObrHandlerServiceImpl(BundleContext bctx) {
		super(bctx, RepositoryAdmin.class.getName(), null);
		
		open();

		subsystemParser = new SubsystemXmlReaderWriter();
	}
	
	public ObrHandlerServiceImpl() {
		super(getBundleContext(ObrHandlerServiceImpl.class), RepositoryAdmin.class.getName(), null);
		
		open();
		
		subsystemParser = new SubsystemXmlReaderWriter();
	}

	@Override
	public Object addingService(ServiceReference reference) {
		if (this.getTrackingCount() == 1) {
			logger.logp(
					Level.INFO,
					"ObrHandlerServiceImpl",
					"addingService",
					"We already have a repository admin service, so ignoring {0}",
					new Object[] { reference });
			return null; // we are not tracking this
		}
		RepositoryAdmin repositoryAdmin = (RepositoryAdmin) context
				.getService(reference);
		repositories.add(repositoryAdmin.getSystemRepository());
		repositories.add(repositoryAdmin.getLocalRepository());
		return super.addingService(reference);
	}

	@Override
	public void remove(ServiceReference reference) {
		super.remove(reference);
	}

	@Override
	public RepositoryAdmin getRepositoryAdmin() {
		assert (getTrackingCount() < 2);
		try {
			return (RepositoryAdmin) waitForService(Constants.OBR_TIMEOUT);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
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

	private synchronized void _setupRepository(File repoDir) throws Exception {
		Repository repository;
		File repoFile = getRepositoryFile(repoDir);
		final long tid = Thread.currentThread().getId();
		if (repoFile != null && repoFile.exists()) {
			long t = System.currentTimeMillis();
			repository = updateRepository(repoFile, repoDir);
			long t2 = System.currentTimeMillis();
			logger.logp(Level.INFO, "ObrHandlerServiceImpl",
					"_setupRepository",
					"Thread #{0}: updateRepository took {1} ms", new Object[] {
							tid, t2 - t });
		} else {
			long t = System.currentTimeMillis();
			repository = createRepository(repoFile, repoDir);
			long t2 = System.currentTimeMillis();
			logger.logp(Level.INFO, "ObrHandlerServiceImpl",
					"_setupRepository",
					"Thread #{0}: createRepository took {1} ms", new Object[] {
							tid, t2 - t });
		}
		repositories.add(repository);
		// We don't add repository to RepositoryAdmin, as we pass the list of
		// repositories to use in resolver().
		// final String repoUrl = repository.getURI();
		// logger.logp(Level.INFO, "ObrHandler", "_setupRepository",
		// "Thread #{0}: Adding repository = {1}",
		// new Object[]{tid, repoUrl});
		// long t = System.currentTimeMillis();
		// getRepositoryAdmin().addRepository(repository);
		// long t2 = System.currentTimeMillis();
		// logger.logp(Level.INFO, "ObrHandler", "_setupRepository",
		// "Thread #{0}: Adding repo took {1} ms",
		// new Object[]{tid, t2 - t});
	}

	//Used for creating and saving repo files
	//seeing https://github.com/tangyong/glassfish-obr-builder/issues/26
	//author/date: tangyong/2013.2.1
	private File getSubSystemRepositoryFile(String subsystemsName, String repoName) {
		String extn = ".xml";
		String prefix = "subsystems";
		
		//obtaining obr-builder's bundle context
		BundleContext ctx = getBundleContext(this.getClass());
		
		File bundleBaseStorage = ctx.getDataFile("");
		
		if ( !bundleBaseStorage.exists() ) {
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

	private File getRepositoryFile(File repoDir) {
		String extn = ".xml";
		String cacheDir = context.getProperty(Constants.HK2_CACHE_DIR);
		if (cacheDir == null) {
			return null; // caching is disabled, so don't do it.
		}

		// TangYong Added
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
	private Repository createRepository(File repoFile, File repoDir)
			throws IOException {
		return createRepository(repoFile, repoDir, true);
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
				logger.logp(Level.WARNING, "ObrHandlerServiceImpl",
						"createRepository", "{0} not an OSGi bundle", jar
								.toURI().toURL());
			} else {
				resources.add(r);
			}
		}
		Repository repository = dmh.repository(resources
				.toArray(new Resource[resources.size()]));
		logger.logp(Level.INFO, "ObrHandlerServiceImpl", "createRepository",
				"Created {0} containing {1} resources.", new Object[] {
						repoFile, resources.size() });
		if (repoFile != null && save) {
			saveRepository(repoFile, repository);
		}
		return repository;
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

	private Repository updateRepository(File repoFile, File repoDir)
			throws Exception {
		Repository repository = loadRepository(repoFile);
		if (isObsoleteRepo(repository, repoFile, repoDir)) {
			if (!repoFile.delete()) {
				throw new IOException("Failed to delete "
						+ repoFile.getAbsolutePath());
			}
			logger.logp(Level.INFO, "ObrHandlerServiceImpl",
					"updateRepository", "Recreating {0}",
					new Object[] { repoFile });
			repository = createRepository(repoFile, repoDir);
		}
		return repository;
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
				logger.logp(Level.INFO, "ObrHandlerServiceImpl",
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
			logger.logp(Level.INFO, "ObrHandlerServiceImpl", "isObsoleteRepo",
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

	@Override
	public synchronized Bundle deploy(Resource resource) {
		final Resolver resolver = getRepositoryAdmin().resolver(
				getRepositories());
		boolean resolved = resolve(resolver, resource);
		if (resolved) {
			final int flags = 0;
			resolver.deploy(flags);
			return getBundle(resource);
		} else {
			Reason[] reqs = resolver.getUnsatisfiedRequirements();
			logger.logp(Level.WARNING, "ObrHandlerServiceImpl", "deploy",
					"Unable to satisfy the requirements: {0}",
					new Object[] { Arrays.toString(reqs) });
			return null;
		}
	}

	private boolean resolve(final Resolver resolver, Resource resource) {
		resolver.add(resource);
		boolean resolved = resolver.resolve();
		logger.logp(Level.INFO, "ObrHandlerServiceImpl", "resolve",
				"At the end of first pass, resolver outcome is \n: {0}",
				new Object[] { getResolverOutput(resolver) });

		return resolved;
	}

	/* package */
	@Override
	public synchronized Bundle deploy(String name, String version) {
		Resource resource = findResource(name, version);
		if (resource == null) {
			logger.logp(Level.INFO, "ObrHandlerServiceImpl", "deploy",
					"No resource matching name = {0} and version = {1} ",
					new Object[] { name, version });
			return null;
		}
		if (resource.isLocal()) {
			return getBundle(resource);
		}
		return deploy(resource);
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
					"ObrHandlerServiceImpl",
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
					"ObrHandlerServiceImpl",
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
	public Subsystems deploySubsystems(String subSystemPath) throws IOException{
		return deploySubsystems(subSystemPath, null);
	}

	@Override
	public Subsystems deploySubsystems(String subSystemPath, String subSystemName) throws IOException{
		// defaultly, we start subsystem
		return deploySubsystems(subSystemPath, subSystemName, true);
	}

	@Override
	public Subsystems deploySubsystems(String subSystemPath, boolean start) throws IOException{
		return deploySubsystems(subSystemPath, null, start);
	}

	@Override
	public Subsystems deploySubsystems(String subSystemPath, String subSystemName,
			boolean start) throws IOException{
		// Currently, we only support local file system and in the future,
		// We will support more options, eg. Maven Repo
		File subSystemFile = new File(subSystemPath);

		if (!subSystemFile.exists()) {
			logger.logp(
					Level.SEVERE,
					"ObrHandlerServiceImpl",
					"deploySubsystems",
					"{0} is not exist, and please check your subsystems definition file!",
					new Object[] { subSystemPath });
			throw new RuntimeException(
					subSystemPath
							+ " is not exist, and please check your subsystems definition file!");
		}
		
        Subsystems subsystems = null;
		
		subsystems = subsystemParser.read(subSystemFile);
		deploy(subsystems, subSystemName, start);
		
		return subsystems;
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

	private Map<File, Repository> createUserDefinedRepos(String subsystemsName,
			List<org.glassfish.obrbuilder.subsystem.Repository> repos) throws IOException {
		Map<File, Repository> repoMap = new HashMap<File, Repository>();
		for (org.glassfish.obrbuilder.subsystem.Repository repo : repos) {
			String repoName = repo.getName();
			String repoPath = repo.getUri();
			File repoFile = getSubSystemRepositoryFile(subsystemsName,repoName);
			Repository repository = createRepository(repoFile,
					new File(repoPath), false);
			
			repoMap.put(repoFile, repository);
			repositories.add(repository);
		}
		
		return repoMap;
	}

	private void startSubsystem(List<Module> modules, List<Bundle> bundles) {
		if (modules.size() != 0) {
			// select modules which start flag is not false
			List<Module> startModules = new ArrayList<Module>();
			for (Module module : modules) {
				// if ignoring "start" attribute, currently, we consider the
				// case as not starting the module
				// needing to discuss with sahoo
				if (module.getStart().equalsIgnoreCase("true")) {
					startModules.add(module);
				}
			}

			if (startModules.size() != 0) {
				// Sort the startModules list based on start level
				Collections.sort(startModules, new Comparator<Module>() {
					public int compare(Module module, Module otherModule) {
						return module.getStartlevel().compareTo(
								otherModule.getStartlevel());
					}
				});

				// Test:TangYong
				for (Module module : startModules) {
					System.out.println("module name: " + module.getName()
							+ "   " + "module start level: "
							+ module.getStartlevel());
				}

				// start sorted modules
				for (Module module : startModules) {
					for (Bundle bundle : bundles) {
						if (module.getName().equalsIgnoreCase(
								bundle.getSymbolicName())) {
							try {
								bundle.start();
							} catch (BundleException e) {
								e.printStackTrace();
								logger.logp(
										Level.SEVERE,
										"ObrHandlerServiceImpl",
										"startSubsystem",
										"{0} can not be started normally!",
										new Object[] { bundle.getSymbolicName() });
								throw new RuntimeException(
										bundle.getSymbolicName()
												+ " is not exist, and please check your inputted subsystem name!");

							}
						}
					}
				}
			}
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

	@Override
	public Subsystems deploySubsystems(InputStream is) throws IOException{
		return deploySubsystems(is, true);
	}

	@Override
	public Subsystems deploySubsystems(InputStream is, boolean start) throws IOException{
		return deploySubsystems(is, null, true);
	}

	@Override
	public Subsystems deploySubsystems(InputStream is, String subSystemName) throws IOException{
		return deploySubsystems(is, subSystemName, true);
	}

	@Override
	public Subsystems deploySubsystems(InputStream is, String subSystemName,
			boolean start) throws IOException {

		Subsystems subsystems = null;
		
		subsystems = subsystemParser.read(is);
		deploy(subsystems, subSystemName, start);
		
		return subsystems;
	}

	private void deploy(Subsystems subsystems, String subSystemName,
			boolean start){
		try {
			// Firstly, we reload glassfish system obr called obr-modules.xml
			// from "com.sun.enterprise.hk2.cacheDir"
			String systemOBRPath = context.getProperty(Constants.HK2_CACHE_DIR);
			File systemOBRFile = new File(systemOBRPath,
					Constants.GF_SYSTEM_OBR_NAME);
			Repository systemRepo = loadRepository(systemOBRFile);
			// We add system obr repo into repositories
			repositories.add(systemRepo);

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
							"ObrHandlerServiceImpl",
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
			
			//creating user-defined obr defined subsystems definition file
			List<org.glassfish.obrbuilder.subsystem.Repository> repos = subsystems
					.getRepository();
			Map<File, Repository> repoMap = createUserDefinedRepos(subsystems.getName(), repos);

			for (Subsystem subsystem : list) {

				// Thirdly, we get Modules defined subsystems definition file
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
								"ObrHandlerServiceImpl",
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

				// if start parameter is set true, we start the subsystem
				// However, we also need to start modules according to subsystems
				// definition properly,
				// if the module's start is false, although start parameter is
				// true, we can not still start the module
				if (start) {
					startSubsystem(modules, bundles);
				}
				
				//Save Subsystems repo files and definition file into glassfish-obr-builder's storage
				saveSubsystemsRepos(repoMap);
				saveSubsystemsDef(subsystems);
			}
		} catch (Exception e) {
			logger.logp(
					Level.SEVERE,
					"ObrHandlerServiceImpl",
					"deploySubsystems",
					"Subsystems deployed failed, failed error msg={0}",
					new Object[] {e.getMessage()});
			
			throw new RuntimeException(e);
		}
	}

	@Override
	public List<Subsystems> listSubsystems() throws IOException {
		List<Subsystems> subsystems = new ArrayList<Subsystems>();
		
		String extn = ".xml";
		String prefix = "subsystems";
		BundleContext ctx = getBundleContext(this.getClass());
		File bundleBaseStorage = ctx.getDataFile("");
		
		if ( !bundleBaseStorage.exists() ) {
			return subsystems; // ToDo: needing to handle the case, and bundle has been breaked
		}

		File subsystemsBaseDir = new File(bundleBaseStorage, prefix);
		if (!subsystemsBaseDir.exists()) {
			return subsystems; // ToDo: needing to handle the case, and bundle has been breaked
		}
		
		File[] files = subsystemsBaseDir.listFiles();
		
		for(int i = 0; i < files.length; i++){
			File file = files[i];
			
			//find subsystems def file
			File defBaseDir = new File(file, "def");
			if (!defBaseDir.exists()) {
				continue;
			}

			File defFile = new File(defBaseDir, "subsystems" + extn);
			if (!defBaseDir.exists()) {
				continue;
			}
			
			subsystems.add(subsystemParser.read(defFile));
		}
		
		return subsystems;
	}

	@Override
	public Subsystems listSubsystems(String subSystemsName) throws IOException {
		Subsystems subsystems = null;
		
		String extn = ".xml";
		String prefix = "subsystems";
		
		BundleContext ctx = getBundleContext(this.getClass());
		File bundleBaseStorage = ctx.getDataFile("");
		
		if ( !bundleBaseStorage.exists() ) {
			return null; // ToDo: needing to handle the case, and bundle has been breaked
		}

		File subsystemsBaseDir = new File(bundleBaseStorage, prefix);
		if (!subsystemsBaseDir.exists()) {
			return null; // ToDo: needing to handle the case, and bundle has been breaked
		}
		
		File[] files = subsystemsBaseDir.listFiles();
		
		for(int i = 0; i < files.length; i++){
			File file = files[i];
			
			if (file.getName().equalsIgnoreCase(subSystemsName)){
				//find the subsystems def file
				File defBaseDir = new File(file, "def");
				if (!defBaseDir.exists()) {
					return null;
				}

				File defFile = new File(defBaseDir, "subsystems" + extn);
				if (!defBaseDir.exists()) {
					return null;
				}
				
				subsystems = subsystemParser.read(defFile);
			}
		}
		
		return subsystems;
	}
	
	public static BundleContext getBundleContext(Class<?> clazz) {
        BundleContext bc = null;
        try {
            bc = BundleReference.class
                            .cast(clazz.getClassLoader())
                            .getBundle().getBundleContext();
        } catch (ClassCastException cce) {
            throw cce;
        }
        
        return bc;
    }
}
