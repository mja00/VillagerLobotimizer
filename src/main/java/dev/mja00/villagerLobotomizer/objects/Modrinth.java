package dev.mja00.villagerLobotomizer.objects;

import java.util.List;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

public class Modrinth {
    private List<ModrinthVersion> versions;
    
    public List<ModrinthVersion> getVersions() {
        return versions;
    }
    
    public void setVersions(List<ModrinthVersion> versions) {
        this.versions = versions;
    }
    
    public static class ModrinthVersion {
        private String id;
        private String project_id;
        private String author_id;
        private boolean featured;
        private String name;
        private String version_number;
        private List<String> project_types;
        private List<String> games;
        private String changelog;
        private String date_published;
        private int downloads;
        private String version_type;
        private String status;
        private String requested_status;
        private List<ModrinthFile> files;
        private List<Object> dependencies;
        private List<String> loaders;
        private Object ordering;
        private List<String> game_versions;
        
        public String getId() {
            return id;
        }
        
        public void setId(String id) {
            this.id = id;
        }
        
        public String getProjectId() {
            return project_id;
        }
        
        public void setProjectId(String project_id) {
            this.project_id = project_id;
        }
        
        public String getAuthorId() {
            return author_id;
        }
        
        public void setAuthorId(String author_id) {
            this.author_id = author_id;
        }
        
        public boolean isFeatured() {
            return featured;
        }
        
        public void setFeatured(boolean featured) {
            this.featured = featured;
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getVersionNumber() {
            return version_number;
        }
        
        public void setVersionNumber(String version_number) {
            this.version_number = version_number;
        }
        
        public List<String> getProjectTypes() {
            return project_types;
        }
        
        public void setProjectTypes(List<String> project_types) {
            this.project_types = project_types;
        }
        
        public List<String> getGames() {
            return games;
        }
        
        public void setGames(List<String> games) {
            this.games = games;
        }
        
        public String getChangelog() {
            return changelog;
        }
        
        public void setChangelog(String changelog) {
            this.changelog = changelog;
        }
        
        public String getDatePublished() {
            return date_published;
        }
        
        public void setDatePublished(String date_published) {
            this.date_published = date_published;
        }
        
        public int getDownloads() {
            return downloads;
        }
        
        public void setDownloads(int downloads) {
            this.downloads = downloads;
        }
        
        public String getVersionType() {
            return version_type;
        }
        
        public void setVersionType(String version_type) {
            this.version_type = version_type;
        }
        
        public String getStatus() {
            return status;
        }
        
        public void setStatus(String status) {
            this.status = status;
        }
        
        public String getRequestedStatus() {
            return requested_status;
        }
        
        public void setRequestedStatus(String requested_status) {
            this.requested_status = requested_status;
        }
        
        public List<ModrinthFile> getFiles() {
            return files;
        }
        
        public void setFiles(List<ModrinthFile> files) {
            this.files = files;
        }
        
        public List<Object> getDependencies() {
            return dependencies;
        }
        
        public void setDependencies(List<Object> dependencies) {
            this.dependencies = dependencies;
        }
        
        public List<String> getLoaders() {
            return loaders;
        }
        
        public void setLoaders(List<String> loaders) {
            this.loaders = loaders;
        }
        
        public Object getOrdering() {
            return ordering;
        }
        
        public void setOrdering(Object ordering) {
            this.ordering = ordering;
        }
        
        public List<String> getGameVersions() {
            return game_versions;
        }
        
        public void setGameVersions(List<String> game_versions) {
            this.game_versions = game_versions;
        }
    }
    
    public static class ModrinthFile {
        private ModrinthHashes hashes;
        private String url;
        private String filename;
        private boolean primary;
        private int size;
        private String file_type;
        
        public ModrinthHashes getHashes() {
            return hashes;
        }
        
        public void setHashes(ModrinthHashes hashes) {
            this.hashes = hashes;
        }
        
        public String getUrl() {
            return url;
        }
        
        public void setUrl(String url) {
            this.url = url;
        }
        
        public String getFilename() {
            return filename;
        }
        
        public void setFilename(String filename) {
            this.filename = filename;
        }
        
        public boolean isPrimary() {
            return primary;
        }
        
        public void setPrimary(boolean primary) {
            this.primary = primary;
        }
        
        public int getSize() {
            return size;
        }
        
        public void setSize(int size) {
            this.size = size;
        }
        
        public String getFileType() {
            return file_type;
        }
        
        public void setFileType(String file_type) {
            this.file_type = file_type;
        }
    }
    
    public static class ModrinthHashes {
        private String sha1;
        private String sha512;
        
        public String getSha1() {
            return sha1;
        }
        
        public void setSha1(String sha1) {
            this.sha1 = sha1;
        }
        
        public String getSha512() {
            return sha512;
        }
        
        public void setSha512(String sha512) {
            this.sha512 = sha512;
        }
    }
    
    public static List<ModrinthVersion> fromJson(String json) {
        Type listType = new TypeToken<List<ModrinthVersion>>(){}.getType();
        return new Gson().fromJson(json, listType);
    }
}
