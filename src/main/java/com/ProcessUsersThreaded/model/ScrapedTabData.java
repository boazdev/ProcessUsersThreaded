package com.ProcessUsersThreaded.model;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.json.JSONObject;

import java.util.List;

@Data
@Builder
@Getter
@Setter
public class ScrapedTabData {
    private List<String> urlStrList;
    private Integer numForkedRepos;
    private JSONObject repositoriesCountByProgrammingLangJson;
}
