package gama.shared.models;

import java.util.List;

public record ModelCatalogue(List<RemoteProject> projects, String warning) {
    public ModelCatalogue {
        projects = List.copyOf(projects);
    }
}
