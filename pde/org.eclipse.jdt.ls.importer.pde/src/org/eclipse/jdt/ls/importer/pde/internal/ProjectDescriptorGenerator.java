/*******************************************************************************
 * Copyright (c) 2017 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Microsoft Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.importer.pde.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.eclipse.core.internal.events.BuildCommand;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IAccessRule;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IRuntimeClasspathEntry;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ProjectUtils;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.LocalProjectScanner;
import org.eclipse.m2e.core.project.MavenProjectInfo;
import org.eclipse.pde.internal.core.natures.PDE;
import org.eclipse.pde.internal.launching.PDELaunchingPlugin;

@SuppressWarnings("restriction")
public class ProjectDescriptorGenerator {
	private static final String PDE_SCHEMA_BUILDER = "org.eclipse.pde.SchemaBuilder";
	private static final String PDE_MANIFEST_BUILDER = "org.eclipse.pde.ManifestBuilder";

	public boolean applies(IPath folder) {
		if(!folder.append("META-INF").toFile().exists()) {
			return false;
		}

		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		IProject project = loadProject(folder, workspace);
		if (!project.exists()) {
			return true;
		}

		try {
			if (!project.hasNature(JavaCore.NATURE_ID) || !project.hasNature(PDE.PLUGIN_NATURE)) {
				return true;
			}
		} catch (CoreException e) {
			JavaLanguageServerPlugin.log(e);
			return true;
		}
		return false;
	}

	public void importAsProject(IPath folder, SubMonitor monitor) throws CoreException {
		try {
			IWorkspace workspace = ResourcesPlugin.getWorkspace();
			IProject project = loadProject(folder, workspace);
			if (project.exists()) {
				if (project.exists() && folder.equals(fixPath(project.getLocation()))) {
					// same project
					updateProjectDescriptors(folder, project, monitor);
				} else if (project.exists()) {
					// two different projects, so create a new project for the current folder.
					String newName = findUniqueProjectName(workspace, folder.lastSegment());
					IProject newProject = workspace.getRoot().getProject(newName);
					newProject.create(monitor);
					updateProjectDescriptors(folder, newProject, monitor);
				} else {
					project.create(monitor);
					updateProjectDescriptors(folder, project, monitor);
				}
			}
		} catch (InterruptedException e) {
			throw new CoreException(new Status(IStatus.ERROR, getClass(), "Failed to import project", e));
		}
	}

	private IProject loadProject(IPath folder, IWorkspace workspace) {
		return workspace.getRoot().getProject(folder.lastSegment());
	}

	private void updateProjectDescriptors(IPath folder, IProject project, IProgressMonitor monitor) throws CoreException, InterruptedException {
		if (!project.isOpen()) {
			project.open(monitor);
		}

		final IProjectDescription description = project.getDescription();
		final Set<String> natureIds = new HashSet<>(Arrays.asList(description.getNatureIds()));
		final List<ICommand> buildSpec = new ArrayList<>(Arrays.asList(description.getBuildSpec()));

		boolean missingPluginNature = !description.hasNature(PDE.PLUGIN_NATURE);
		if (missingPluginNature) {
			natureIds.add(PDE.PLUGIN_NATURE);
			buildSpec.add(builder(PDE_MANIFEST_BUILDER));
			buildSpec.add(builder(PDE_SCHEMA_BUILDER));
		}

		final boolean missingJavaNature = !description.hasNature(JavaCore.NATURE_ID);
		if (missingJavaNature) {
			// java nature is missing, so lets add it together with the build config
			natureIds.add(JavaCore.NATURE_ID);
			buildSpec.add(builder(JavaCore.BUILDER_ID));
		}

		description.setNatureIds(natureIds.toArray(String[]::new));
		description.setBuildSpec(buildSpec.toArray(ICommand[]::new));
		project.setDescription(description, monitor);

		if (missingJavaNature || missingPluginNature) {
			final IJavaProject javaProject = JavaCore.create(project);
			final Set<IClasspathEntry> classpathEntries = new HashSet<>(Arrays.asList(javaProject.getRawClasspath()).stream().filter(cp -> cp.getEntryKind() != IClasspathEntry.CPE_SOURCE).toList());
			final Optional<IClasspathEntry> existingRuntime = classpathEntries.stream().filter(cp -> cp.getEntryKind() == IClasspathEntry.CPE_CONTAINER).filter(IRuntimeClasspathEntry.class::isInstance).findFirst();

			classpathEntries.add(JavaCore.newContainerEntry(new Path("org.eclipse.pde.core.requiredPlugins")));
			if (ProjectUtils.isMavenProject(project) || folder.append("pom.xml").toFile().exists()) {
				Optional<Model> result = resolveMavenProject(folder, monitor).map(mp -> mp.getModel());
				if (result.isPresent()) {
					Model m = result.get();
					IFolder source = makeIfNeeded(project.getFolder(Optional.ofNullable(m.getBuild()).map(Build::getSourceDirectory).orElse("src/main/java")), monitor);
					IFolder output = makeIfNeeded(project.getFolder(Optional.ofNullable(m.getBuild()).map(Build::getOutputDirectory).orElse("target/classes")), monitor);

					IPackageFragmentRoot root = javaProject.getPackageFragmentRoot(source);
					classpathEntries.add(JavaCore.newSourceEntry(root.getPath(), new IPath[0], output.getFullPath()));
				} else {
					JavaLanguageServerPlugin.logInfo("Maven project at %s could not be loaded.".formatted(folder.toOSString()));
				}
			} else {
				IFolder source = makeIfNeeded(project.getFolder("src"), monitor);
				IFolder output = makeIfNeeded(project.getFolder("bin"), monitor);
				javaProject.setOutputLocation(output.getFullPath(), monitor);

				IPackageFragmentRoot root = javaProject.getPackageFragmentRoot(source);
				classpathEntries.add(JavaCore.newSourceEntry(root.getPath()));
			}
			if (existingRuntime.isEmpty()) {
				classpathEntries.add(JavaRuntime.getDefaultJREContainerEntry());
			}

			// add jar files in lib dir if exist, todo: read manifest
			addLibDir(folder, classpathEntries);

			javaProject.setRawClasspath(classpathEntries.toArray(IClasspathEntry[]::new), monitor);
			javaProject.save(monitor, true);
		}
	}

	private void addLibDir(IPath folder, Set<IClasspathEntry> classpathEntries) {
		File libDir = folder.append("lib").toFile();
		if (libDir.exists()) {
			try (Stream<java.nio.file.Path> files = Files.list(libDir.toPath())) {
				files.filter(p -> p.toFile().getName().endsWith(".jar")).forEach(file -> {
					classpathEntries.add(JavaCore.newLibraryEntry(IPath.fromFile(file.toFile()), null, null, new IAccessRule[0], new IClasspathAttribute[0], true));
				});
			} catch (IOException e) {
				JavaLanguageServerPlugin.logInfo("Failed to add lib reference to project at path %s.".formatted(folder.toOSString()));
				PDELaunchingPlugin.log(e);
			}
		}
	}

	private IFolder makeIfNeeded(IFolder folder, IProgressMonitor monitor) throws CoreException {
		if (!folder.exists()) {
			folder.create(true, true, monitor);
		}
		return folder;
	}

	private Optional<MavenProjectInfo> resolveMavenProject(IPath folder, IProgressMonitor monitor) throws InterruptedException {
		LocalProjectScanner scanner = new LocalProjectScanner(Arrays.asList(folder.toOSString()), false, MavenPlugin.getMavenModelManager());
		scanner.run(monitor);
		List<MavenProjectInfo> projects = scanner.getProjects();
		return !projects.isEmpty() ? Optional.ofNullable(projects.get(0)) : Optional.empty();
	}

	private ICommand builder(String name) {
		BuildCommand command = new BuildCommand();
		command.setBuilderName(name);
		return command;
	}

	private IPath fixPath(IPath path) {
		if (path != null && path.getDevice() != null) {
			return path.setDevice(path.getDevice().toUpperCase());
		}
		if (Platform.OS_WIN32.equals(Platform.getOS()) && path != null && path.toString().startsWith("//")) {
			String server = path.segment(0);
			String pathStr = path.toString().replace(server, server.toUpperCase());
			return new Path(pathStr);
		}
		return path;
	}

	private String findUniqueProjectName(IWorkspace workspace, String basename) {
		IProject project = null;
		String name;
		for (int i = 1; project == null || project.exists(); i++) {
			name = (i < 2) ? basename : basename + " (" + i + ")";
			project = workspace.getRoot().getProject(name);
		}
		return project.getName();
	}
}
