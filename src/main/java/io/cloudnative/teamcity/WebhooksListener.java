package io.cloudnative.teamcity;

import static io.cloudnative.teamcity.WebhookPayload.*;
import static io.cloudnative.teamcity.WebhooksConstants.*;
import static io.cloudnative.teamcity.WebhooksUtils.*;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.artifacts.ArtifactsGuard;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifact;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifacts;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifactsViewMode; 
import jetbrains.buildServer.vcs.VcsException;
import jodd.http.HttpRequest;
import jodd.http.net.SocketHttpConnection;
import lombok.*;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.FieldDefaults;
import java.io.File;
import java.util.*;


@ExtensionMethod(LombokExtensions.class)
@AllArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class WebhooksListener extends BuildServerAdapter {

  @NonNull WebhooksSettings settings;
  @NonNull SBuildServer     buildServer;
  @NonNull ServerPaths      serverPaths;
  @NonNull ArtifactsGuard   artifactsGuard;


  public void register(){
    buildServer.addListener(this);
  }


  @Override
  public void buildFinished(@NonNull SRunningBuild build) {
    long time = System.currentTimeMillis();
    try {
      Gson gson    = new Gson();
      
      String payload = gson.toJson(buildPayload(build));
      
      gson.fromJson(payload, Map.class); // Sanity check of JSON generated
      log(String.format("Build '%s/#%s' finished, payload is '%s'",
        build.getFullName(), 
        build.getBuildNumber(), 
        payload));

      for (String url : settings.getUrls(build.getProjectExternalId())){
        postPayload(url, payload);
      }

      log(String.format("Operation finished in %s ms",
        System.currentTimeMillis() - time));
    }
    catch (Throwable t) {
      error(String.format("Failed to listen on buildFinished() of '%s' #%s",
        build.getFullName(), 
        build.getBuildNumber()), 
        t);
    }
  }


  @SuppressWarnings({"FeatureEnvy" , "ConstantConditions"})
  //@SneakyThrows(VcsException.class)
  private WebhookPayload buildPayload(@NonNull SBuild build){
    
    String buildStatus = buildServer.findBuildInstanceById(build.getBuildId()).
      getStatusDescriptor().getText();
    String buildStatusLink = null;
    String text = "Build completed";
    String themeColor = null;
    String buildSummary = null;
    String imgUrl = null;
    //List<Fact> facts = new ArrayList<Fact>();
    
    Section section = Section.builder().
      facts(new ArrayList<Fact>()).
      build();

    //%serverUrl%/viewLog.html?buildTypeId=%buildTypeId%&buildId=%buildId%
    String buildPageUrl = String.format("%s/viewLog.html?buildTypeId=%s&buildId=%s",
      buildServer.getRootUrl(),
      build.getBuildType().getExternalId(),
      build.getBuildId());
    
    if (build.getStatusDescriptor().isSuccessful()) {
      buildStatusLink = "[" + buildStatus + "](" + buildPageUrl + ")";
      section.facts.add(Fact.builder().
        name("Status").
        value(buildStatusLink).
        build());

      themeColor = "229911";
    } else {
      buildStatus = "Failed";
      buildStatusLink = "[" + buildStatus + "](" + buildPageUrl + ")";
      section.facts.add(Fact.builder().
        name("Status").
        value(buildStatusLink).
        build());
      String buildDetails = build.getStatusDescriptor().getText();
      section.facts.add(Fact.builder().
        name("Message").
        value(build.getStatusDescriptor().getText()).
        build());
      
      themeColor = "AA0000";
    }

    if(build.getArtifacts(BuildArtifactsViewMode.VIEW_DEFAULT).isAvailable()) {
      section.facts.add(Fact.builder().
        name("Artifacts").
        value("[View]("+buildPageUrl+"&tab=artifacts)").
        build());
    }
    
    ArrayList<Section> sections = new ArrayList<Section>();
    sections.add(section);
    return WebhookPayload.of(build.getFullName(),
                             text,
                             themeColor,
                             sections);
  }


  /**
   * POSTs payload to the URL specified
   */
  private void postPayload(@NonNull String url, @NonNull String payload){
    try {
      val request  = HttpRequest.post(url).body(payload).open();
      // http://jodd.org/doc/http.html#sockethttpconnection
      ((SocketHttpConnection) request.httpConnection()).getSocket().setSoTimeout(POST_TIMEOUT);
      val response = request.send();

      if (response.statusCode() == 200) {
        log("Payload POST-ed to '%s'".f(url));
      }
      else {
        error("POST-ing payload to '%s' - got %s response: %s".f(url, response.statusCode(), response));
      }
    }
    catch (Throwable t) {
      error("Failed to POST payload to '%s'".f(url), t);
    }
  }


  /**
   * Retrieves map of build's artifacts (archived in TeamCity and uploaded to S3):
   * {'artifact.jar' => {'archive' => 'http://teamcity/artifact/url', 's3' => 'https://s3-artifact/url'}}
   *
   * https://devnet.jetbrains.com/message/5257486
   * https://confluence.jetbrains.com/display/TCD8/Patterns+For+Accessing+Build+Artifacts
   */
  @SuppressWarnings({"ConstantConditions", "CollectionDeclaredAsConcreteClass", "FeatureEnvy"})
  private Map<String,Map<String, String>> artifacts(@NonNull SBuild build){

    val buildArtifacts = buildArtifacts(build);
    if (buildArtifacts.isEmpty()) {
      return Collections.emptyMap();
    }

    val rootUrl   = buildServer.getRootUrl();
    @SuppressWarnings("TypeMayBeWeakened")
    val artifacts = new HashMap<String, Map<String, String>>();

    if (notEmpty(rootUrl)) {
      for (val artifact : buildArtifacts){
        val artifactName = artifact.getName();
        if (".teamcity".equals(artifactName) || isEmpty(artifactName)) { continue; }

        // http://127.0.0.1:8080/repository/download/Echo_Build/37/echo-service-0.0.1-SNAPSHOT.jar
        final String url = "%s/repository/download/%s/%s/%s".f(rootUrl,
                                                               build.getBuildType().getExternalId(),
                                                               build.getBuildNumber(),
                                                               artifactName);
        artifacts.put(artifactName, map("archive", url));
      }
    }

    return Collections.unmodifiableMap(addS3Artifacts(artifacts, build));
  }


  /**
   * Retrieves current build's artifacts.
   */
  @SuppressWarnings("ConstantConditions")
  private Collection<File> buildArtifacts(@NonNull SBuild build){
    val artifactsDirectory = build.getArtifactsDirectory();
    if ((artifactsDirectory == null) || (! artifactsDirectory.isDirectory())) {
      return Collections.emptyList();
    }

    try {
      artifactsGuard.lockReading(artifactsDirectory);
      File[] files = artifactsDirectory.listFiles();
      return (files == null ? Collections.<File>emptyList() : Arrays.asList(files));
    }
    finally {
      artifactsGuard.unlockReading(artifactsDirectory);
    }
  }


  /**
   * Updates map of build's artifacts with S3 URLs:
   * {'artifact.jar' => {'s3' => 'https://s3-artifact/url'}}
   */
  @SuppressWarnings("FeatureEnvy")
  private Map<String,Map<String, String>> addS3Artifacts(@NonNull Map<String, Map<String, String>> artifacts,
                                                         @NonNull @SuppressWarnings("TypeMayBeWeakened") SBuild build){

    val s3SettingsFile = new File(serverPaths.getConfigDir(), S3_SETTINGS_FILE);

    if (! s3SettingsFile.isFile()) {
      return artifacts;
    }

    val s3Settings   = readJsonFile(s3SettingsFile);
    val bucketName   = ((String) s3Settings.get("artifactBucket"));
    val awsAccessKey = ((String) s3Settings.get("awsAccessKey"));
    val awsSecretKey = ((String) s3Settings.get("awsSecretKey"));

    if (isEmpty(bucketName)) {
      return artifacts;
    }

    try {
      AmazonS3 s3Client = isEmpty(awsAccessKey, awsSecretKey) ?
        new AmazonS3Client() :
        new AmazonS3Client(new BasicAWSCredentials(awsAccessKey, awsSecretKey));

      if (! s3Client.doesBucketExist(bucketName)) {
        return artifacts;
      }

      // "Echo::Build/15/"
      final String prefix = "%s/%s/".f(build.getFullName().replace(" :: ", "::"), build.getBuildNumber());
      val objects = s3Client.listObjects(bucketName, prefix).getObjectSummaries();

      if (objects.isEmpty()) {
        return artifacts;
      }

      val region = s3Client.getBucketLocation(bucketName);

      for (val summary : objects){
        val artifactKey = summary.getKey();
        if (isEmpty(artifactKey) || artifactKey.endsWith("/build.json")) { continue; }

        final String artifactName = artifactKey.split("/").last();
        if (isEmpty(artifactName)) { continue; }

        // https://s3-eu-west-1.amazonaws.com/evgenyg-bakery/Echo%3A%3ABuild/45/echo-service-0.0.1-SNAPSHOT.jar
        final String url = "https://s3-%s.amazonaws.com/%s/%s".f(region, bucketName, artifactKey);

        if (artifacts.containsKey(artifactName)) {
          artifacts.get(artifactName).put("s3", url);
        }
        else {
          artifacts.put(artifactName, map("s3", url));
        }
      }
    }
    catch (Throwable t) {
      error("Failed to list objects in S3 bucket '%s'".f(bucketName), t);
    }

    return artifacts;
  }
}
