package gama.ui.shared.models;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.widgets.Display;

import gama.shared.models.ModelCatalogue;
import gama.shared.models.ModelRepository;
import gama.shared.models.RemoteProject;
import gama.ui.navigator.view.contents.NavigatorRoot;
import gama.ui.navigator.view.contents.ResourceManager;
import gama.ui.navigator.view.contents.TopLevelFolder;
import gama.ui.navigator.view.contents.VirtualContent;
import gama.ui.shared.resources.GamaIcon;
import gama.ui.shared.resources.IGamaColors;
import gama.ui.shared.resources.IGamaIcons;

public final class SharedModelsFolder extends TopLevelFolder {

    static final String PLUGIN_ID = "gama.ui.shared.models";
    final ModelRepository repository;
    private final ResourceManager manager;
    private final Display display;
    private final List<Job> jobs = new ArrayList<>();
    private volatile boolean disposed;
    private volatile int generation;
    private Object[] sources;

    SharedModelsFolder(final NavigatorRoot root, final ModelRepository repository) {
        super(root, "Shared Models", icon(IGamaIcons.FOLDER_PLUGIN),
                "Browse community projects; import only those you choose", IGamaColors.BLUE);
        this.repository = repository;
        manager = root.getManager();
        display = Display.getCurrent();
        sources = newSources();
    }

    private Object[] newSources() {
        return new Object[] { new SourceNode(this, "Published models", false),
                new SourceNode(this, "Repository projects", true) };
    }

    @Override public Object[] getNavigatorChildren() { return sources; }
    @Override public Object[] getCachedChildren() { return sources; }
    @Override public boolean hasChildren() { return true; }

    @Override
    public void refresh(final IProgressMonitor monitor) {
        cancelJobs();
        publish(() -> {
            sources = newSources();
            manager.refreshContent(this);
        });
    }

    @Override
    public void dispose() {
        disposed = true;
        cancelJobs();
    }

    private synchronized void cancelJobs() {
        generation++;
        for (final Job job : jobs) job.cancel();
        jobs.clear();
    }

    private void publish(final Runnable action) {
        if (!disposed && !display.isDisposed()) display.asyncExec(() -> {
            if (!disposed) action.run();
        });
    }

    @FunctionalInterface
    interface Work<T> {
        T run(IProgressMonitor monitor, BooleanSupplier cancelled) throws Exception;
    }

    <T> void run(final String title, final Work<T> work, final Consumer<T> success,
            final Consumer<Exception> failure) {
        final int expected = generation;
        final Job job = new Job(title) {
            @Override
            protected IStatus run(final IProgressMonitor monitor) {
                monitor.beginTask(title, IProgressMonitor.UNKNOWN);
                final BooleanSupplier cancelled = () -> disposed || generation != expected || monitor.isCanceled();
                try {
                    ModelRepository.check(cancelled);
                    final T result = work.run(monitor, cancelled);
                    publish(() -> { if (expected == generation) success.accept(result); });
                    return Status.OK_STATUS;
                } catch (final CancellationException | org.eclipse.core.runtime.OperationCanceledException e) {
                    publish(() -> {
                        if (expected == generation) failure.accept(
                                new CancellationException("Operation cancelled. Refresh to retry."));
                    });
                    return Status.CANCEL_STATUS;
                } catch (final Exception e) {
                    final IStatus status = new Status(IStatus.ERROR, PLUGIN_ID, title + ": " + e.getMessage(), e);
                    Platform.getLog(SharedModelsFolder.class).log(status);
                    publish(() -> { if (expected == generation) failure.accept(e); });
                    return status;
                } finally {
                    monitor.done();
                    synchronized (SharedModelsFolder.this) { jobs.remove(this); }
                }
            }
        };
        job.setUser(true);
        synchronized (this) {
            if (disposed || expected != generation) return;
            jobs.add(job);
            job.schedule();
        }
    }

    private void changed(final VirtualContent<?> node) { manager.refreshContent(node); }

    static ImageDescriptor icon(final String name) { return GamaIcon.named(name).descriptor(); }

    public static class Node extends VirtualContent<VirtualContent<?>> {
        final SharedModelsFolder owner;
        private final String id;
        private final VirtualContentType type;
        Object[] children = EMPTY;

        Node(final VirtualContent<?> parent, final String name, final String id, final VirtualContentType type) {
            super(parent, name);
            owner = (SharedModelsFolder) parent.getTopLevelFolder();
            this.id = id;
            this.type = type;
        }

        @Override public String getId() { return id; }
        @Override public VirtualContentType getType() { return type; }
        @Override public Object[] getNavigatorChildren() { return children; }
        @Override public Object[] getCachedChildren() { return children; }
        @Override public boolean hasChildren() { return children.length > 0; }
        @Override public int findMaxProblemSeverity() { return NO_PROBLEM; }
        @Override public ImageDescriptor getOverlay() { return null; }
        @Override public void getSuffix(final StringBuilder sb) {}
        @Override public ImageDescriptor getImageDescriptor() {
            return icon(type == VirtualContentType.PROJECT ? IGamaIcons.FOLDER_PROJECT
                    : type == VirtualContentType.FILE ? IGamaIcons.FILE_TEXT : IGamaIcons.FOLDER_RESOURCES);
        }
    }

    private static final class SourceNode extends Node {
        private final boolean repositorySource;
        private boolean requested;

        SourceNode(final SharedModelsFolder parent, final String name, final boolean repositorySource) {
            super(parent, name, repositorySource ? "repository" : "registry", VirtualContentType.FOLDER);
            this.repositorySource = repositorySource;
        }

        @Override public boolean hasChildren() { return !requested || super.hasChildren(); }

        @Override
        public Object[] getNavigatorChildren() {
            if (!requested) {
                requested = true;
                children = new Object[] { new MessageNode(this, "Loading catalogue...", "loading") };
                owner.run("Loading " + getName(), (monitor, cancelled) -> repositorySource
                        ? owner.repository.repository(cancelled) : owner.repository.registry(cancelled),
                        this::loaded, this::failed);
            }
            return children;
        }

        private void loaded(final ModelCatalogue catalogue) {
            final List<Node> result = new ArrayList<>();
            if (catalogue.warning() != null) result.add(new MessageNode(this, catalogue.warning(), "warning"));
            final TreeMap<String, Node> groups = new TreeMap<>();
            for (final RemoteProject project : catalogue.projects()) {
                final Node parent;
                if (repositorySource) {
                    parent = groups.computeIfAbsent(project.group(),
                            group -> new Node(this, group, group, VirtualContentType.FOLDER));
                    final List<Object> nodes = new ArrayList<>(List.of(parent.children));
                    nodes.add(new ProjectNode(parent, project));
                    parent.children = nodes.toArray();
                } else {
                    result.add(new ProjectNode(this, project));
                }
            }
            result.addAll(groups.values());
            if (catalogue.projects().isEmpty()) result.add(new MessageNode(this, "No shared projects yet", "empty"));
            children = result.toArray();
            owner.changed(this);
        }

        private void failed(final Exception error) {
            children = new Object[] { new MessageNode(this, "Unable to load: " + error.getMessage()
                    + " (Refresh to retry)", "error") };
            owner.changed(this);
        }
    }

    public static final class ProjectNode extends Node {
        volatile RemoteProject project;
        private boolean requested;

        ProjectNode(final VirtualContent<?> parent, final RemoteProject project) {
            super(parent, project.name(), project.id(), VirtualContentType.PROJECT);
            this.project = project;
        }

        @Override public boolean hasChildren() { return !requested || super.hasChildren(); }
        @Override public String getExplanation() { return project.description(); }
        @Override public void getSuffix(final StringBuilder sb) {
            if (project.release() != null) sb.append(project.release().version());
        }

        @Override
        public Object[] getNavigatorChildren() {
            if (!requested) {
                requested = true;
                children = new Object[] { new MessageNode(this, "Loading project...", "loading") };
                owner.run("Browsing " + getName(), (monitor, cancelled) -> {
                    final RemoteProject resolved = owner.repository.resolve(project, cancelled);
                    if (resolved.isRepositoryProject()) return new Listing(resolved, resolved.files());
                    try (var prepared = owner.repository.prepare(resolved, cancelled)) {
                        return new Listing(resolved, prepared.entries());
                    }
                }, listing -> {
                    project = listing.project();
                    children = fileTree(this, listing.files(), "");
                    owner.changed(this);
                }, error -> {
                    children = new Object[] { new MessageNode(this, error.getMessage() + " (Refresh to retry)", "error") };
                    owner.changed(this);
                });
            }
            return children;
        }
    }

    private record Listing(RemoteProject project, List<String> files) {}

    private static Object[] fileTree(final VirtualContent<?> parent, final List<String> files, final String prefix) {
        final TreeMap<String, List<String>> folders = new TreeMap<>();
        final TreeMap<String, FileNode> leaves = new TreeMap<>();
        for (final String file : files) {
            final String relative = file.substring(prefix.length());
            if (relative.isEmpty()) continue;
            final int slash = relative.indexOf('/');
            if (slash < 0) leaves.put(relative, new FileNode(parent, relative, file));
            else folders.computeIfAbsent(relative.substring(0, slash), key -> new ArrayList<>()).add(file);
        }
        final List<Node> nodes = new ArrayList<>();
        folders.forEach((name, paths) -> {
            final Node folder = new Node(parent, name, prefix + name, VirtualContentType.FOLDER);
            folder.children = fileTree(folder, paths, prefix + name + "/");
            nodes.add(folder);
        });
        nodes.addAll(leaves.values());
        return nodes.toArray();
    }

    public static final class FileNode extends Node {
        final String path;
        FileNode(final VirtualContent<?> parent, final String name, final String path) {
            super(parent, name, path, VirtualContentType.FILE);
            this.path = path;
        }
        ProjectNode remoteProject() {
            VirtualContent<?> parent = getParent();
            while (!(parent instanceof ProjectNode)) parent = parent.getParent();
            return (ProjectNode) parent;
        }
        @Override public boolean handleDoubleClick() {
            SharedModelsHandler.openPage(owner.repository.filePage(remoteProject().project, path));
            return true;
        }
    }

    private static final class MessageNode extends Node {
        MessageNode(final VirtualContent<?> parent, final String message, final String id) {
            super(parent, message, id, VirtualContentType.NONE);
        }
        @Override public ImageDescriptor getImageDescriptor() { return null; }
    }
}
