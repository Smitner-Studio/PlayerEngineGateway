package com.player2.playerengine.player2api.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Utils {
   private static final Logger LOGGER = LogManager.getLogger();

   /** Max chars of raw model content echoed into logs on a parse failure (avoid log spam / huge replies). */
   private static final int RAW_CONTENT_LOG_LIMIT = 2000;
   public static String replacePlaceholders(String input, Map<String, String> replacements) {
      for (Entry<String, String> entry : replacements.entrySet()) {
         String placeholder = "\\{\\{" + entry.getKey() + "}}";
         input = input.replaceAll(placeholder, entry.getValue());
      }

      return input;
   }

   public static String getStringJsonSafely(JsonObject input, String fieldName) {
      return input.has(fieldName) && !input.get(fieldName).isJsonNull() ? input.get(fieldName).getAsString() : null;
   }

   public static String[] jsonArrayToStringArray(JsonArray jsonArray) {
      if (jsonArray == null) {
         return new String[0];
      } else {
         List<String> stringList = new ArrayList<>();

         for (JsonElement element : jsonArray) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
               stringList.add(element.getAsString());
            } else {
               System.err.println("Warning: Skipping non-string element in JSON array: " + element);
            }
         }

         return stringList.toArray(new String[0]);
      }
   }

   public static String[] getStringArrayJsonSafely(JsonObject input, String fieldName) {
      if (input.has(fieldName) && !input.get(fieldName).isJsonNull()) {
         JsonElement element = input.get(fieldName);
         if (!element.isJsonArray()) {
            System.err
                  .println("Warning: Expected a JSON array for field '" + fieldName + "', but found a different type.");
            return null;
         } else {
            JsonArray jsonArray = element.getAsJsonArray();
            return jsonArrayToStringArray(jsonArray);
         }
      } else {
         return null;
      }
   }

   /**
    * Parse an LLM reply into a {@link JsonObject}, tolerating the common ways a model wraps or pads
    * its JSON. The previous implementation stripped only an exact lowercase {@code ```json} fence and
    * then STRICT-parsed, so any stray prefix/prose, a bare/uppercase fence, or trailing text made gson
    * throw a raw {@code MalformedJsonException} that was broadcast verbatim to the player.
    *
    * <p>Hardening, in order:
    * <ol>
    *   <li>Strip a leading code fence (```json / ```JSON / bare ```), trailing fence, and surrounding
    *       whitespace.</li>
    *   <li>Lenient-parse the cleaned text (gson {@code JsonReader.setLenient(true)}) and return it if it
    *       is a JSON object.</li>
    *   <li>Fallback: extract the first balanced {@code { ... }} object substring (respecting strings and
    *       escapes) from anywhere in the content and lenient-parse that. Handles leading/trailing prose.</li>
    * </ol>
    *
    * @return the parsed JSON object
    * @throws LlmJsonParseException if no JSON object can be recovered. The raw (truncated) content is
    *         logged at WARN here and carried on the exception for the caller; it is never shown to the player.
    */
   public static JsonObject parseCleanedJson(String content) throws LlmJsonParseException {
      String original = content == null ? "" : content;
      String cleaned = stripCodeFences(original);

      // 1) Lenient parse of the whole cleaned string.
      JsonObject obj = tryLenientParseObject(cleaned);
      if (obj != null) {
         return obj;
      }

      // 2) Fallback: pull the first balanced { ... } object out of the (possibly prose-padded) content
      //    and lenient-parse that.
      String extracted = extractFirstJsonObject(cleaned);
      if (extracted != null) {
         JsonObject extractedObj = tryLenientParseObject(extracted);
         if (extractedObj != null) {
            LOGGER.warn("parseCleanedJson: recovered JSON object via balanced-brace extraction from a padded model reply");
            return extractedObj;
         }
         String unwrapped = unwrapRedundantOuterBraces(extracted);
         if (unwrapped != null) {
            JsonObject unwrappedObj = tryLenientParseObject(unwrapped);
            if (unwrappedObj != null) {
               LOGGER.warn("parseCleanedJson: recovered JSON object by unwrapping redundant outer braces");
               return unwrappedObj;
            }
         }
      }

      // 3) Give up: log the raw content (truncated) so the malformed payload is diagnosable, and throw
      //    a typed failure the conversation layer can translate per-audience (DESIGN.md §3).
      LOGGER.warn("parseCleanedJson: could not parse model reply as JSON. Raw content (truncated to {} chars): <<<{}>>>",
            RAW_CONTENT_LOG_LIMIT, truncateForLog(original).replace('\n', ' ').replace('\r', ' '));
      throw new LlmJsonParseException(truncateForLog(original), null);
   }

   /** Strip a leading/trailing markdown code fence (```json, ```JSON, or bare ```), then trim. */
   private static String stripCodeFences(String content) {
      if (content == null) {
         return "";
      }
      String out = content.trim();
      // Leading fence: ``` optionally followed by a language tag (json/JSON/etc.) on the same line.
      out = out.replaceFirst("(?is)^```[ \\t]*[a-z0-9_-]*[ \\t]*\\r?\\n?", "");
      // Trailing fence.
      out = out.replaceFirst("(?s)\\r?\\n?[ \\t]*```[ \\t]*$", "");
      return out.trim();
   }

   /** Lenient-parse {@code text}; return the {@link JsonObject} if it is one, else null (never throws). */
   private static JsonObject tryLenientParseObject(String text) {
      if (text == null || text.isEmpty()) {
         return null;
      }
      try {
         JsonReader reader = new JsonReader(new StringReader(text));
         reader.setLenient(true);
         JsonElement el = JsonParser.parseReader(reader);
         if (el != null && el.isJsonObject()) {
            return el.getAsJsonObject();
         }
      } catch (com.google.gson.JsonParseException | IllegalStateException e) {
         // JsonParseException covers JsonSyntaxException; IllegalStateException covers getAsJsonObject on
         // a non-object. Fall through: caller tries the extraction fallback or fails with a typed error.
      }
      return null;
   }

   /**
    * Return the first balanced {@code { ... }} object substring in {@code content}, honoring quoted
    * strings and backslash escapes so braces inside string values do not throw off the depth count.
    * Returns null if there is no balanced object.
    */
   private static String extractFirstJsonObject(String content) {
      if (content == null) {
         return null;
      }
      int start = content.indexOf('{');
      if (start < 0) {
         return null;
      }
      int depth = 0;
      boolean inString = false;
      boolean escaped = false;
      for (int i = start; i < content.length(); i++) {
         char c = content.charAt(i);
         if (inString) {
            if (escaped) {
               escaped = false;
            } else if (c == '\\') {
               escaped = true;
            } else if (c == '"') {
               inString = false;
            }
            continue;
         }
         if (c == '"') {
            inString = true;
         } else if (c == '{') {
            depth++;
         } else if (c == '}') {
            depth--;
            if (depth == 0) {
               return content.substring(start, i + 1);
            }
         }
      }
      return null;
   }

   /**
    * Some models occasionally emit {@code {{ "say": ..., "program": ... }}}
    * instead of a single JSON object. The balanced extractor correctly returns the outer span, but
    * Gson rejects it because the extra braces are not an object member. Peel only one syntactically
    * redundant brace pair at a time, and only when the inner span is itself balanced.
    */
   private static String unwrapRedundantOuterBraces(String content) {
      if (content == null) {
         return null;
      }
      String current = content.trim();
      while (current.startsWith("{{") && current.endsWith("}}")) {
         String inner = current.substring(1, current.length() - 1).trim();
         String balancedInner = extractFirstJsonObject(inner);
         if (balancedInner == null || balancedInner.length() != inner.length()) {
            return null;
         }
         current = inner;
      }
      return current.equals(content.trim()) ? null : current;
   }

   /** Truncate raw content for safe logging (single visible block, capped length). */
   private static String truncateForLog(String content) {
      if (content == null) {
         return "null";
      }
      if (content.length() <= RAW_CONTENT_LOG_LIMIT) {
         return content;
      }
      return content.substring(0, RAW_CONTENT_LOG_LIMIT) + "...[truncated " + (content.length() - RAW_CONTENT_LOG_LIMIT) + " chars]";
   }

   public static String[] splitLinesToArray(String input) {
      return input != null && !input.isEmpty() ? input.split("\\R+") : new String[0];
   }

   public static JsonObject deepCopy(JsonObject original) {
      JsonParser parser = new JsonParser();
      return parser.parse(original.toString()).getAsJsonObject();
   }

   @FunctionalInterface
   public interface ThrowingFunction<T, R> {
      R apply(T t) throws Exception;
   }
}
