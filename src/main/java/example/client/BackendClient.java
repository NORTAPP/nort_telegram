package example.client;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class BackendClient {
    private final OkHttpClient client;
    private final String baseUrl;
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public BackendClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build();
        this.baseUrl = "https://nort.onrender.com";
    }

    public String getTrendingMarkets() {
        return fetch(baseUrl + "/markets?limit=10&sort_by=volume&category=crypto");
    }

    public String getMarkets() {
        return fetch(baseUrl + "/markets?limit=50");
    }

    public String getSignals() {
        return fetch(baseUrl + "/signals?top=10");
    }

    public String getAIAdvice(String marketId) {
        String json = String.format("{\"market_id\":\"%s\",\"telegram_id\":null}", marketId);
        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder()
                .url(baseUrl + "/agent/advice")
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return "Error " + response.code();
            return response.body().string();
        } catch (IOException e) {
            return "Connection failed.";
        }
    }

    // UPDATED: supports language parameter
    public String getPremiumAdvice(String marketId, long chatId, String language) {
        String langJson = (language != null && !language.isEmpty()) ? String.format(", \"language\":\"%s\"", language) : "";
        String json = String.format(
                "{\"market_id\":\"%s\", \"telegram_id\":\"%d\", \"premium\":true%s}",
                marketId, chatId, langJson
        );
        return post(baseUrl + "/agent/advice", json);
    }

    public String verifyPayment(String proof, long chatId) {
        String json = String.format(
                "{\"proof\":\"%s\", \"user_id\":\"%d\"}",
                proof, chatId
        );
        return post(baseUrl + "/agent/x402/verify", json);
    }

    public String placePaperTrade(long chatId, String marketId, String side, double amount) {
        double pricePerShare = 0.5;
        double shares = amount / pricePerShare;
        String outcome = side.toUpperCase();
        String json = String.format(
                "{\"telegram_user_id\":%d,\"market_id\":\"%s\",\"outcome\":\"%s\",\"shares\":%.2f,\"price_per_share\":%.2f}",
                chatId, marketId, outcome, shares, pricePerShare
        );
        return post(baseUrl + "/papertrade", json);
    }

    public String getWalletSummary(long chatId) {
        return fetch(baseUrl + "/wallet/summary?telegram_user_id=" + chatId);
    }

    // NEW: Permissions route POST /permissions
    public String updatePermissions(long chatId, Boolean autoTrade, Double limit) {
        String autoTradeStr = (autoTrade != null) ? String.valueOf(autoTrade) : "null";
        String limitStr = (limit != null) ? String.valueOf(limit) : "null";
        String json = String.format("{\"telegram_user_id\":%d, \"auto_trade\":%s, \"limit\":%s}", chatId, autoTradeStr, limitStr);
        return post(baseUrl + "/permissions", json);
    }

    private String fetch(String url) {
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return "Error " + response.code();
            return response.body().string();
        } catch (IOException e) {
            return "Connection failed.";
        }
    }

    private String post(String url, String jsonBody) {
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder().url(url).post(body).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return "Error " + response.code();
            return response.body().string();
        } catch (IOException e) {
            return "Connection failed.";
        }
    }
}
