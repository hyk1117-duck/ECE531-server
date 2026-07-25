package com.example;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import fi.iki.elonen.NanoHTTPD;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;


/**
 * Minimal REST CRUD server over MongoDB, plus a static file server for the
 * frontend in src/main/resources/public/.
 *
 * Endpoints:
 *   GET    /            dashboard (index.html)
 *   GET    /index.html  dashboard (index.html)
 *   GET    /data         list all documents
 *   PUT    /data         create
 *   GET    /data/{id}    read one
 *   POST   /data/{id}    update
 *   DELETE /data/{id}    delete
 *
 * Run with: java -jar target/nanohttpd-mongo-starter.jar
 */
public class Server extends NanoHTTPD {

    private final MongoCollection<Document> collection;

    public Server(int port, String mongoUri, String dbName, String collectionName) {
        super(port);
        MongoClient client = MongoClients.create(mongoUri);
        this.collection = client.getDatabase(dbName).getCollection(collectionName);
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getProperty("port", "8080"));
        String mongoUri = System.getProperty("mongoUri", "mongodb://localhost:27017");
        Server server = new Server(port, mongoUri, "mydb", "items");
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);    
        }

    @Override
    public Response serve(IHTTPSession session) {
        if (session.getMethod() == Method.OPTIONS) {
            return withCors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""));
        }
        try {
            return withCors(route(session));
        } catch (IllegalArgumentException e) {
            return withCors(error(Response.Status.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            return withCors(error(Response.Status.INTERNAL_ERROR, e.getMessage()));
        }
    }

    private static final String DATA_PREFIX = "/data";

    private Response route(IHTTPSession session) throws IOException, ResponseException {
        String uri = session.getUri();
        Method method = session.getMethod();

        // Dashboard — always served here now that the data API has its own path.
        if (method == Method.GET && (uri.equals("/") || uri.equals("/index.html"))) {
            return serveDashboard();
        }

        // Data API — GET/PUT /data operate on the collection; GET/POST/DELETE /data/{id}
        // operate on one document.
        if (uri.equals(DATA_PREFIX) || uri.startsWith(DATA_PREFIX + "/")) {
            boolean isCollection = uri.equals(DATA_PREFIX);
            String id = isCollection ? null : uri.substring(DATA_PREFIX.length() + 1);

            if (isCollection && method == Method.GET)     return list();
            if (isCollection && method == Method.PUT)     return create(session);
            if (!isCollection && method == Method.GET)    return read(id);
            if (!isCollection && method == Method.POST)   return update(session, id);
            if (!isCollection && method == Method.DELETE) return delete(id);
        }

        return error(Response.Status.NOT_FOUND, "no such route");
    }

    // ---------- static frontend ----------

    /** Serves src/main/resources/public/index.html, bundled on the classpath by the
     *  shaded jar. Returns 404 if the resource wasn't packaged. */
    private Response serveDashboard() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/public/index.html")) {
            if (in == null) {
                return error(Response.Status.NOT_FOUND,
                        "dashboard not found — expected classpath resource /public/index.html");
            }
            byte[] bytes = in.readAllBytes();
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                    new ByteArrayInputStream(bytes), bytes.length);
        }
    }

    // ---------- CRUD ----------

    private Response list() {
        JSONArray arr = new JSONArray();
        for (Document doc : collection.find()) arr.put(toClientJson(doc));
        return json(Response.Status.OK, arr.toString(2));
    }

    private Response create(IHTTPSession session) throws IOException, ResponseException {
        Document doc = Document.parse(readBody(session));
        doc.remove("_id"); // never trust a client-supplied id on create
        collection.insertOne(doc); // driver fills in doc's _id in place
        return json(Response.Status.CREATED,toClientJson(doc).toString(2));
    }

    private Response read(String id) {
        Document doc = collection.find(new Document("_id", toObjectId(id))).first();
        if (doc == null) return error(Response.Status.NOT_FOUND, "not found");
        return json(Response.Status.OK,toClientJson(doc).toString(2));
    }

    private Response update(IHTTPSession session, String id) throws IOException, ResponseException {
        Document update = Document.parse(readBody(session));
        update.remove("_id"); // never overwrite the id
        long matched = collection.replaceOne(new Document("_id", toObjectId(id)), update).getMatchedCount();
        if (matched == 0) return error(Response.Status.NOT_FOUND, "not found");
        return json(Response.Status.OK, new JSONObject()
                .put("ID:", id)
                .put("status", "updated")
                .toString(2));
    }

    private Response delete(String id) {
        long deleted = collection.deleteOne(new Document("_id", toObjectId(id))).getDeletedCount();
        if (deleted == 0) return error(Response.Status.NOT_FOUND, "not found");
        return json(Response.Status.OK, new JSONObject()
                .put("ID:", id)
                .put("status", "deleted")
                .toString(2));
    }

    // ---------- helpers ----------

    private String readBody(IHTTPSession session) throws IOException, ResponseException {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        if (files.containsKey("postData")) return files.get("postData");           
        if (files.containsKey("content")) {                                        
            return new String(Files.readAllBytes(Paths.get(files.get("content"))), StandardCharsets.UTF_8);
        }
        return "{}";
    }

    private ObjectId toObjectId(String id) {
        if (!ObjectId.isValid(id)) throw new IllegalArgumentException("invalid id: " + id);
        return new ObjectId(id);
    }

    /** Converts Mongo's "_id": {"$oid": "..."} into a plain "id" string for clients. */
    private JSONObject toClientJson(Document doc) {
        JSONObject obj = new JSONObject(doc.toJson());
        if (obj.has("_id")) {
            Object raw = obj.remove("_id");
            String idStr = (raw instanceof JSONObject && ((JSONObject) raw).has("$oid"))
                    ? ((JSONObject) raw).getString("$oid")
                    : String.valueOf(raw);
            obj.put("id", idStr);
        }
        return obj;
    }

    private Response json(Response.Status status, String body) {
        return newFixedLengthResponse(status, "application/json", body);
    }

    private Response error(Response.Status status, String message) {
        return json(status, new JSONObject().put("error", message == null ? "" : message).toString());
    }

    private Response withCors(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        return response;
    }
}