package com.ProcessUsersThreaded.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ProcessUsersThreaded.constants.ConstantJsonKeys;
import com.ProcessUsersThreaded.constants.ConstantsGit;
import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ProcessUsersThreaded.constants.ConstantJsonKeys.EXTENSIONS;
import static com.ProcessUsersThreaded.constants.ConstantsGit.MAX_CLONE_REPO_THREADS;


@Service
public class GitHubRepoService {

    public JSONObject getRepositoriesDataThreaded(List<String> reposUrlsStrings) throws IOException, InterruptedException, ExecutionException {
        List<Callable<JSONObject>> tasks = new ArrayList<>();
        ExecutorService executorService = Executors.newFixedThreadPool(MAX_CLONE_REPO_THREADS);
        // Create tasks for each repository URL
        for (String repoUrl : reposUrlsStrings) {
            tasks.add(() -> getRepositoryData(repoUrl));
        }

        // Submit tasks and wait for their completion
        List<Future<JSONObject>> futures = executorService.invokeAll(tasks);

        // Retrieve results from completed tasks
        List<JSONObject> reposDataJsonLst = new ArrayList<>();
        for (Future<JSONObject> future : futures) {
            JSONObject repoJsonData = future.get(); // This will block until the task is completed
            reposDataJsonLst.add(repoJsonData);
        }

        executorService.shutdown();
        return mergeJSONObjects(reposDataJsonLst);
    }
    public static JSONObject getRepositoryData(String repositoryUrl) throws IOException, InterruptedException {

        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode objectNode = factory.objectNode();
        String username = repositoryUrl.split("/")[3];
        String repositoryName = repositoryUrl.split("/")[4].split("\\.")[0];
        objectNode.put("username",username);
        objectNode.put("repositoryName",repositoryName);
        String path = "data/" +username + "_" + repositoryName + "_" + generateRandomString();

        cloneRepositoryLFS(repositoryUrl,path, ConstantsGit.MAX_CLONE_RETRY);
        Integer commitsCount = getNumCommitsFromGitClone(path, repositoryName);
        objectNode.setAll(getFilesDataByPath(path)); //Read repository
        deleteRepositoriesFolder(path); //Deleting repository
        objectNode.put(ConstantJsonKeys.commitsKey,commitsCount);
        return new JSONObject(objectNode.toString());
    }

    public JSONObject mergeJSONObjects(List<JSONObject> jsonObjects) {
        JSONObject mergedObject = new JSONObject();
        JSONObject linesByLangsObject = new JSONObject();
        int totalCommits = 0;
        int totalLinesCount = 0;
        int totalTestsCount = 0;

        for (JSONObject jsonObject : jsonObjects) {
            totalCommits += jsonObject.getInt(ConstantJsonKeys.commitsKey);
            totalLinesCount += jsonObject.getInt(ConstantJsonKeys.linesCountKey);
            totalTestsCount += jsonObject.getInt(ConstantJsonKeys.testsCountKey);

            JSONObject linesByLangsNestedObj = jsonObject.getJSONObject(ConstantJsonKeys.linesByLanguageKey);
            for (String key : linesByLangsNestedObj.keySet()) {
                if (linesByLangsObject.has(key)) {
                    Object existingValue = linesByLangsObject.get(key);
                    Object newValue = linesByLangsNestedObj.get(key);

                    if (existingValue instanceof Number && newValue instanceof Number) {
                        Number sum = ((Number) existingValue).intValue() + ((Number) newValue).intValue();
                        linesByLangsObject.put(key, sum);
                    }
                } else {
                    linesByLangsObject.put(key, linesByLangsNestedObj.get(key));
                }
            }
        }

        mergedObject.put(ConstantJsonKeys.commitsKey, totalCommits);
        mergedObject.put(ConstantJsonKeys.linesCountKey, totalLinesCount);
        mergedObject.put(ConstantJsonKeys.testsCountKey, totalTestsCount);
        mergedObject.put(ConstantJsonKeys.linesByLanguageKey, linesByLangsObject);

        return mergedObject;
    }




    public static boolean cloneRepositoryLFS(String repositoryUrl, String path, Integer numTries) throws InterruptedException, IOException {
        File repositoriesFolder = new File(path);
        Boolean isFolderCreated = repositoriesFolder.mkdirs();
        //String[] command = {"git", "clone", "--depth", "1", removeQuotes(repositoryUrl)}; //because of github.com changed html we have to fetch all commits
        //in order to count number of commits using git bash
        String[] command = {"git", "clone", removeQuotes(repositoryUrl)};
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(repositoriesFolder);
        // Set the GIT_LFS_SKIP_SMUDGE environment variable
        Map<String, String> environment = processBuilder.environment();
        environment.put("GIT_LFS_SKIP_SMUDGE", "1");

        Process process = processBuilder.start();
        int exitCode = process.waitFor();

        if (exitCode == 0) {
            //getNumCommitsFromGitClone(path, repositoryName);
            return true;
        } else {
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    System.err.println(line);
                }
            }
            System.out.println("another: " + process.getErrorStream().toString());
            if(numTries>0)
            {
                deleteRepositoriesFolder(path);
                return cloneRepositoryLFS(repositoryUrl, path,numTries-1);
            }
            else
            {
                System.out.println(String.format("failed to fetch repository:%s more than %d retries",
                        repositoryUrl,ConstantsGit.MAX_CLONE_RETRY));
                return false;
            }


        }
    }

    //Delete repository folder
    public static void deleteRepositoriesFolder(String path){
        File repositoriesFolder = new File(path);
        try {
            FileUtils.deleteDirectory(repositoriesFolder);
        } catch (IOException e) {
            System.err.println("Failed to delete directory: " + repositoriesFolder);
        }
    }
    public static ObjectNode getFilesDataByPath(String path) throws IOException {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode objectNode = factory.objectNode();

        List<String> keywords = testKeywords(); //test keywords
        /*if(requestKeyWords!=null)
            keywords.addAll(requestKeyWords);*/
        Path repositoriesFolder = Path.of(path);

        Map<String, AtomicInteger> linesByLanguage = new HashMap<>();
        Map<String, Integer> requestKeywordCount = new HashMap<>();
        AtomicInteger testsCount = new AtomicInteger(0) ;
        AtomicInteger linesCount = new AtomicInteger(0) ;
        try {
            Files.walk(repositoriesFolder)
                    .filter(file -> Files.isRegularFile(file) && !file.getFileName().toString().contains(".git") && hasValidExtension(file.getFileName().toString()))
                    .forEach(file -> {
                        try {
                            List<String> lines = Files.readAllLines(file);
                            int lineCount = lines.size();
                            String extension = getFileExtension(file.getFileName().toString()).toLowerCase();
                            AtomicInteger linesCounter = linesByLanguage.computeIfAbsent(extension, k -> new AtomicInteger());
                            linesCounter.addAndGet(lineCount);
                            linesCount.addAndGet(lineCount);
                            for (String line : lines) {
                                for (String keyword : keywords) {
                                    String regex = Pattern.quote(keyword.toLowerCase());
                                    Pattern pattern = Pattern.compile(regex);
                                    Matcher matcher = pattern.matcher(line.toLowerCase());
                                    while (matcher.find()) {
                                        testsCount.getAndIncrement();  //tests of fastapitutorial should be 27
                                    }
                                }

                            }

                        } catch (IOException e) {
                            System.err.println(String.format("Failed to read file: %s, error: " , file, e.getMessage()));

                        }
                    });
        } catch (IOException e) {
            System.err.println("Error while walking file tree: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("An unexpected error occurred: " + e.getMessage());
        }
        objectNode.set("linesByLanguage", convertMapToJson(linesByLanguage));
        objectNode.put("testsCount",testsCount.get());
        objectNode.put("linesCount",linesCount.get());
        return objectNode;
    }
    public static ObjectNode getFilesData(String path) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode objectNode = factory.objectNode();
        List<String> keywords = testKeywords();
        Path repositoriesFolder = Path.of(path);

        Map<String, AtomicInteger> linesByLanguage = new HashMap<>();
        Map<String, AtomicInteger> testsByLanguage = new HashMap<>();

        try {
            Files.walk(repositoriesFolder)
                    .filter(file -> Files.isRegularFile(file) && !file.getFileName().toString().contains(".git") && hasValidExtension(file.getFileName().toString()))
                    .forEach(file -> {
                        try {
                            List<String> lines = Files.readAllLines(file);
                            int lineCount = lines.size();
                            String extension = getFileExtension(file.getFileName().toString()).toLowerCase();
                            AtomicInteger linesCounter = linesByLanguage.computeIfAbsent(extension, k -> new AtomicInteger());
                            linesCounter.addAndGet(lineCount);

                            for (String line : lines) {
                                for (String keyword : keywords) {
                                    String regex = Pattern.quote(keyword.toLowerCase());
                                    Pattern pattern = Pattern.compile(regex);
                                    Matcher matcher = pattern.matcher(line.toLowerCase());
                                    while (matcher.find()) {
                                        AtomicInteger testsCounter = testsByLanguage.computeIfAbsent(extension, k -> new AtomicInteger());
                                        testsCounter.incrementAndGet();
                                    }
                                }
                            }
                        } catch (IOException e) {
                            System.err.println("Failed to read file: " + file);
                        }
                    });
        } catch (IOException e) {
            System.err.println("Error while walking file tree: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("An unexpected error occurred: " + e.getMessage());
        }

        objectNode.set("lines_by_language", convertMapToJson(linesByLanguage));
        objectNode.set("tests_by_language", convertMapToJson(testsByLanguage));

        return objectNode;
    }

    private static String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        return (lastDotIndex != -1) ? fileName.substring(lastDotIndex + 1) : "";
    }

    private static JsonNode convertMapToJson(Map<String, AtomicInteger> map) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        map.forEach((key, value) -> node.put(key, value.intValue()));
        return node;
    }

    private static JsonNode convertMapToJsonInteger(Map<String, Integer> map) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        map.forEach((key, value) -> node.put(key, value.intValue()));
        return node;
    }


    private static boolean hasValidExtension(String fileName) {
        for (String extension : EXTENSIONS) {
            if (fileName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> testKeywords(){
        List<String> keywords = new ArrayList<>();
        keywords.add("@Test"); //Java
        keywords.add("@def test_"); //Python
        keywords.add("[TestMethod]"); //C#
        keywords.add("[Test]"); //C#
        keywords.add("def test_"); //Ruby
        keywords.add("public function tes"); //PHP
        keywords.add("@func Test"); //Go
        keywords.add("@Test");

        return keywords;
    }
    public static String generateRandomString() {
        final int LENGTH = 5;
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder(LENGTH);

        for (int i = 0; i < LENGTH; i++) {
            sb.append(characters.charAt(random.nextInt(characters.length())));
        }
        return sb.toString();
    }

    public static String removeQuotes(String url) {
        if (url != null && url.length() > 0 && url.charAt(0) == '"' && url.charAt(url.length() - 1) == '"') {
            return url.substring(1, url.length() - 1);
        }
        return url;
    }

    public static Integer getNumCommitsFromGitClone(String path, String repositoryName)  {
          File clonedRepoDir = new File(path,repositoryName);
        String[] revListCommand = {"git", "rev-list", "--all", "--count"};
        ProcessBuilder revListProcessBuilder = new ProcessBuilder(revListCommand);
        revListProcessBuilder.directory(clonedRepoDir);
            try {
                Process revListProcess = revListProcessBuilder.start();
                int revListExitCode = revListProcess.waitFor();

                if (revListExitCode == 0) {
                    try (BufferedReader outputReader = new BufferedReader(new InputStreamReader(revListProcess.getInputStream()))) {
                        return Integer.parseInt(outputReader.readLine());
                    }
                } else {
                    System.err.println("Failed to run git rev-list command in order to get number of commits in repo");
                    try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(revListProcess.getErrorStream()))) {
                        String line;
                        while ((line = errorReader.readLine()) != null) {
                            System.err.println(line);
                        }
                    }
                    System.out.println("error from git: " + revListProcess.getErrorStream().toString());
                }
            }
            catch (Exception ex){
                System.out.println("Exception: " + ex.toString());
                ex.printStackTrace();
            }


        return 0;
    }
}