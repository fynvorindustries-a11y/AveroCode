package com.averolauncher.model.curseforge;

import java.util.List;

/** GET /v1/mods/{modId}/files response */
public class CurseForgeFileResponse {
    public List<CurseForgeSearchResponse.LatestFile> data;
    public CurseForgeSearchResponse.Pagination pagination;
}
