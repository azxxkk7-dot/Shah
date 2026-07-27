package com.pinfetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootApplication
@RestController
public class PinterestDownloaderApplication {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        SpringApplication.run(PinterestDownloaderApplication.class, args);
        System.out.println("==========================================================");
        System.out.println("   FOZ GPT - ULTRA PINTEREST VIDEO DOWNLOADER (JAVA v2.1) ");
        System.out.println("   Akses URL ini di browser Anda: http://localhost:8080    ");
        System.out.println("==========================================================");
    }

    // Endpoint API Ekstraksi Media
    @PostMapping("/api/extract")
    public ResponseEntity<Map<String, Object>> extractMedia(@RequestBody Map<String, String> payload) {
        Map<String, Object> response = new HashMap<>();
        String targetUrl = payload.get("url");

        if (targetUrl == null || targetUrl.trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "Tautan URL kosong!");
            return ResponseEntity.badRequest().body(response);
        }

        Map<String, Object> result = extractPinterestMedia(targetUrl.trim());
        if (!(Boolean) result.get("success")) {
            return ResponseEntity.badRequest().body(result);
        }

        return ResponseEntity.ok(result);
    }

    // Endpoint Proxy Download Lokal (Bypass CORS)
    @GetMapping("/api/download")
    public ResponseEntity<?> downloadMedia(@RequestParam("url") String mediaUrl,
                                           @RequestParam(value = "type", defaultValue = "image") String mediaType) {
        try {
            String decodedUrl = URLDecoder.decode(mediaUrl, StandardCharsets.UTF_8.name());
            URL url = new URI(decodedUrl).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Referer", "https://www.pinterest.com/");
            connection.setConnectTimeout(25000);
            connection.setReadTimeout(25000);

            if (connection.getResponseCode() != 200) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body("Server Pinterest menolak izin pengunduhan langsung.");
            }

            String ext = "video".equalsIgnoreCase(mediaType) ? "mp4" : "jpg";
            String contentType = "video".equalsIgnoreCase(mediaType) ? "video/mp4" : "image/jpeg";
            String filename = "PinFetch_" + ext + "_HD." + ext;

            InputStream inputStream = connection.getInputStream();
            InputStreamResource resource = new InputStreamResource(inputStream);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
            headers.setContentType(MediaType.parseMediaType(contentType));

            return ResponseEntity.ok()
                    .headers(headers)
                    .contentLength(connection.getContentLengthLong())
                    .body(resource);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Kesalahan transfer data: " + e.getMessage());
        }
    }

    // Algoritma Parser Ekstraksi Pinterest
    private Map<String, Object> extractPinterestMedia(String inputUrl) {
        Map<String, Object> response = new HashMap<>();
        try {
            String targetUrl = inputUrl;

            // Resolusi URL pendek pin.it
            if (targetUrl.contains("pin.it") || targetUrl.contains("pinterest.com/co/")) {
                HttpURLConnection conn = (HttpURLConnection) new URI(targetUrl).toURL().openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.connect();
                targetUrl = conn.getURL().toString();
            }

            // Unduh dokumen HTML
            Document doc = Jsoup.connect(targetUrl)
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
                    .header("Referer", "https://www.pinterest.com/")
                    .timeout(12000)
                    .get();

            String rawHtml = doc.html().replace("\\/", "/");

            // LANGKAH 1: Regex Pemindaian CDN Video Direct
            List<String> videoPatterns = Arrays.asList(
                    "https://v1\\.pinimg\\.com/videos/mc/[^\\s\"']+\\.(?:mp4|m3u8)",
                    "https://videos\\.pinimg\\.com/[^\\s\"']+\\.(?:mp4|m3u8)"
            );

            for (String patternStr : videoPatterns) {
                Pattern pattern = Pattern.compile(patternStr);
                Matcher matcher = pattern.matcher(rawHtml);
                if (matcher.find()) {
                    String cleanUrl = matcher.group(0).replaceAll("[\\\\\"']", "");
                    if (cleanUrl.contains(".m3u8")) {
                        cleanUrl = cleanUrl.replace("/hls/", "/720p/").replace(".m3u8", ".mp4");
                    }
                    response.put("success", true);
                    response.put("type", "video");
                    response.put("media_url", cleanUrl);
                    return response;
                }
            }

            // LANGKAH 2: Ekstraksi JSON Rekursif pada Script Elements
            Elements scriptElements = doc.select("script");
            for (Element script : scriptElements) {
                String content = script.html();
                if (content.contains("video_list") || content.contains("videos") || content.contains("pins")) {
                    try {
                        String cleanJs = content.trim();
                        if (cleanJs.startsWith("window.")) {
                            int eqIdx = cleanJs.indexOf('=');
                            if (eqIdx != -1) {
                                cleanJs = cleanJs.substring(eqIdx + 1).trim();
                                if (cleanJs.endsWith(";")) {
                                    cleanJs = cleanJs.substring(0, cleanJs.length() - 1);
                                }
                            }
                        }

                        JsonNode rootNode = mapper.readTree(cleanJs);
                        JsonNode videoList = deepSearchKey(rootNode, "video_list");

                        if (videoList != null && videoList.isObject()) {
                            String[] resolutions = {"V_720P", "V_1080P", "V_480P", "V_360P"};
                            for (String res : resolutions) {
                                if (videoList.has(res) && videoList.get(res).has("url")) {
                                    String vUrl = videoList.get(res).get("url").asText();
                                    if (vUrl.contains(".m3u8")) {
                                        vUrl = vUrl.replace("/hls/", "/720p/").replace(".m3u8", ".mp4");
                                    }
                                    response.put("success", true);
                                    response.put("type", "video");
                                    response.put("media_url", vUrl);
                                    return response;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            // LANGKAH 3: Fallback Meta Tag OG Video
            Element metaOgVideo = doc.selectFirst("meta[property=og:video]");
            if (metaOgVideo != null && metaOgVideo.hasAttr("content")) {
                String vUrl = metaOgVideo.attr("content");
                if (vUrl.contains("hls")) {
                    vUrl = vUrl.replace("hls/", "720p/").replace(".m3u8", ".mp4");
                }
                response.put("success", true);
                response.put("type", "video");
                response.put("media_url", vUrl);
                return response;
            }

            // LANGKAH 4: Fallback Meta Tag OG Image
            Element metaOgImage = doc.selectFirst("meta[property=og:image]");
            if (metaOgImage != null && metaOgImage.hasAttr("content")) {
                String imgUrl = metaOgImage.attr("content");
                imgUrl = imgUrl.replace("/736x/", "/originals/").replace("/564x/", "/originals/");
                response.put("success", true);
                response.put("type", "image");
                response.put("media_url", imgUrl);
                return response;
            }

            response.put("success", false);
            response.put("error", "Media tidak dapat dideteksi. Pastikan Pin memiliki gambar atau video.");
            return response;

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "Kesalahan pemrosesan 500QI: " + e.getMessage());
            return response;
        }
    }

    // Fungsi Pencarian Key JSON Secara Rekursif
    private JsonNode deepSearchKey(JsonNode node, String keyToFind) {
        if (node == null) return null;
        if (node.has(keyToFind)) {
            return node.get(keyToFind);
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode res = deepSearchKey(entry.getValue(), keyToFind);
                if (res != null) return res;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                JsonNode res = deepSearchKey(item, keyToFind);
                if (res != null) return res;
            }
        }
        return null;
    }
}