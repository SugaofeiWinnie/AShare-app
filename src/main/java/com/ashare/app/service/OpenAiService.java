package com.ashare.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class OpenAiService {
  private static final String RESPONSES_URL = "https://api.openai.com/v1/responses";

  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper;
  private final String apiKey;
  private final String model;

  public OpenAiService(
      RestTemplate restTemplate,
      ObjectMapper objectMapper,
      @Value("${ashare.openai.api-key:}") String apiKey,
      @Value("${ashare.openai.model:gpt-5.2}") String model) {
    this.restTemplate = restTemplate;
    this.objectMapper = objectMapper;
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.model = model == null || model.isBlank() ? "gpt-5.2" : model.trim();
  }

  public boolean enabled() {
    return !apiKey.isBlank();
  }

  public String model() {
    return model;
  }

  public String generateText(String prompt) {
    if (!enabled() || prompt == null || prompt.isBlank()) {
      return "";
    }
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("model", model);
      body.put("input", List.of(Map.of("role", "user", "content", prompt)));
      body.put("temperature", 0.2);

      HttpHeaders headers = new HttpHeaders();
      headers.setBearerAuth(apiKey);
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setAccept(List.of(MediaType.APPLICATION_JSON));

      HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
      ResponseEntity<JsonNode> response = restTemplate.postForEntity(RESPONSES_URL, entity, JsonNode.class);
      JsonNode root = response.getBody();
      if (root == null || !response.getStatusCode().is2xxSuccessful()) {
        return "";
      }
      String text = extractOutputText(root);
      return text == null ? "" : text.trim();
    } catch (RuntimeException ex) {
      return "";
    }
  }

  private String extractOutputText(JsonNode response) {
    StringBuilder builder = new StringBuilder();
    JsonNode output = response.path("output");
    if (output.isArray()) {
      for (JsonNode item : output) {
        JsonNode content = item.path("content");
        if (content.isArray()) {
          for (JsonNode part : content) {
            String type = part.path("type").asText("");
            if ("output_text".equals(type) || "text".equals(type)) {
              String text = part.path("text").asText("");
              if (!text.isBlank()) {
                builder.append(text);
              }
            }
          }
        }
      }
    }
    if (builder.length() == 0) {
      String outputText = response.path("output_text").asText("");
      if (!outputText.isBlank()) {
        builder.append(outputText);
      }
    }
    return builder.toString();
  }
}
