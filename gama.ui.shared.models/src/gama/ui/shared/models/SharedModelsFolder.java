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
        super(root, "Community", icon(IGamaIcons.FOLDER_COMMUNITY),
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
    @Override public boolean supportsWorkspaceActions() { return false; }

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

    void changed(final VirtualContent<?> node) { manager.refreshContent(node); }

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

        ProjectNode(final VirtualContent<?> parent, final RemoteProject project) {
            super(parent, project.name(), project.id(), VirtualContentType.PROJECT);
            update(project);
        }

        void update(final RemoteProject resolved) {
            project = resolved;
            children = resolved.properties().stream().map(property -> new PropertyNode(this, property)).toArray();
        }

        @Override public String getExplanation() { return "remote project"; }
        @Override public void getSuffix(final StringBuilder sb) {
            if (project.modelCount() >= 0) {
                sb.append(project.modelCount()).append(project.modelCount() == 1 ? " model" : " models");
            } else {
                sb.append("model count unavailable");
            }
        }
    }

    private static final class PropertyNode extends Node {
        private final String value;

        PropertyNode(final ProjectNode parent, final RemoteProject.Property property) {
            super(parent, property.name(), property.name(), VirtualContentType.NONE);
            value = property.value();
        }

        @Override public void getSuffix(final StringBuilder sb) {
            sb.append(value.replaceAll("\\s+", " "));
        }
        @Override public String getStatusMessage() { return getName() + ": " + value; }
        @Override public String getStatusTooltip() { return getStatusMessage(); }
        @Override public ImageDescriptor getImageDescriptor() { return icon("generic/action.properties"); }
    }

    private static final class MessageNode extends Node {
        MessageNode(final VirtualContent<?> parent, final String message, final String id) {
            super(parent, message, id, VirtualContentType.NONE);
        }
        @Override public ImageDescriptor getImageDescriptor() { return null; }
    }
}
