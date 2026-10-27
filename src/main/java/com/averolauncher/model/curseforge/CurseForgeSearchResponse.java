package com.averolauncher.model.curseforge;

import java.util.List;

/**
 * Models CurseForge Core API v1 search response.
 * API docs: https://docs.curseforge.com/
 * All field names match the JSON exactly (snake_case / camelCase as the API returns).
 */
public class CurseForgeSearchResponse {

    public List<CurseForgeMod> data;
    public Pagination pagination;

    public static class Pagination {
        public int index;
        public int pageSize;
        public int resultCount;
        public int totalCount;
    }

    public static class CurseForgeMod {
        public int id;
        public int gameId;        // 432 = Minecraft
        public String name;
        public String slug;
        public Links links;
        public String summary;
        public int status;
        public long downloadCount;
        public boolean isFeatured;
        public int primaryCategoryId;
        public List<Category> categories;
        public int classId;       // 6=Mod, 4471=Modpack, 12=ResourcePack, 6552=Shader, 6945=DataPack
        public Author primaryAuthor;
        public List<Author> authors;
        public Logo logo;
        public List<Screenshot> screenshots;
        public int mainFileId;
        public List<LatestFile> latestFiles;
        public List<LatestFileIndex> latestFilesIndexes;
        public String dateCreated;
        public String dateModified;
        public String dateReleased;
        public Boolean allowModDistribution;
        public int gamePopularityRank;
        public boolean isAvailable;
        public int thumbsUpCount;

        public String getAuthorName() {
            if (primaryAuthor != null) return primaryAuthor.name;
            if (authors != null && !authors.isEmpty()) return authors.get(0).name;
            return "Unknown";
        }

        public String getIconUrl() {
            return logo != null ? logo.thumbnailUrl : null;
        }
    }

    public static class Links {
        public String websiteUrl;
        public String wikiUrl;
        public String issuesUrl;
        public String sourceUrl;
    }

    public static class Category {
        public int id;
        public int gameId;
        public String name;
        public String slug;
        public String url;
        public String iconUrl;
        public String dateModified;
        public Boolean isClass;
        public int classId;
        public Integer parentCategoryId;
        public Integer displayIndex;
    }

    public static class Author {
        public int id;
        public String name;
        public String url;
    }

    public static class Logo {
        public int id;
        public int modId;
        public String title;
        public String description;
        public String thumbnailUrl;
        public String url;
    }

    public static class Screenshot {
        public int id;
        public int modId;
        public String title;
        public String description;
        public String thumbnailUrl;
        public String url;
    }

    public static class LatestFile {
        public int id;
        public int gameId;
        public int modId;
        public boolean isAvailable;
        public String displayName;
        public String fileName;
        public int releaseType;   // 1=Release, 2=Beta, 3=Alpha
        public String fileStatus;
        public List<FileHash> hashes;
        public String fileDate;
        public long fileLength;
        public long downloadCount;
        public long fileSizeOnDisk;
        public String downloadUrl;
        public List<String> gameVersions;
        public List<SortableGameVersion> sortableGameVersions;
        public List<FileDependency> dependencies;
        public boolean exposeAsAlternative;
        public Integer parentProjectFileId;
        public Integer alternateFileId;
        public boolean isServerPack;
        public Integer serverPackFileId;
        public boolean isEarlyAccessContent;
        public Integer earlyAccessEndDate;
        public long fileFingerprint;
        public List<Module> modules;
    }

    public static class FileHash {
        public String value;
        public int algo;  // 1=SHA1, 2=MD5
    }

    public static class SortableGameVersion {
        public String gameVersionName;
        public String gameVersionPadded;
        public String gameVersion;
        public String gameVersionReleaseDate;
        public int gameVersionTypeId;
    }

    public static class FileDependency {
        public int modId;
        public int relationType; // 1=EmbeddedLibrary, 2=OptionalDependency, 3=RequiredDependency, 4=Tool, 5=Incompatible, 6=Include
    }

    public static class Module {
        public String name;
        public long fingerprint;
    }

    public static class LatestFileIndex {
        public String gameVersion;
        public int fileId;
        public String filename;
        public int releaseType;
        public int gameVersionTypeId;
        public Integer modLoader;
    }
}
