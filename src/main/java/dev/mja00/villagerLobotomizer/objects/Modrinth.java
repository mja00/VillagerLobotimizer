package dev.mja00.villagerLobotomizer.objects;

import java.util.List;

/**
 * Domain records for the subset of the Modrinth API surface used by the update checker.
 * Gson 2.10+ (on the project's classpath) deserialises records natively via the component
 * accessor methods.
 */
public class Modrinth {

    private Modrinth() {}

    public record Version(
            String id,
            String project_id,
            String author_id,
            boolean featured,
            String name,
            String version_number,
            List<String> project_types,
            List<String> games,
            String changelog,
            String date_published,
            int downloads,
            String version_type,
            String status,
            String requested_status,
            List<File> files,
            List<Object> dependencies,
            List<String> loaders,
            Object ordering,
            List<String> game_versions
    ) {}

    public record File(
            Hashes hashes,
            String url,
            String filename,
            boolean primary,
            int size,
            String file_type
    ) {}

    public record Hashes(
            String sha1,
            String sha512
    ) {}
}
