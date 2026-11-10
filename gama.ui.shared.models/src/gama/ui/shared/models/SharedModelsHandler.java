package gama.ui.shared.models;

import java.net.URI;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

import gama.ui.shared.models.SharedModelsFolder.ProjectNode;

public final class SharedModelsHandler extends AbstractHandler {
    @Override
    public Object execute(final ExecutionEvent event) throws ExecutionException {
        if (!(HandlerUtil.getCurrentSelection(event) instanceof IStructuredSelection selection)) return null;
        final Object selected = selection.getFirstElement();
        final ProjectNode node = selected instanceof ProjectNode project ? project : null;
        if (node == null) throw new ExecutionException("Select a shared project");
        final Shell shell = HandlerUtil.getActiveShell(event);
        switch (event.getCommand().getId()) {
            case "gama.ui.shared.models.open" -> {
                node.owner.run("Opening " + node.getName(),
                        (monitor, cancelled) -> node.owner.repository.resolve(node.project, cancelled),
                        project -> {
                            node.update(project);
                            node.owner.changed(node);
                            openPage(project.homepage());
                        }, error -> showError(shell, error));
            }
            case "gama.ui.shared.models.properties" -> node.owner.run("Reading " + node.getName(),
                    (monitor, cancelled) -> node.owner.repository.resolve(node.project, cancelled),
                    project -> {
                        node.update(project);
                        node.owner.changed(node);
                        if (!shell.isDisposed()) new PropertiesDialog(shell, project.name(), project.propertiesText()).open();
                    }, error -> showError(shell, error));
            case "gama.ui.shared.models.import" -> {
                final InputDialog dialog = new InputDialog(shell, "Import shared project",
                        "Copy this project into your workspace. Existing projects will not be overwritten.\n"
                                + "Shared models may require another GAMA version or additional extensions.\n\nProject name:",
                        node.getName(), name -> {
                            final var workspace = ResourcesPlugin.getWorkspace();
                            final var status = workspace.validateName(name, IResource.PROJECT);
                            if (!status.isOK()) return status.getMessage();
                            if (workspace.getRoot().getProject(name).exists()
                                    || workspace.getRoot().getLocation().append(name).toFile().exists()) {
                                return "A project or folder already exists with this name";
                            }
                            return null;
                        });
                if (dialog.open() != Window.OK) return null;
                final String name = dialog.getValue();
                node.owner.run("Importing " + name, (monitor, cancelled) -> {
                    try (var prepared = node.owner.repository.prepare(node.project, cancelled)) {
                        return ProjectImporter.importProject(prepared, name, monitor, cancelled);
                    }
                }, project -> {
                    if (!shell.isDisposed()) MessageDialog.openInformation(shell, "Community",
                            "Imported " + project.getName() + " into User projects.\n"
                                    + "This is an independent local copy; remote refresh will not change it.");
                }, error -> showError(shell, error));
            }
            default -> throw new ExecutionException("Unknown Shared Models command");
        }
        return null;
    }

    static void openPage(final URI uri) {
        try {
            PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(uri.toURL());
        } catch (final PartInitException | java.net.MalformedURLException e) {
            org.eclipse.core.runtime.Platform.getLog(SharedModelsHandler.class).log(
                    new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.ERROR,
                            SharedModelsFolder.PLUGIN_ID, "Cannot open model page", e));
            final var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null) showError(window.getShell(), e);
        }
    }

    private static void showError(final Shell shell, final Exception error) {
        if (!shell.isDisposed()) MessageDialog.openError(shell, "Community",
                error.getMessage() == null ? error.toString() : error.getMessage());
    }

    private static final class PropertiesDialog extends Dialog {
        private final String title;
        private final String description;
        PropertiesDialog(final Shell parent, final String title, final String description) {
            super(parent);
            this.title = title;
            this.description = description;
            setShellStyle(getShellStyle() | SWT.RESIZE);
        }
        @Override protected void configureShell(final Shell shell) {
            super.configureShell(shell);
            shell.setText(title + " - Properties");
        }
        @Override protected Control createDialogArea(final Composite parent) {
            final Composite area = (Composite) super.createDialogArea(parent);
            final Text text = new Text(area, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
            text.setText(description);
            final GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
            data.widthHint = 600;
            data.heightHint = 350;
            text.setLayoutData(data);
            return area;
        }
        @Override protected void createButtonsForButtonBar(final Composite parent) {
            createButton(parent, org.eclipse.jface.dialogs.IDialogConstants.OK_ID, "Close", true);
        }
    }
}
