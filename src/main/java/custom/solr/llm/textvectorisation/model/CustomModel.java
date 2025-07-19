package custom.solr.llm.textvectorisation.model;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.DimensionAwareEmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class CustomModel extends DimensionAwareEmbeddingModel  {

  private final ObjectMapper mapper = new ObjectMapper();
  private final String endpointUrl;
  private final String fieldName;

  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  
  
  RequestConfig requestConfig;
  PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
  
  public CustomModel(Duration timeout, String endpointUrl, String fieldName) {
    this.endpointUrl = endpointUrl;
    this.fieldName = fieldName;

    int timeoutInSeccods = ((int) (timeout == null ? 10 : timeout.getSeconds())) * 1000;

    cm.setMaxTotal(50);
    cm.setDefaultMaxPerRoute(50);
    requestConfig = RequestConfig.custom()
        .setConnectTimeout(timeoutInSeccods)             
        .setSocketTimeout(timeoutInSeccods)              
        .setConnectionRequestTimeout(timeoutInSeccods)   
        .build();
  }

  @Override
  public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {

    List<Embedding> embeddings =
        textSegments.stream()
            .map(TextSegment::text)
            .map(this::doEmbeddingCall)
            .map(Embedding::from)
            .toList();

    return Response.from(embeddings);
  }

  private List<Float> doEmbeddingCall(String text) {

    HttpPost post = createPostRequest(text);
    long t1 = System.currentTimeMillis();
    try (CloseableHttpResponse response = getHttpClientFromPool().execute(post)) {

      log.info("Time taken to get vectors from model: {} ms", System.currentTimeMillis() - t1);
      if(response.getStatusLine().getStatusCode() != 200) {
        log.error(String.format("Failed HTTP response code %d", response.getStatusLine().getStatusCode()));
      }

      HttpEntity entity = response.getEntity();

        String json = EntityUtils.toString(entity);
        return parseFloatArray(json);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  private HttpPost createPostRequest(String text){
    HttpPost post = new HttpPost(endpointUrl);
    post.setHeader("Content-Type", "application/json");
    Map<String, String> data = new HashMap<>();
    data.put(fieldName, text);

    ObjectMapper mapper = new ObjectMapper();
    String jsonPayload;
    try {
      jsonPayload = mapper.writeValueAsString(data);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
    post.setEntity(new StringEntity(jsonPayload, Charset.defaultCharset()));
    return post;
  }

  private CloseableHttpClient getHttpClientFromPool() {
    return HttpClients.custom()
        .setConnectionManager(cm)
        .setDefaultRequestConfig(requestConfig)
        .build();
  }

  public List<Float> parseFloatArray(String json) {
    try {
      return mapper.readValue(
          json, mapper.getTypeFactory().constructCollectionType(List.class, Float.class));
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  public static CustomModelBuilder builder() {
    return new CustomModelBuilder();
  }

  public static class CustomModelBuilder {
    String endpointUrl;
    private String fieldName;

    public CustomModelBuilder() {
    }

    public CustomModel build() {
      return new CustomModel(Duration.ofSeconds(30), endpointUrl, fieldName);
    }

    public void endpointUrl(String endpointUrl) {
      this.endpointUrl = endpointUrl;
    }

    public void fieldName(String fieldName) {
      this.fieldName = fieldName;
    }
  }
}
