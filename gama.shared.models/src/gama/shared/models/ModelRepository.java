package gama.shared.models;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import gama.shared.models.RemoteProject.Property;
import gama.shared.models.RemoteProject.Release;

/** Network and archive operations never create workspace resources. */
public final class ModelRepository {

    public static final String REPOSITORY = "gama-platform/gama.models";
    public static final URI REGISTRY = URI.create("https://gama-platform.org/gama.models/registry/");
    private static final String API = "https://api.github.com/repos/" + REPOSITORY;
    private static final long DOWNLOAD_LIMIT = 200L * 1024 * 1024;
    private static final long EXTRACT_LIMIT = 1024L * 1024 * 1024;
    private static final int FILE_LIMIT = 10000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path cache;

    public ModelRepository(final Path cache) { this.cache = cache; }

    public ModelCatalogue registry(final BooleanSupplier cancelled) throws IOException {
        final Document document = json(REGISTRY.resolve("index.json"), cancelled);
        requireSchema(document.json());
        final List<RemoteProject> projects = new ArrayList<>();
        for (final JsonNode entry : array(document.json(), "models")) {
            final String id = text(entry, "id");
            if (!id.matches("[a-z0-9]+(-[a-z0-9]+)*")) throw new IOException("Invalid registry model ID");
            final String path = text(entry, "path");
            if (!path.equals("models/" + id + ".json")) throw new IOException("Invalid registry entry path");
            check(cancelled);
            projects.add(resolve(new RemoteProject(id, text(entry, "name"), "Published models",
                    "", REGISTRY.resolve(path), null, null, -1, null, List.of()), cancelled));
        }
        return new ModelCatalogue(projects, document.warning());
    }

    public ModelCatalogue repository(final BooleanSupplier cancelled) throws IOException {
        final Document head = json(URI.create(API + "/commits/main"), cancelled);
        final String commit = text(head.json(), "sha");
        if (!commit.matches("[0-9a-f]{40}")) throw new IOException("Invalid repository commit");
        final Document tree = json(URI.create(API + "/git/trees/" + commit + "?recursive=1"), cancelled);
        if (tree.json().path("truncated").asBoolean()) throw new IOException("GitHub returned a truncated tree");
        final List<String> paths = new ArrayList<>();
        for (final JsonNode entry : array(tree.json(), "tree")) {
            if ("blob".equals(text(entry, "type"))) {
                final String path = text(entry, "path");
                safePath(path);
                paths.add(path);
            }
        }
        final List<RemoteProject> projects = new ArrayList<>();
        for (final String marker : paths) {
            if (!marker.endsWith("/.project")) continue;
            final String root = marker.substring(0, marker.length() - "/.project".length());
            final String name = root.substring(root.lastIndexOf('/') + 1);
            final String group = root.contains("/") ? root.substring(0, root.indexOf('/')) : "Projects";
            final int modelCount = (int) paths.stream().filter(p -> p.startsWith(root + "/"))
                    .filter(p -> p.endsWith(".gaml")).count();
            projects.add(new RemoteProject("repository:" + root, name, group,
                    "Project stored in " + REPOSITORY + "/" + root + ".",
                    githubPage(commit, root), root, commit, modelCount, null,
                    List.of(new Property("Repository", REPOSITORY),
                            new Property("Location", root), new Property("Category", group),
                            new Property("Models", Integer.toString(modelCount)))));
        }
        return new ModelCatalogue(projects, joinWarnings(head.warning(), tree.warning()));
    }

    public RemoteProject resolve(final RemoteProject project, final BooleanSupplier cancelled) throws IOException {
        if (project.isRepositoryProject() || project.release() != null) return project;
        final Document document = json(REGISTRY.resolve("models/" + project.id() + ".json"), cancelled);
        requireSchema(document.json());
        if (!project.id().equals(text(document.json(), "id"))) throw new IOException("Registry entry ID mismatch");
        final JsonNode versions = array(document.json(), "versions");
        if (versions.isEmpty()) throw new IOException("No published versions for " + project.name());
        final JsonNode version = versions.get(versions.size() - 1);
        final String hash = text(version, "sha256");
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid archive checksum");
        final long size = version.path("size").asLong(-1);
        if (size <= 0 || size > DOWNLOAD_LIMIT) throw new IOException("Archive exceeds the download limit");
        final URI url = https(text(version, "url"));
        final Release release = new Release(text(version, "version"), text(version, "gama_version"),
                text(version, "format"), url, hash, size, "available".equals(text(version, "status")));
        final JsonNode modelFiles = version.path("gaml_files");
        final int modelCount = modelFiles.isArray() ? modelFiles.size() : -1;
        final String description = text(document.json(), "description");
        final List<Property> properties = new ArrayList<>();
        final List<String> authors = new ArrayList<>();
        final JsonNode authorNodes = document.json().path("authors");
        if (!authorNodes.isMissingNode() && !authorNodes.isNull()) {
            for (final JsonNode author : array(document.json(), "authors")) {
                authors.add(text(author, "name"));
            }
        }
        if (!authors.isEmpty()) properties.add(new Property(authors.size() == 1 ? "Author" : "Authors",
                String.join(", ", authors)));
        properties.add(new Property("Description", description));
        properties.add(new Property("Version", release.version()));
        properties.add(new Property("GAMA", release.gamaVersion()));
        properties.add(new Property("Format", release.format()));
        properties.add(new Property("Availability", release.available() ? "available" : "unavailable"));
        if (document.json().path("license").isTextual()) {
            properties.add(new Property("License", text(document.json(), "license")));
        }
        if (document.warning() != null) properties.add(new Property("Warning", document.warning()));
        final JsonNode homepage = document.json().path("homepage");
        final URI home = homepage.isTextual() && !homepage.asText().isBlank()
                ? webPage(homepage.asText()) : null;
        final URI repository = repositoryPage(url, home);
        properties.add(new Property("Repository", repository.toString()));
        if (modelCount >= 0) properties.add(new Property("Models", Integer.toString(modelCount)));
        return new RemoteProject(project.id(), project.name(), project.group(), description,
                repository, null, null, modelCount, release, properties);
    }

    private static URI repositoryPage(final URI archive, final URI homepage) {
        final String host = archive.getHost();
        final String[] parts = archive.getPath().split("/");
        if ("github.com".equalsIgnoreCase(host) && parts.length > 4 && "archive".equals(parts[3])) {
            return URI.create("https://github.com/" + parts[1] + "/" + parts[2]);
        }
        if ("codeload.github.com".equalsIgnoreCase(host) && parts.length > 3 && "zip".equals(parts[3])) {
            return URI.create("https://github.com/" + parts[1] + "/" + parts[2]);
        }
        return homepage == null ? archive : homepage;
    }

    private static URI githubPage(final String commit, final String path) {
        try {
            return new URI("https", "github.com", "/" + REPOSITORY + "/tree/" + commit + "/" + path, null);
        } catch (final java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid repository path", e);
        }
    }

    public PreparedProject prepare(final RemoteProject selected, final BooleanSupplier cancelled) throws IOException {
        final RemoteProject project = resolve(selected, cancelled);
        final URI url;
        final String expectedHash;
        final long expectedSize;
        if (project.isRepositoryProject()) {
            url = URI.create("https://codeload.github.com/" + REPOSITORY + "/zip/" + project.commit());
            expectedHash = null;
            expectedSize = -1;
        } else {
            final Release release = project.release();
            if (!release.available()) throw new IOException("This model version is marked unavailable");
            if (!"zip".equals(release.format())) {
                throw new IOException("Only ZIP project import is supported in this version (not "
                        + release.format() + ")");
            }
            url = release.url();
            expectedHash = release.sha256();
            expectedSize = release.size();
        }
        Files.createDirectories(cache);
        final Path archive = cache.resolve(key(url.toString()) + ".zip");
        if (Files.exists(archive) && expectedHash != null && !expectedHash.equals(hash(archive, cancelled))) {
            Files.delete(archive);
        }
        if (!Files.exists(archive)) {
            final Path partial = Files.createTempFile(cache, "download-", ".zip");
            try {
                download(url, partial, DOWNLOAD_LIMIT, cancelled);
                if (expectedSize > 0 && Files.size(partial) != expectedSize) throw new IOException("Archive size mismatch");
                if (expectedHash != null && !expectedHash.equals(hash(partial, cancelled))) {
                    throw new IOException("Archive SHA-256 mismatch");
                }
                Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(partial);
            }
        }
        final Path staging = Files.createTempDirectory(cache, "project-");
        try {
            extract(archive, staging, project, cancelled);
            final Path root = project.isRepositoryProject() ? staging : findProjectRoot(staging);
            return new PreparedProject(project, root, staging);
        } catch (final IOException | RuntimeException e) {
            try { deleteTree(staging); } catch (final IOException cleanup) { e.addSuppressed(cleanup); }
            if (e instanceof java.util.zip.ZipException) {
                try { Files.deleteIfExists(archive); } catch (final IOException cleanup) { e.addSuppressed(cleanup); }
            }
            throw e;
        }
    }

    private static void extract(final Path archive, final Path staging, final RemoteProject project,
            final BooleanSupplier cancelled) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            int entries = 0;
            long bytes = 0;
            String wrapper = null;
            final var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                check(cancelled);
                final var entry = enumeration.nextElement();
                if (++entries > FILE_LIMIT) throw new IOException("Archive contains too many entries");
                String name = entry.getName();
                if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                safePath(name);
                if (project.isRepositoryProject()) {
                    final int slash = name.indexOf('/');
                    final String top = slash < 0 ? name : name.substring(0, slash);
                    if (wrapper == null) wrapper = top;
                    if (!wrapper.equals(top)) throw new IOException("Repository archive has multiple roots");
                    final String prefix = wrapper + "/" + project.repositoryPath() + "/";
                    if (!name.startsWith(prefix)) continue;
                    name = name.substring(prefix.length());
                }
                final Path destination = staging.resolve(name).normalize();
                if (!destination.startsWith(staging)) throw new IOException("Unsafe archive path");
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    if (Files.exists(destination)) throw new IOException("Duplicate archive entry: " + name);
                    Files.createDirectories(destination.getParent());
                    try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(destination)) {
                        bytes += copy(input, output, EXTRACT_LIMIT - bytes, cancelled);
                    }
                }
            }
        }
        if (project.isRepositoryProject() && !Files.isRegularFile(staging.resolve(".project"))) {
            throw new IOException("Selected project is missing from the archive");
        }
    }

    private static Path findProjectRoot(final Path staging) throws IOException {
        try (var paths = Files.walk(staging)) {
            final List<Path> markers = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(".project")).toList();
            if (markers.size() == 1) return markers.getFirst().getParent();
            if (markers.size() > 1) throw new IOException("This archive contains multiple projects; import is ambiguous");
        }
        if (Files.isDirectory(staging.resolve("models"))) return staging;
        try (var children = Files.list(staging)) {
            final List<Path> folders = children.filter(Files::isDirectory).toList();
            if (folders.size() == 1 && Files.isDirectory(folders.getFirst().resolve("models"))) return folders.getFirst();
        }
        throw new IOException("Archive contains no GAMA project (.project or models/)");
    }

    public static void safePath(final String path) throws IOException {
        if (path.isBlank() || path.length() > 4096 || path.startsWith("/") || path.contains("\\") || path.contains(":")
                || path.indexOf('\0') >= 0) throw new IOException("Unsafe archive/repository path: " + path);
        final String[] components = path.split("/", -1);
        if (components.length > 128) throw new IOException("Archive/repository path is too deeply nested");
        for (final String component : components) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Unsafe archive/repository path: " + path);
            }
        }
    }

    private record Document(JsonNode json, String warning) {}

    private Document json(final URI uri, final BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(cache);
        final Path stored = cache.resolve(key(uri.toString()) + ".json");
        final Path temporary = Files.createTempFile(cache, "metadata-", ".json");
        try {
            try {
                download(uri, temporary, 16L * 1024 * 1024, cancelled);
            } catch (final IOException network) {
                if (!Files.isRegularFile(stored)) throw network;
                return new Document(JSON.readTree(stored.toFile()),
                        "Offline/stale catalogue: " + network.getMessage() + ". Refresh to retry.");
            }
            final JsonNode parsed = JSON.readTree(temporary.toFile());
            if (parsed == null || !parsed.isObject()) throw new IOException("Expected a JSON object from " + uri);
            Files.move(temporary, stored, StandardCopyOption.REPLACE_EXISTING);
            return new Document(parsed, null);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void download(final URI original, final Path destination, final long limit,
            final BooleanSupplier cancelled) throws IOException {
        URI uri = original;
        for (int redirects = 0; redirects <= 5; redirects++) {
            check(cancelled);
            https(uri.toString());
            final HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "GAMA-Shared-Models");
            connection.setRequestProperty("Accept", "application/vnd.github+json, application/json, */*");
            try {
                final int status = connection.getResponseCode();
                if (status >= 300 && status <= 399) {
                    final String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without a destination");
                    uri = uri.resolve(location);
                    continue;
                }
                if (status != 200) throw new IOException("HTTP " + status + " from " + uri.getHost()
                        + (status == 403 || status == 429 ? " (access denied or rate limit; retry later)" : ""));
                if (connection.getContentLengthLong() > limit) throw new IOException("Download exceeds size limit");
                try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(destination)) {
                    copy(input, output, limit, cancelled);
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many HTTP redirects");
    }

    private static long copy(final InputStream input, final OutputStream output, final long limit,
            final BooleanSupplier cancelled) throws IOException {
        long total = 0;
        final byte[] buffer = new byte[32768];
        int read;
        while ((read = input.read(buffer)) != -1) {
            check(cancelled);
            total += read;
            if (total > limit) throw new IOException("Download/extraction exceeds size limit");
            output.write(buffer, 0, read);
        }
        return total;
    }

    public static void check(final BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static String text(final JsonNode node, final String field) throws IOException {
        final JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IOException("Missing field: " + field);
        return value.asText();
    }

    private static JsonNode array(final JsonNode node, final String field) throws IOException {
        final JsonNode value = node.get(field);
        if (value == null || !value.isArray()) throw new IOException("Missing array: " + field);
        return value;
    }

    private static void requireSchema(final JsonNode node) throws IOException {
        if (node.path("schema_version").asInt(-1) != 1) throw new IOException("Unsupported registry schema");
    }

    private static URI https(final String value) throws IOException {
        final URI uri = webPage(value);
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IOException("Downloads require HTTPS");
        return uri;
    }

    private static URI webPage(final String value) throws IOException {
        try {
            final URI uri = URI.create(value);
            if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IOException("Only HTTP(S) page URLs without credentials are supported");
            }
            return uri;
        } catch (final IllegalArgumentException e) {
            throw new IOException("Invalid URL", e);
        }
    }

    private static String key(final String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (final NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String hash(final Path file, final BooleanSupplier cancelled) throws IOException {
        final MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            final byte[] buffer = new byte[32768];
            int read;
            while ((read = input.read(buffer)) != -1) {
                check(cancelled);
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String joinWarnings(final String first, final String second) {
        return first == null ? second : second == null ? first : first + "\n" + second;
    }

    private static void deleteTree(final Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    public record PreparedProject(RemoteProject project, Path root, Path staging) implements AutoCloseable {
        public List<String> entries() throws IOException {
            try (var paths = Files.walk(root)) {
                return paths.filter(p -> !p.equals(root)).map(p -> root.relativize(p).toString().replace('\\', '/')
                        + (Files.isDirectory(p) ? "/" : "")).sorted().toList();
            }
        }
        @Override
        public void close() throws IOException { deleteTree(staging); }
    }
}
