package gama.ui.shared.models;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.FrameworkUtil;

import gama.shared.models.ModelRepository;
import gama.ui.navigator.view.contents.ITopLevelFolderProvider;
import gama.ui.navigator.view.contents.NavigatorRoot;
import gama.ui.navigator.view.contents.TopLevelFolder;

public final class SharedModelsFolderProvider implements ITopLevelFolderProvider {
    @Override
    public TopLevelFolder createFolder(final NavigatorRoot root) {
        final var cache = Platform.getStateLocation(FrameworkUtil.getBundle(ModelRepository.class))
                .append("catalogue").toFile().toPath();
        return new SharedModelsFolder(root, new ModelRepository(cache));
    }
}
