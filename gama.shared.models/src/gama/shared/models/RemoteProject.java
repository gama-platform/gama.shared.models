package gama.shared.models;

import java.net.URI;
import java.util.List;

public record RemoteProject(String id, String name, String group, String description, URI homepage,
        String repositoryPath, String commit, List<String> files, Release release) {

    public RemoteProject {
        files = List.copyOf(files);
    }

    public boolean isRepositoryProject() { return repositoryPath != null; }

    public record Release(String version, String gamaVersion, String format, URI url, String sha256,
            long size, boolean available) {}
}
