package com.ProcessUsersThreaded.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ProcessUsersThreaded.constants.ConstantsGit;
import com.ProcessUsersThreaded.model.ScrapedTabData;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GitHubUserReposTabService {



    public static final Pattern REPOSITORIES_PATTERN = Pattern.compile("codeRepository[^\\s]+[^\\S][^\\n][^\\S]+([^<]+)");
       public static final Pattern NUM_OF_REPOSITORIES_PATTERN = Pattern.compile("Repositories[^<]+<span title=\\\"([^\\\"]+)");
        /*"<span data-view-component=\"true\">Repositories</span>
    <span title=\"Not available\" data-view-component=\"true\" class=\"Counter js-profile-repository-count\">([^<]+)"gm*/
       public static final Pattern NUM_OF_REPOSITORIES_PATTERN2 = Pattern.compile("title=\\\"Not available\\\"\\s+data-view-component=\\\"true\\\" class=\\\"Counter js-profile-repository-count\\\">([^<]+)");
    public static final Pattern NAME_PATTERN = Pattern.compile("itemprop=\\\"name[^\\s]+[^\\S][^\\n][^\\S]+([^\\n]+)");
    public static final Pattern FOLLOWERS_PATTERN = Pattern.compile("class=\\\"text-bold color-fg-default\\\">([^<]+)[^\\d]+follower");
    public static final Pattern FOLLOWING_PATTERN = Pattern.compile("fg-default\\\">([^<]+)[^]d]+\\bfollowing\\b");
    public static final Pattern FORKS_PATTERN1 = Pattern.compile("text-bold[^>]+>([^<]+)[^f]+forks");
    public static final Pattern FORKS_PATTERN2 = Pattern.compile("</svg>Fork[^C]+[^\\d]+([^<]+)");
    public static final Pattern STARS_PATTERN1 = Pattern.compile("text-bold[^>]+>([^<]+)[^f]+stars");
    public static final Pattern STARS_PATTERN2 = Pattern.compile("repo-stars-counter-star[^=]+=\\\"([^ ]+)");
    public static final Pattern COMMITS_PATTERN = Pattern.compile("<span class=\\\"d-none d-sm-inline\\\">[^\\S]+<strong>([^<]+)");
    public static final Pattern PROGRAMMING_LANGUAGE_PATTERN = Pattern.compile("programmingLanguage\\\">([^<]+)");
    public static final Pattern FORKED_FROM_PATTERN = Pattern.compile("forked[ ]from([^h]+)");
    public static final Pattern EMPTY_PATTERN = Pattern.compile("This repository is ([^.]+)");
    public static final Pattern USER_NOT_FOUND_PATTERN = Pattern.compile("This is not the ([^ ]+)");

    private static OkHttpClient client = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(10))
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    //Main method
    public JSONObject getUserMetadata(String username) throws IOException, InterruptedException {

        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode objectNode = factory.objectNode();

        objectNode.setAll(metadata(username));
        if(objectNode.get("USER_NOT_FOUND").asBoolean())
            return null;

        //Set Redis repositories counter to 0
        //userRepositoryCountService.setUserRepositoryCount(username, 0);
        System.out.println("GetUserMetaData");
        objectNode.setAll(repositoriesData(username,objectNode.get("publicRepos").asText()));
        //producer.send2(objectNode.get("repositoriesUrls").asText());

        String retStr = objectNode.get("repositoriesUrls").asText();
        System.out.println(retStr);
        objectNode.remove("repositoriesUrls");
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(Map.of("results", retStr));
        JsonNode retJsonNode = objectMapper.readTree(json);
        //return new JSONObject(objectNode.toString());
        return new JSONObject(retJsonNode.toString());
    }

    public JSONObject getUserMetadataOnly(String username) throws IOException, InterruptedException { //TODO: add forks,stars,commits, forked repos,empty repos initiliazed to 0

        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode objectNode = factory.objectNode();

        System.out.println("Getting user " + username + " metadata only and repositoriesUrl");
        objectNode.setAll(metadata(username)); //name,username,url,publicRepos,followers,following
        //objectNode.put("processed_publicRepos",0);

        if(objectNode.get("USER_NOT_FOUND").asBoolean())
            return null;
        objectNode.put("numPublicRepos",objectNode.get("publicRepos"));
        objectNode.remove("publicRepos");
        objectNode.remove("USER_NOT_FOUND");
        String jsonString = objectNode.toString();
        System.out.println("getUserMetadataOnly will write the following json to redis:");
        System.out.println(jsonString);


        JSONObject jsonObj = new JSONObject(objectNode.toString());
        return jsonObj;
    }

    //Data gathering
    public static ObjectNode metadata(String username) throws IOException, InterruptedException {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode objectNode = factory.objectNode();
        String html = getRepositoriesPageHtml(username,1);

        if(getRegexGroup(USER_NOT_FOUND_PATTERN,html,"",username).equals("USER_NOT_FOUND")){
            objectNode.put("USER_NOT_FOUND",true);
            return objectNode;
        }
        else
            objectNode.put("USER_NOT_FOUND",false);


        String name = getRegexGroup(NAME_PATTERN,html, "Name", username); //name,username,url,publicRepos,followers,following
        if (name.equals("</span>")) name = "N/A";
        objectNode.put("name",name);
        objectNode.put("username",username);
        objectNode.put("url","https://github.com/" + username);
        try{
            objectNode.put("publicRepos", Integer.parseInt(getRegexGroup(NUM_OF_REPOSITORIES_PATTERN,html, "Number of Repositories", username)));
        }
        catch (Exception ex){
            String pattern2 = getRegexGroup(NUM_OF_REPOSITORIES_PATTERN2,html, "Number of Repositories", username);
            System.out.println("Pattern2: "+pattern2);
            objectNode.put("publicRepos", Integer.parseInt(getRegexGroup(NUM_OF_REPOSITORIES_PATTERN2,html, "Number of Repositories", username)));
            System.out.println("organization account. setting number of public repos to 0");
            //objectNode.put("publicRepos", 0);
        }
        String isNuym;

         objectNode.put("followers", Integer.parseInt(getRegexGroup(FOLLOWERS_PATTERN,html, "Followers", username)));
        objectNode.put("following", Integer.parseInt(getRegexGroup(FOLLOWING_PATTERN,html , "Following", username)));
        objectNode.put("forks",0);
        objectNode.put("commits",0);
        objectNode.put("stars",0);
        objectNode.put("forkedRepos",0);
        objectNode.put("emptyRepos",0);
        objectNode.put("linesCount",0);
        objectNode.put("testsCount",0);

        return objectNode;
    }


    public static ObjectNode repositoriesData(String username, String publicRepos) throws IOException, InterruptedException {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode objectNode = factory.objectNode();

        Matcher matcher;
        List<String> repositoryUrls = new ArrayList<>();
        float numOfRepositoriesPages = Float.parseFloat(publicRepos) / 30;
        int java = 0, python = 0, node = 0, angular = 0, react = 0,
                net = 0, ejs = 0, cSharp = 0, js = 0, jupyter = 0,
                cpp = 0, css = 0, kotlin = 0, c = 0, dart = 0, typeScript = 0,
                htmlRep = 0, objectiveC = 0, swift = 0, go = 0, rust = 0, ruby = 0,
                scala = 0, php = 0, r = 0, scss = 0, assembly = 0, pawn = 0;


        for(int i=1 ; numOfRepositoriesPages > 0 ; i++, numOfRepositoriesPages--){
            String html = getRepositoriesPageHtml(username,i);

            matcher = PROGRAMMING_LANGUAGE_PATTERN.matcher(html);
            while(matcher.find()){
                switch (matcher.group(1)){
                    case "SCSS":
                        scss++;
                        break;
                    case "Assembly":
                        assembly++;
                        break;
                    case "Pawn":
                        pawn++;
                        break;
                    case "Objective-C":
                        objectiveC++;
                        break;
                    case "Dart":
                        dart++;
                        break;
                    case "TypeScript":
                        typeScript++;
                        break;
                    case "C":
                        c++;
                        break;
                    case "Kotlin":
                        kotlin++;
                        break;
                    case "HTML":
                        htmlRep++;
                        break;
                    case "Java":
                        java++;
                        break;
                    case "EJS":
                        ejs++;
                        break;
                    case "C#":
                        cSharp++;
                        break;
                    case "JavaScript":
                        js++;
                        break;
                    case "Jupyter Notebook":
                        jupyter++;
                        break;
                    case "C++":
                        cpp++;
                        break;
                    case "CSS":
                        css++;
                        break;
                    case "Python":
                        python++;
                        break;
                    case "Node.js":
                        node++;
                        break;
                    case "Angular":
                        angular++;
                        break;
                    case "React":
                        react++;
                        break;
                    case ".NET":
                        net++;
                        break;
                    case "PHP":
                        php++;
                        break;
                    case "Ruby":
                        ruby++;
                        break;
                    case "Scala":
                        scala++;
                        break;
                    case "Swift":
                        swift++;
                        break;
                    case "Go":
                        go++;
                        break;
                    case "R":
                        r++;
                        break;
                    case "Rust":
                        rust++;
                        break;
                }
            }

            matcher = REPOSITORIES_PATTERN.matcher(html);
            while(matcher.find()){
                repositoryUrls.add("https://github.com/" + username + "/" + matcher.group(1) + ".git");
            }
        }
        objectNode.putAll(repositoriesMetadata(repositoryUrls,username));

        {
            objectNode.put("scss_repositories", scss);
            objectNode.put("assembly_repositories", assembly);
            objectNode.put("pawn_repositories", pawn);
            objectNode.put("objectiveC_repositories", objectiveC);
            objectNode.put("kotlin_repositories", kotlin);
            objectNode.put("dart_repositories", dart);
            objectNode.put("c_repositories", c);
            objectNode.put("typeScript_repositories", typeScript);
            objectNode.put("html_repositories", htmlRep);
            objectNode.put("java_repositories", java);
            objectNode.put("ejs_repositories", ejs);
            objectNode.put("cSharp_repositories", cSharp);
            objectNode.put("javaScript_repositories", js);
            objectNode.put("jupyter_repositories", jupyter);
            objectNode.put("cpp_repositories", cpp);
            objectNode.put("css_repositories", css);
            objectNode.put("python_repositories", python);
            objectNode.put("node.js_repositories", node);
            objectNode.put("angular_repositories", angular);
            objectNode.put("react_repositories", react);
            objectNode.put(".net_repositories", net);
            objectNode.put("php_repositories", php);
            objectNode.put("ruby_repositories", ruby);
            objectNode.put("scala_repositories", scala);
            objectNode.put("swift_repositories", swift);
            objectNode.put("go_repositories", go);
            objectNode.put("r_repositories", r);
            objectNode.put("rust_repositories", rust);
        }

        return objectNode;
    }
    public static ScrapedTabData repositoriesUrlsStrings(String username, Integer publicRepos) throws IOException, InterruptedException {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode objectNode = factory.objectNode();

        Matcher matcher;
        List<String> repositoryUrls = new ArrayList<>();
        Integer numForked = 0;
        System.out.println("Number of repos:" + publicRepos);
        int numOfRepositoriesPages = (int) Math.ceil((double) publicRepos / 30);

        System.out.println("Number of repos:" + numOfRepositoriesPages);
        int java = 0, python = 0, node = 0, angular = 0, react = 0,
                net = 0, ejs = 0, cSharp = 0, js = 0, jupyter = 0,
                cpp = 0, css = 0, kotlin = 0, c = 0, dart = 0, typeScript = 0,
                htmlRep = 0, objectiveC = 0, swift = 0, go = 0, rust = 0, ruby = 0,
                scala = 0, php = 0, r = 0, scss = 0, assembly = 0, pawn = 0;
        for(int i=1 ; numOfRepositoriesPages > 0 ; i++, numOfRepositoriesPages--) {
            String html = getRepositoriesPageHtml(username, i);
            matcher = PROGRAMMING_LANGUAGE_PATTERN.matcher(html);
            while(matcher.find()){
                switch (matcher.group(1)){
                    case "SCSS":
                        scss++;
                        break;
                    case "Assembly":
                        assembly++;
                        break;
                    case "Pawn":
                        pawn++;
                        break;
                    case "Objective-C":
                        objectiveC++;
                        break;
                    case "Dart":
                        dart++;
                        break;
                    case "TypeScript":
                        typeScript++;
                        break;
                    case "C":
                        c++;
                        break;
                    case "Kotlin":
                        kotlin++;
                        break;
                    case "HTML":
                        htmlRep++;
                        break;
                    case "Java":
                        java++;
                        break;
                    case "EJS":
                        ejs++;
                        break;
                    case "C#":
                        cSharp++;
                        break;
                    case "JavaScript":
                        js++;
                        break;
                    case "Jupyter Notebook":
                        jupyter++;
                        break;
                    case "C++":
                        cpp++;
                        break;
                    case "CSS":
                        css++;
                        break;
                    case "Python":
                        python++;
                        break;
                    case "Node.js":
                        node++;
                        break;
                    case "Angular":
                        angular++;
                        break;
                    case "React":
                        react++;
                        break;
                    case ".NET":
                        net++;
                        break;
                    case "PHP":
                        php++;
                        break;
                    case "Ruby":
                        ruby++;
                        break;
                    case "Scala":
                        scala++;
                        break;
                    case "Swift":
                        swift++;
                        break;
                    case "Go":
                        go++;
                        break;
                    case "R":
                        r++;
                        break;
                    case "Rust":
                        rust++;
                        break;
                }
            }
            ScrapedTabData scrapedTabPageData = extractUrlsStrings(html);
            numForked+= scrapedTabPageData.getNumForkedRepos();
            repositoryUrls.addAll(scrapedTabPageData.getUrlStrList());//(scrapedTabPageData.getUrlStrList())
        }
        objectNode.put("scss_repositories", scss);
        objectNode.put("assembly_repositories", assembly);
        objectNode.put("pawn_repositories", pawn);
        objectNode.put("objectivec_repositories", objectiveC);
        objectNode.put("kotlin_repositories", kotlin);
        objectNode.put("dart_repositories", dart);
        objectNode.put("c_repositories", c);
        objectNode.put("typescript_repositories", typeScript);
        objectNode.put("html_repositories", htmlRep);
        objectNode.put("java_repositories", java);
        objectNode.put("ejs_repositories", ejs);
        objectNode.put("csharp_repositories", cSharp);
        objectNode.put("javascript_repositories", js);
        objectNode.put("jupyter_repositories", jupyter);
        objectNode.put("cpp_repositories", cpp);
        objectNode.put("css_repositories", css);
        objectNode.put("python_repositories", python);
        objectNode.put("nodejs_repositories", node);
        objectNode.put("angular_repositories", angular);
        objectNode.put("react_repositories", react);
        objectNode.put("dotnet_repositories", net);
        objectNode.put("php_repositories", php);
        objectNode.put("ruby_repositories", ruby);
        objectNode.put("scala_repositories", scala);
        objectNode.put("swift_repositories", swift);
        objectNode.put("go_repositories", go);
        objectNode.put("r_repositories", r);
        objectNode.put("rust_repositories", rust);
        JSONObject progLangsJson = new JSONObject();
        progLangsJson.put("programming_languages", objectNode);
        return ScrapedTabData.builder().numForkedRepos(numForked).urlStrList(repositoryUrls).repositoriesCountByProgrammingLangJson(progLangsJson).build();
    }
    public static ObjectNode repositoriesMetadata(List<String> allRepositories, String username) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode objectNode = factory.objectNode();
        List<String> validRepositories = new ArrayList<>();
        int forks = 0, commits = 0, stars = 0, emptyRepositories = 0, forkedRepositories = 0;

        for(String URL : allRepositories){
            boolean isEmpty;
            boolean isForked;
            String html;

            try {
                html = getPageHtml(URL);
                isEmpty = !getRegexGroup(EMPTY_PATTERN, html, "Empty", username).equals("0");
                isForked = !getRegexGroup(FORKED_FROM_PATTERN, html, "Forked", username).equals("0");
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }

            if (isEmpty)
                emptyRepositories++;
            else if (isForked)
                forkedRepositories++;
            else{
                validRepositories.add(URL);
                forks += Integer.parseInt(getRegexGroup(FORKS_PATTERN1,html,URL,username));
                commits += Integer.parseInt(getRegexGroup(COMMITS_PATTERN, html,URL,username));
                stars += Integer.parseInt(getRegexGroup(STARS_PATTERN1, html,URL,username));
            }
        }


        objectNode.put("forks", forks);
        objectNode.put("commits", commits);
        objectNode.put("stars", stars);
        objectNode.put("forked_repos", forkedRepositories);
        objectNode.put("empty_repos", emptyRepositories);
        objectNode.put("repositoriesUrls",validRepositories.toString());

        return objectNode;
    }

    //Help methods
    public static String getRegexGroup(Pattern pattern, String html, String source, String username) {
        Matcher matcher = pattern.matcher(html);
        matcher.find();

        try {
            String ret = matcher.group(1);
            if (pattern.equals(USER_NOT_FOUND_PATTERN)) {
                return "USER_NOT_FOUND";
            }
            if (ret.endsWith("k"))
                ret = convertToNumber(ret);
            if (ret.contains(",")) {
                ret = ret.replace(",", "");
            }
            return ret;
        } catch (Exception e) {
            if (pattern.equals(USER_NOT_FOUND_PATTERN)) {
                return "USER_FOUND";
            }
            if (pattern.equals(FORKS_PATTERN1)) {
                return getRegexGroup(FORKS_PATTERN2, html, source, username);
            }
            if (pattern.equals(STARS_PATTERN1)) {
                return getRegexGroup(STARS_PATTERN2, html, source, username);
            }
            if (pattern.equals(FORKED_FROM_PATTERN) || pattern.equals(EMPTY_PATTERN))
                return "0";
        }
        return "0";
    }
    public static String convertToNumber(String str) {
        double num = Double.parseDouble(str.substring(0, str.length() - 1));
        return String.valueOf((int) (num * 1000));
    }
    public static String getRepositoriesPageHtml(String username, int pageNumber) throws IOException, InterruptedException {
        Request request = new Request.Builder()
                .url("https://github.com/" + username + "?page=" + pageNumber +"&tab=repositories")
                .method("GET", null)
                .build();

        //Response response = client.newCall(request).execute();
        Response response = makeRequestWithRetries(request, ConstantsGit.MAX_HTTP_RETRY);
        return response.body().string();
    }
    public static String getPageHtml(String url) throws IOException, InterruptedException {
        Request request = new Request.Builder()
                .url(url)
                .method("GET", null)
                .build();

        //Response response = client.newCall(request).execute();
        Response response = makeRequestWithRetries(request, ConstantsGit.MAX_HTTP_RETRY);
        return response.body().string();
    }

    public static ScrapedTabData extractUrlsStrings(String htmlString) {


        Document doc = Jsoup.parse(htmlString);

        List<String> repositoryUrls = new ArrayList<>();
        int forkedRepos = 0;

        Elements liElements = doc.select("#user-repositories-list > ul > li");
        for (Element li : liElements) {
            String liClass = li.className();

            if (liClass.contains("source")) {
                // Element has "source" class
                Element a = li.child(0).child(0).child(0).child(0); // Navigate to the a element
                String href = a.attr("href");
                repositoryUrls.add("https://github.com" + href + ".git");
            } else if (liClass.contains("fork")) {
                forkedRepos++;
            }
        }
        ScrapedTabData scrapedData =  ScrapedTabData.builder().numForkedRepos(forkedRepos).urlStrList(repositoryUrls)
                        .build();

        return scrapedData;
    }

    public static Response makeRequestWithRetries(Request request, int numTries) throws IOException, InterruptedException {
        IOException lastException = null;
        for (int i = 0; i < numTries; i++) {
            try {
                Response response = client.newCall(request).execute();
                if(response.isSuccessful()) {
                    return response;
                }
            } catch (IOException e) {
                System.out.println(String.format("exception in okHttp: %s", e.toString()));
                lastException = e;
            }
            Thread.sleep(1000);
        }
        throw new IOException("Could not make request to git hub after max tries");
    }
}