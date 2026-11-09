package gama.ui.shared.models;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;

import gama.shared.models.ModelRepository;
import gama.shared.models.ModelRepository.PreparedProject;
import gama.workspace.nature.GamaNatures;

final class ProjectImporter {

    private ProjectImporter() {}

    static IProject importProject(final PreparedProject prepared, final String name,
            final IProgressMonitor monitor, final BooleanSupplier cancelled) throws Exception {
        final var workspace = ResourcesPlugin.getWorkspace();
        final var root = workspace.getRoot();
        final IProject project = root.getProject(name);
        final Path destination = root.getLocation().append(name).toFile().toPath();
        final IProjectDescription description = workspace.newProjectDescription(name);
        final Path marker = prepared.root().resolve(".project");
        if (Files.isRegularFile(marker)) {
            rejectLinks(marker);
            final IProjectDescription original = workspace.loadProjectDescription(
                    org.eclipse.core.runtime.Path.fromOSString(marker.toString()));
            final List<String> natures = new ArrayList<>();
            for (final String nature : GamaNatures.migrate(original.getNatureIds())) {
                if (List.of("gama.workspace.builtinNature", "gama.workspace.pluginNature",
                        "msi.gama.application.builtinNature", "msi.gama.application.pluginNature").contains(nature)) continue;
                if (workspace.getNatureDescriptor(nature) == null) {
                    throw new IOException("Required project nature is not installed: " + nature);
                }
                if (!natures.contains(nature)) natures.add(nature);
            }
            if (!natures.contains(GamaNatures.GAMA_NATURE)) natures.add(GamaNatures.GAMA_NATURE);
            if (!natures.contains(GamaNatures.XTEXT_NATURE)) natures.add(GamaNatures.XTEXT_NATURE);
            description.setNatureIds(natures.toArray(String[]::new));
            description.setBuildSpec(original.getBuildSpec());
            description.setComment(original.getComment());
            description.setReferencedProjects(original.getReferencedProjects());
        } else {
            description.setNatureIds(new String[] { GamaNatures.GAMA_NATURE, GamaNatures.XTEXT_NATURE });
        }
        if (Arrays.stream(description.getBuildSpec())
                .noneMatch(command -> command.getBuilderName().equals("org.eclipse.xtext.ui.shared.xtextBuilder"))) {
            final List<ICommand> commands = new ArrayList<>(Arrays.asList(description.getBuildSpec()));
            final ICommand builder = description.newCommand();
            builder.setBuilderName("org.eclipse.xtext.ui.shared.xtextBuilder");
            commands.add(builder);
            description.setBuildSpec(commands.toArray(ICommand[]::new));
        }
        final boolean[] created = { false };
        try {
            workspace.run(progress -> {
                if (project.exists() || Files.exists(destination)) {
                    throw new CoreException(new Status(IStatus.ERROR, SharedModelsFolder.PLUGIN_ID,
                            "A project or folder already exists with the name " + name));
                }
                ModelRepository.check(cancelled);
                project.create(description, progress);
                created[0] = true;
                project.open(progress);
                try {
                    for (final String relative : prepared.entries()) {
                        ModelRepository.check(cancelled);
                        if (relative.endsWith("/")) {
                            ensureFolder(project.getFolder(relative.substring(0, relative.length() - 1)), progress);
                            continue;
                        }
                        if (relative.equals(".project")) continue;
                        final IFile target = project.getFile(relative);
                        ensureFolder(target.getParent(), progress);
                        try (InputStream input = Files.newInputStream(prepared.root().resolve(relative))) {
                            // Project natures may already have generated settings in this new project.
                            if (target.exists()) target.setContents(input, IResource.FORCE, progress);
                            else target.create(input, false, progress);
                        }
                    }
                    project.setDefaultCharset("UTF-8", progress);
                    project.setPersistentProperty(new QualifiedName("gama.shared.models", "source"),
                            prepared.project().homepage().toString());
                    project.setPersistentProperty(new QualifiedName("gama.shared.models", "version"),
                            prepared.project().isRepositoryProject() ? prepared.project().commit()
                                    : prepared.project().release().version());
                    ModelRepository.check(cancelled);
                } catch (final IOException e) {
                    throw new CoreException(new Status(IStatus.ERROR, SharedModelsFolder.PLUGIN_ID,
                            "Cannot copy project files", e));
                }
            }, workspace.getRuleFactory().modifyRule(root), IResource.NONE, monitor);
            return project;
        } catch (final Exception failure) {
            if (created[0]) {
                try { project.delete(true, true, new org.eclipse.core.runtime.NullProgressMonitor()); }
                catch (final CoreException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private static void ensureFolder(final org.eclipse.core.resources.IContainer parent,
            final IProgressMonitor monitor) throws CoreException {
        if (parent instanceof IFolder folder && !folder.exists()) {
            ensureFolder(folder.getParent(), monitor);
            folder.create(false, true, monitor);
        }
    }

    private static void rejectLinks(final Path marker) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        final var document = factory.newDocumentBuilder().parse(marker.toFile());
        if (document.getElementsByTagName("link").getLength() > 0) {
            throw new IOException("Projects with linked resources cannot be copied safely; use GAMA's local import wizard");
        }
    }
}
