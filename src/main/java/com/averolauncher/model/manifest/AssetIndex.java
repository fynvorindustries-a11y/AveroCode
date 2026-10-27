package com.averolauncher.model.manifest;

import java.util.Map;

/** Structure of the asset index JSON referenced by VersionDetail.assetIndex.url */
public class AssetIndex {
    public Map<String, AssetObject> objects;
    public boolean virtual;
    public boolean map_to_resources;

    public static class AssetObject {
        public String hash;
        public long size;

        /** Mojang stores loose assets under objects/<first-2-chars-of-hash>/<full-hash> */
        public String storagePath() {
            return hash.substring(0, 2) + "/" + hash;
        }
    }
}
