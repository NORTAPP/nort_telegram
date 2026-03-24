package example;

import example.client.BackendClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Bot extends TelegramLongPollingBot {

    private static final double DEFAULT_AUTO_TRADE_AMOUNT = 50.0;

    private final BackendClient backend = new BackendClient();
    private final Map<Long, String> userLanguages = new ConcurrentHashMap<>();
    private final Map<Long, String> pendingPremiumMarkets = new ConcurrentHashMap<>();
    private final Map<Long, Double> autoTradeLimits = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> autoTradeEnabled = new ConcurrentHashMap<>();

    @Override
    public String getBotUsername() {
        return "Nort67Bot";
    }

    @Override
    public String getBotToken() {
        String token = System.getenv("BOT_TOKEN");
        if (token == null || token.isEmpty()) {
            throw new RuntimeException("BOT_TOKEN environment variable not set!");
        }
        return token;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            handleIncomingMessage(update.getMessage().getChatId(), update.getMessage().getText().trim());
        } else if (update.hasCallbackQuery()) {
            handleCallback(update);
        }
    }

    private void handleIncomingMessage(long chatId, String messageText) {
        if (messageText.isEmpty()) {
            return;
        }

        String[] commandParts = messageText.split("\\s+");
        String command = commandParts[0].toLowerCase();
        String lang = userLanguages.getOrDefault(chatId, "en");

        switch (command) {
            case "/start":
                sendMenu(chatId);
                break;

            case "/lang":
            case "/language":
                handleLanguageCommand(chatId, commandParts);
                break;

            case "/enable_autotrade":
                handleAutoTradeToggle(chatId, true);
                break;

            case "/disable_autotrade":
                handleAutoTradeToggle(chatId, false);
                break;

            case "/set_limit":
                handleSetLimit(chatId, commandParts);
                break;

            case "/trending":
                handleTrending(chatId);
                break;

            case "/advice":
            case "/premium_advice":
                handleAdvice(chatId, commandParts, lang);
                break;

            case "/pay":
                handlePaymentProof(chatId, commandParts, lang);
                break;

            case "/portfolio":
                handlePortfolio(chatId);
                break;

            case "/markets":
                handleMarkets(chatId);
                break;

            case "/signals":
                handleSignals(chatId);
                break;

            case "/papertrade":
                handlePaperTrade(chatId, commandParts);
                break;

            default:
                sendDefaultHelp(chatId);
        }
    }

    private void handleLanguageCommand(long chatId, String[] commandParts) {
        if (commandParts.length < 2) {
            sendLanguageMenu(chatId);
            return;
        }

        String newLang = commandParts[1].toLowerCase();
        if (newLang.equals("sw") || newLang.equals("en")) {
            userLanguages.put(chatId, newLang);
            sendText(chatId, "Language updated to: " + (newLang.equals("sw") ? "Kiswahili" : "English"));
        } else {
            sendText(chatId, "Unsupported language. Use '/lang en' or '/lang sw'.");
        }
    }

    private void handleAutoTradeToggle(long chatId, boolean enabled) {
        sendText(chatId, (enabled ? "Enabling" : "Disabling") + " auto-trade permissions...");
        String result = backend.updatePermissions(chatId, enabled, null);
        autoTradeEnabled.put(chatId, enabled);
        sendText(chatId, formatJsonOrRaw("Permission update", result));
    }

    private void handleSetLimit(long chatId, String[] commandParts) {
        if (commandParts.length < 2) {
            sendText(chatId, "Usage: /set_limit <amount>\nExample: /set_limit 100");
            return;
        }

        try {
            double limit = Double.parseDouble(commandParts[1]);
            if (limit <= 0) {
                sendText(chatId, "Limit must be greater than 0.");
                return;
            }

            sendText(chatId, "Setting auto-trade limit to $" + String.format("%.2f", limit) + "...");
            String result = backend.updatePermissions(chatId, null, limit);
            autoTradeLimits.put(chatId, limit);
            sendText(chatId, formatJsonOrRaw("Limit update", result));
        } catch (NumberFormatException e) {
            sendText(chatId, "Invalid amount. Please use numbers only.");
        }
    }

    private void handleTrending(long chatId) {
        sendText(chatId, "Fetching top trending markets...");
        String trendingRaw = backend.getTrendingMarkets();
        try {
            JSONObject trendingJson = new JSONObject(trendingRaw);
            JSONArray markets = trendingJson.getJSONArray("markets");
            StringBuilder message = new StringBuilder();
            message.append("TOP TRENDING MARKETS\n");
            message.append("--------------------------------\n\n");

            for (int i = 0; i < markets.length(); i++) {
                JSONObject market = markets.getJSONObject(i);
                String marketId = market.optString("id", "?");
                String question = market.optString("question", "Unknown");
                double volume = market.optDouble("volume", 0);
                double odds = market.optDouble("current_odds", 0);
                String expires = market.optString("expires_at", "?").split(" ")[0];
                String oddsStr = odds > 0 ? String.format("%.0f%%", odds * 100) : "-";

                message.append(String.format("%d. %s%n", i + 1, question));
                message.append(String.format("   ID: %s%n", marketId));
                message.append(String.format("   Volume: $%,.0f | Odds: %s | Expires: %s%n%n", volume, oddsStr, expires));
            }

            message.append("Use /advice <id> for premium analysis.");
            sendText(chatId, message.toString());
        } catch (Exception e) {
            sendText(chatId, "Unable to retrieve trending markets at this time.");
        }
    }

    private void handleAdvice(long chatId, String[] commandParts, String lang) {
        if (commandParts.length < 2) {
            sendText(chatId, "Usage: /advice <market_id>\nExample: /advice 527079");
            return;
        }

        String marketId = commandParts[1];
        pendingPremiumMarkets.put(chatId, marketId);
        sendText(chatId, "Analyzing market " + marketId + " (" + (lang.equals("sw") ? "Kiswahili" : "English") + ")...");

        String premiumResponse = backend.getPremiumAdvice(marketId, chatId, lang);

        if (isBackendUnavailable(premiumResponse)) {
            sendText(chatId, "The backend is currently waking up or unavailable. Please wait 30 seconds and try again.");
            return;
        }

        if (looksLikePaymentRequired(premiumResponse)) {
            sendPaymentInstructions(chatId, marketId, premiumResponse);
            return;
        }

        sendPremiumAdvice(chatId, marketId, premiumResponse);
    }

    private void handlePaymentProof(long chatId, String[] commandParts, String lang) {
        if (commandParts.length < 2) {
            sendText(chatId, "Usage: /pay <tx_hash>\nExample: /pay 0xabc123...");
            return;
        }

        String txHash = commandParts[1];
        if (!txHash.matches("0x[0-9a-fA-F]{64}")) {
            sendText(chatId, "Invalid transaction hash. It should look like /pay 0xabc123...");
            return;
        }

        String marketId = pendingPremiumMarkets.get(chatId);
        if (marketId == null || marketId.isEmpty()) {
            sendText(chatId, "I do not have a pending premium request for you. Run /advice <market_id> first.");
            return;
        }

        sendText(chatId, "Verifying payment on Base for market " + marketId + "...");
        String verifyResult = backend.verifyPayment(txHash, chatId);

        try {
            JSONObject verifyJson = new JSONObject(verifyResult);
            if (verifyJson.optBoolean("success") || verifyJson.optBoolean("verified")) {
                sendText(chatId, "Payment verified. Unlocking premium advice...");
                String unlocked = backend.getPremiumAdvice(marketId, chatId, lang);

                if (looksLikePaymentRequired(unlocked)) {
                    sendText(chatId, "Payment verification succeeded, but premium advice is still locked. Please try /advice " + marketId + " again.");
                    return;
                }

                sendPremiumAdvice(chatId, marketId, unlocked);
                pendingPremiumMarkets.remove(chatId);
            } else {
                sendText(chatId, "Payment not verified. Details: " + verifyJson.optString("reason", "Unknown error"));
            }
        } catch (Exception e) {
            sendText(chatId, "Verification error: " + e.getMessage() + "\nRaw: " + preview(verifyResult));
        }
    }

    private void handlePortfolio(long chatId) {
        String summary = backend.getWalletSummary(chatId);
        if (summary == null || summary.trim().isEmpty() || summary.startsWith("Connection failed")) {
            sendText(chatId, "Portfolio summary is unavailable right now.");
            return;
        }

        sendText(chatId, "PORTFOLIO SUMMARY\n--------------------------------\n" + summary);
    }

    private void handleMarkets(long chatId) {
        sendText(chatId, "Fetching market data...");
        String rawMarkets = backend.getMarkets();
        if (rawMarkets == null || rawMarkets.trim().isEmpty()) {
            rawMarkets = "No market data available at this time.";
        }
        sendText(chatId, "LIVE MARKETS\n--------------------------------\n\n" + rawMarkets);
    }

    private void handleSignals(long chatId) {
        sendText(chatId, "Analyzing market momentum...");
        String signalsRaw = backend.getSignals();
        try {
            JSONArray signalList;
            String trimmed = signalsRaw.trim();
            if (trimmed.startsWith("[")) {
                signalList = new JSONArray(trimmed);
            } else {
                JSONObject wrapped = new JSONObject(trimmed);
                signalList = wrapped.getJSONArray(wrapped.keys().next());
            }

            StringBuilder signals = new StringBuilder();
            signals.append("TOP MARKET SIGNALS\n");
            signals.append("--------------------------------\n\n");

            for (int i = 0; i < signalList.length(); i++) {
                JSONObject signal = signalList.getJSONObject(i);
                String marketId = signal.optString("market_id", "?");
                String question = signal.optString("question", "Unknown");
                double score = signal.optDouble("score", 0);
                String reason = signal.optString("reason", "");
                double volume = signal.optDouble("volume", 0);
                double odds = signal.optDouble("current_odds", 0);
                String oddsStr = odds > 0 ? String.format("%.0f%%", odds * 100) : "-";
                int scorePct = (int) Math.round(score * 100);

                signals.append(String.format("%d. %s%n", i + 1, question));
                signals.append(String.format("   ID: %s | Score: %d%%%n", marketId, scorePct));
                signals.append(String.format("   Volume: $%,.0f | Odds: %s%n", volume, oddsStr));
                signals.append(String.format("   Note: %s%n%n", reason));
            }

            signals.append("Use /advice <id> to get a full AI analysis.");
            sendText(chatId, signals.toString());
        } catch (Exception e) {
            sendText(chatId, "Signal parse error: " + e.getMessage() + "\nRaw preview: " + preview(signalsRaw));
        }
    }

    private void handlePaperTrade(long chatId, String[] commandParts) {
        if (commandParts.length < 4) {
            sendText(chatId, "Usage: /papertrade <market_id> <yes/no> <amount>\nExample: /papertrade 527079 yes 50");
            return;
        }

        try {
            double amount = Double.parseDouble(commandParts[3]);
            String result = backend.placePaperTrade(chatId, commandParts[1], commandParts[2], amount);
            sendText(chatId, "PAPER TRADE RESULT\n--------------------------------\n" + result);
        } catch (NumberFormatException e) {
            sendText(chatId, "Invalid amount. Please use numbers only (e.g. 50, 100.50).");
        }
    }

    private void handleCallback(Update update) {
        String callData = update.getCallbackQuery().getData();
        long chatId = update.getCallbackQuery().getMessage().getChatId();

        if (callData.startsWith("lang_")) {
            String newLang = callData.substring(5);
            userLanguages.put(chatId, newLang);
            sendText(chatId, "Language updated to: " + (newLang.equals("sw") ? "Kiswahili" : "English"));
            return;
        }

        if (callData.startsWith("exe_yes_")) {
            handleExecuteCallback(chatId, callData);
            return;
        }

        if (callData.equals("exe_no")) {
            sendText(chatId, "Trade auto-execution cancelled.");
            return;
        }

        switch (callData) {
            case "btn_trending":
                handleTrending(chatId);
                break;
            case "btn_advice":
                sendText(chatId, "Usage: /advice <market_id>\nExample: /advice 527079\n\nIf premium advice is locked, pay and then send /pay <tx_hash>.");
                break;
            case "btn_portfolio":
                handlePortfolio(chatId);
                break;
            default:
                sendText(chatId, "Unknown action.");
        }
    }

    private void handleExecuteCallback(long chatId, String callData) {
        String[] parts = callData.split("_");
        if (parts.length < 4) {
            sendText(chatId, "Unable to parse trade execution request.");
            return;
        }

        if (!autoTradeEnabled.getOrDefault(chatId, false)) {
            sendText(chatId, "Auto-trade is disabled. Enable it first with /enable_autotrade.");
            return;
        }

        String marketId = parts[2];
        String side = parts[3];
        double limit = autoTradeLimits.getOrDefault(chatId, DEFAULT_AUTO_TRADE_AMOUNT);
        double amount = Math.min(DEFAULT_AUTO_TRADE_AMOUNT, limit);

        if (amount <= 0) {
            sendText(chatId, "Your auto-trade limit must be greater than 0. Use /set_limit <amount> first.");
            return;
        }

        sendText(chatId, "Executing trade... Placing $" + String.format("%.2f", amount) + " on " + side + " for market " + marketId);
        String result = backend.placePaperTrade(chatId, marketId, side, amount);
        sendText(chatId, "Trade Result:\n" + result);
    }

    private void sendPaymentInstructions(long chatId, String marketId, String premiumResponse) {
        try {
            JSONObject paymentJson = new JSONObject(premiumResponse);
            double amount = paymentJson.optDouble("amount", 0.10);
            String address = paymentJson.optString("address", "NORT_TREASURY_ADDRESS");
            String asset = paymentJson.optString("asset", "USDC");
            String chain = paymentJson.optString("chain", "Base");

            sendText(chatId,
                    "Premium advice for market " + marketId + " is locked.\n\n" +
                    "Send $" + String.format("%.2f", amount) + " " + asset + " on " + chain + " to:\n" +
                    address + "\n\n" +
                    "Then send proof with:\n/pay <tx_hash>");
        } catch (Exception e) {
            sendText(chatId,
                    "Premium advice for market " + marketId + " is locked.\n\n" +
                    "Complete the x402 payment, then send proof with:\n/pay <tx_hash>\n\n" +
                    "Raw payment response: " + preview(premiumResponse));
        }
    }

    private void sendPremiumAdvice(long chatId, String marketId, String premiumResponse) {
        try {
            JSONObject json = new JSONObject(premiumResponse);
            if (!json.has("summary")) {
                sendText(chatId, "Premium content for market " + marketId + ":\n\n" + json.optString("content", preview(premiumResponse)));
                return;
            }

            String summary = json.optString("summary", "");
            String why = json.optString("why_trending", "");
            String plan = json.optString("suggested_plan", "WAIT");
            String disclaimer = json.optString("disclaimer", "This is not financial advice.");
            double confidence = json.optDouble("confidence", 0.5);
            String staleWarn = json.optString("stale_data_warning", "");
            String riskList = formatRiskFactors(json.opt("risk_factors"));

            StringBuilder message = new StringBuilder();
            message.append("MARKET ANALYSIS: ").append(json.optString("market_id", marketId)).append("\n\n");
            message.append("SUMMARY\n").append(summary).append("\n\n");
            message.append("TREND ANALYSIS\n").append(why).append("\n\n");
            message.append("RISK FACTORS\n").append(riskList).append("\n\n");
            message.append("RECOMMENDED ACTION: ").append(plan).append("\n");
            message.append("CONFIDENCE: ").append(String.format("%.0f%%", confidence * 100)).append("\n");
            if (!staleWarn.isEmpty()) {
                message.append("\nDATA WARNING: ").append(staleWarn).append("\n");
            }
            message.append("\n").append(disclaimer);

            if (!plan.toUpperCase().contains("WAIT")) {
                String side = deriveTradeSide(plan);
                sendAdviceWithExecuteOption(chatId, message.toString(), marketId, side);
            } else {
                sendText(chatId, message.toString());
            }
        } catch (Exception e) {
            sendText(chatId, "Unable to parse premium advice.\nRaw preview: " + preview(premiumResponse));
        }
    }

    private String formatRiskFactors(Object risksObj) {
        if (!(risksObj instanceof JSONArray)) {
            return "None listed";
        }

        JSONArray risks = (JSONArray) risksObj;
        if (risks.length() == 0) {
            return "None listed";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < risks.length(); i++) {
            if (i > 0) {
                builder.append("\n");
            }
            builder.append("- ").append(risks.optString(i, ""));
        }
        return builder.toString();
    }

    private String deriveTradeSide(String plan) {
        String normalizedPlan = plan.toUpperCase();
        if (normalizedPlan.contains("NO") || normalizedPlan.contains("SELL")) {
            return "NO";
        }
        return "YES";
    }

    private boolean isBackendUnavailable(String response) {
        return response.contains("503")
                || response.startsWith("Connection failed")
                || response.startsWith("Error 5");
    }

    private boolean looksLikePaymentRequired(String response) {
        return response.contains("\"amount\"")
                || response.contains("PAYMENT-REQUIRED")
                || response.contains("payment required")
                || response.contains("\"address\"")
                || response.contains("\"asset\"");
    }

    private String formatJsonOrRaw(String title, String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            return title + ":\n" + json.toString(2);
        } catch (Exception e) {
            return title + ":\n" + payload;
        }
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.substring(0, Math.min(300, text.length()));
    }

    public void sendMenu(long chatId) {
        SendMessage sm = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text("NORT67 AI MARKET ANALYST\n\n" +
                        "Real-time prediction market analysis powered by live market data, AI reasoning, and Telegram bot workflows.\n\n" +
                        "Core commands: /advice /signals /trending /portfolio /lang\n" +
                        "Payment flow: /advice <market_id> then /pay <tx_hash>")
                .build();

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        row1.add(InlineKeyboardButton.builder().text("Trending Markets").callbackData("btn_trending").build());
        row1.add(InlineKeyboardButton.builder().text("AI Advice").callbackData("btn_advice").build());

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        row2.add(InlineKeyboardButton.builder().text("Portfolio").callbackData("btn_portfolio").build());

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        row3.add(InlineKeyboardButton.builder().text("Set Language").callbackData("lang_sw").build());

        rowsInline.add(row1);
        rowsInline.add(row2);
        rowsInline.add(row3);

        markupInline.setKeyboard(rowsInline);
        sm.setReplyMarkup(markupInline);

        try {
            execute(sm);
        } catch (TelegramApiException e) {
            System.err.println("Failed to send menu: " + e.getMessage());
        }
    }

    public void sendLanguageMenu(long chatId) {
        SendMessage sm = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text("Select your preferred language / Chagua lugha unayopendelea:")
                .build();

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        row1.add(InlineKeyboardButton.builder().text("English").callbackData("lang_en").build());
        row1.add(InlineKeyboardButton.builder().text("Kiswahili").callbackData("lang_sw").build());
        rowsInline.add(row1);
        markupInline.setKeyboard(rowsInline);
        sm.setReplyMarkup(markupInline);

        try {
            execute(sm);
        } catch (TelegramApiException e) {
            System.err.println("Failed to send language menu: " + e.getMessage());
        }
    }

    public void sendAdviceWithExecuteOption(long chatId, String text, String marketId, String side) {
        if (text.length() > 4096) {
            text = text.substring(0, 4090) + "\n[...]";
        }

        SendMessage sm = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text + "\n\nAuto-execute this trade?")
                .build();

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
        List<InlineKeyboardButton> row1 = new ArrayList<>();

        row1.add(InlineKeyboardButton.builder().text("Yes").callbackData("exe_yes_" + marketId + "_" + side).build());
        row1.add(InlineKeyboardButton.builder().text("No").callbackData("exe_no").build());

        rowsInline.add(row1);
        markupInline.setKeyboard(rowsInline);
        sm.setReplyMarkup(markupInline);

        try {
            execute(sm);
        } catch (TelegramApiException e) {
            System.err.println("Failed to send execution option: " + e.getMessage());
        }
    }

    public void sendText(long chatId, String text) {
        if (text.length() > 4096) {
            text = text.substring(0, 4090) + "\n[...]";
        }
        SendMessage sm = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .build();
        try {
            execute(sm);
        } catch (TelegramApiException e) {
            System.err.println("Failed to send message: " + e.getMessage());
        }
    }

    private void sendDefaultHelp(long chatId) {
        sendText(chatId, "NORT67 AI MARKET ANALYST\n\n" +
                "Available commands:\n" +
                "/trending - Hottest markets by volume\n" +
                "/advice <id> - Premium AI analysis for a market\n" +
                "/premium_advice <id> - Alias for /advice\n" +
                "/pay <tx_hash> - Submit x402 payment proof\n" +
                "/signals - Algorithmic trading signals\n" +
                "/markets - Live market listings\n" +
                "/portfolio - Wallet or paper summary\n" +
                "/papertrade <id> yes/no <amount> - Simulate trades\n" +
                "/lang - Set your preferred language (en/sw)\n\n" +
                "Settings:\n" +
                "/enable_autotrade - Enable automated execution\n" +
                "/disable_autotrade - Disable automated execution\n" +
                "/set_limit <amount> - Set auto-trade limit\n\n" +
                "Type /start for the interactive menu.");
    }
}
