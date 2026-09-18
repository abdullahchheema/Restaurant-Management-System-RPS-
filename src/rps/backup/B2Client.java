package rps.backup;

import rps.util.MiniJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * A minimal client for Backblaze B2's native REST API (not the S3-compatible one —
 * b2_authorize_account's own auth flow needs only Basic auth + SHA1, no AWS Signature
 * V4 HMAC chain to hand-implement). Talks to exactly the four calls this app needs:
 * authorize, get an upload URL, upload a file, and list/delete old files for pruning.
 */
final class B2Client {

    private static final String AUTH_URL = "https://api.backblazeb2.com/b2api/v3/b2_authorize_account";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String keyId;
    private final String applicationKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private String authToken;
    private String apiUrl;
    private String bucketId;
    /** The key's own restricted prefix (e.g. "sahowala/"), if it has one — every upload
     *  and list call is scoped under this automatically, so the app never needs its own
     *  separate "which folder" setting that could drift from what the key actually allows. */
    private String namePrefix;

    B2Client(String keyId, String applicationKey) {
        this.keyId = keyId;
        this.applicationKey = applicationKey;
    }

    record UploadedFile(String fileId, String fileName) {}
    record RemoteFile(String fileId, String fileName, long uploadTimestamp) {}

    /** Must be called once before any other method. Throws with the raw B2 error body
     *  on failure (bad key, revoked key, etc.) — deliberately not swallowed here, since
     *  the caller decides how to surface it. */
    void authorize() throws IOException, InterruptedException {
        String credentials = Base64.getEncoder().encodeToString(
            (keyId + ":" + applicationKey).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(AUTH_URL))
            .timeout(TIMEOUT)
            .header("Authorization", "Basic " + credentials)
            .GET()
            .build();
        Map<String, Object> body = sendJson(req);

        authToken = MiniJson.str(body, "authorizationToken");
        Map<String, Object> apiInfo = MiniJson.asObject(body.get("apiInfo"));
        Map<String, Object> storageApi = MiniJson.asObject(apiInfo.get("storageApi"));
        apiUrl = MiniJson.str(storageApi, "apiUrl");
        bucketId = MiniJson.str(storageApi, "bucketId");
        namePrefix = MiniJson.str(storageApi, "namePrefix");
        if (namePrefix == null) namePrefix = "";
        if (bucketId == null) {
            throw new IOException("This application key isn't restricted to a single bucket — "
                + "expected a bucket-scoped key.");
        }
    }

    /** Full remote name including the key's own prefix — callers pass just the file's
     *  own name (e.g. "rps-20260826-153000.dump") and get back where it actually landed. */
    String remoteName(String fileName) {
        return namePrefix + fileName;
    }

    UploadedFile upload(Path localFile, String fileName) throws IOException, InterruptedException {
        Map<String, Object> uploadUrlInfo = post(apiUrl + "/b2api/v3/b2_get_upload_url",
            MiniJson.object("bucketId", bucketId));
        String uploadUrl = MiniJson.str(uploadUrlInfo, "uploadUrl");
        String uploadAuthToken = MiniJson.str(uploadUrlInfo, "authorizationToken");

        byte[] data = Files.readAllBytes(localFile);
        String sha1 = sha1Hex(data);
        String remoteName = remoteName(fileName);

        HttpRequest req = HttpRequest.newBuilder(URI.create(uploadUrl))
            .timeout(TIMEOUT)
            .header("Authorization", uploadAuthToken)
            .header("X-Bz-File-Name", urlEncode(remoteName))
            .header("Content-Type", "application/octet-stream")
            .header("X-Bz-Content-Sha1", sha1)
            .POST(HttpRequest.BodyPublishers.ofByteArray(data))
            .build();
        Map<String, Object> resp = sendJson(req);
        return new UploadedFile(MiniJson.str(resp, "fileId"), MiniJson.str(resp, "fileName"));
    }

    /** Every version B2 has kept of one file name, newest first — since every backup
     *  uploads to the same fixed name, each upload lands as a new version rather than a
     *  new file, and this is how old versions are found for pruning. B2 lists in a single
     *  page for the modest version counts this app will ever produce. */
    List<RemoteFile> listVersions(String fileName) throws IOException, InterruptedException {
        String remote = remoteName(fileName);
        Map<String, Object> resp = post(apiUrl + "/b2api/v3/b2_list_file_versions",
            MiniJson.object("bucketId", bucketId, "prefix", remote, "maxFileCount", 1000));
        List<Object> files = MiniJson.asArray(resp.get("files"));
        List<RemoteFile> result = new ArrayList<>();
        for (Object f : files) {
            Map<String, Object> fo = MiniJson.asObject(f);
            String name = MiniJson.str(fo, "fileName");
            if (!remote.equals(name)) continue; // prefix match only — exclude any other file that happens to start with this name
            result.add(new RemoteFile(MiniJson.str(fo, "fileId"), name,
                MiniJson.longVal(fo, "uploadTimestamp", 0)));
        }
        result.sort((a, b) -> Long.compare(b.uploadTimestamp(), a.uploadTimestamp()));
        return result;
    }

    void deleteFile(String fileName, String fileId) throws IOException, InterruptedException {
        post(apiUrl + "/b2api/v3/b2_delete_file_version",
            MiniJson.object("fileName", fileName, "fileId", fileId));
    }

    // ---------------------------------------------------------------- plumbing

    private Map<String, Object> post(String url, String jsonBody) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(TIMEOUT)
            .header("Authorization", authToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
        return sendJson(req);
    }

    private Map<String, Object> sendJson(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, Object> body = MiniJson.asObject(MiniJson.parse(resp.body()));
        if (resp.statusCode() / 100 != 2) {
            String code = MiniJson.str(body, "code");
            String message = MiniJson.str(body, "message");
            throw new IOException("B2 API error " + resp.statusCode() + " (" + code + "): " + message);
        }
        return body;
    }

    private static String sha1Hex(byte[] data) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 not available", e);
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
