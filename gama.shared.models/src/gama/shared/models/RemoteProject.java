package gama.shared.models;

import java.net.URI;
import java.util.List;

public record RemoteProject(String id, String name, String group, String description, URI homepage,
        String repositoryPath, String commit, int modelCount, Release release, List<Property> properties) {

    public RemoteProject {
        properties = List.copyOf(properties);
    }

    public boolean isRepositoryProject() { return repositoryPath != null; }

    public String propertiesText() {
        final StringBuilder text = new StringBuilder();
        for (final Property property : properties) {
            if (!text.isEmpty()) text.append("\n\n");
            text.append(property.name()).append(": ").append(property.value());
        }
        return text.toString();
    }

    public record Property(String name, String value) {}

    public record Release(String version, String gamaVersion, String format, URI url, String sha256,
            long size, boolean available) {}
}
