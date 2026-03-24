package example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import example.client.BackendClient;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Bot extends TelegramLongPollingBot {

    private final BackendClient backend = new BackendClient();

    // In-memory language preference state
    private final Map<Long, String> userLanguages = new ConcurrentHashMap<>();

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
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            if (messageText.matches("[0-9a-fA-F]{64}")) {
                String verifyResult = backend.verifyPayment(messageText, chatId);
                try {
                    JSONObject verifyJson = new JSONObject(verifyResult);
                    if (verifyJson.getBoolean("success")) {
                        sendText(chatId, "✅ Payment verified! Unlocking premium advice...");
                        String lang = userLanguages.getOrDefault(chatId, "en");
                        String unlocked = backend.getPremiumAdvice("last_market_id", chatId, lang);
                        JSONObject unlockedJson = new JSONObject(unlocked);
                        sendText(chatId, "💎 *Premium Content:*\n" + unlockedJson.getString("content"));
                    } else {
                        sendText(chatId, "❌ Payment not verified. Details: " + verifyJson.optString("reason", "Unknown error"));
                    }
                } catch (Exception e) {
                    sendText(chatId, "Verification error: " + e.getMessage() + "\nRaw: " + verifyResult);
                }
                return;
            }

            String[] commandParts = messageText.split(" ");
            String command = commandParts[0].toLowerCase();
            String lang = userLanguages.getOrDefault(chatId, "en");

            switch (command) {
                case "/start":
                    sendMenu(chatId);
                    break;

                case "/lang":
                case "/language":
                    if (commandParts.length < 2) {
                        sendLanguageMenu(chatId);
                    } else {
                        String newLang = commandParts[1].toLowerCase();
                        if (newLang.equals("sw") || newLang.equals("en")) {
                            userLanguages.put(chatId, newLang);
                            sendText(chatId, "Language updated to: " + (newLang.equals("sw") ? "Kiswahili" : "English"));
                        } else {
                            sendText(chatId, "Unsupported language. Use '/lang en' or '/lang sw'.");
                        }
                    }
                    break;

                case "/enable_autotrade":
                    sendText(chatId, "Enabling auto-trade permissions...");
                    String enableRes = backend.updatePermissions(chatId, true, null);
                    sendText(chatId, "Result:\n" + enableRes);
                    break;

                case "/disable_autotrade":
                    sendText(chatId, "Disabling auto-trade permissions...");
                    String disableRes = backend.updatePermissions(chatId, false, null);
                    sendText(chatId, "Result:\n" + disableRes);
                    break;

                case "/set_limit":
                    if (commandParts.length < 2) {
                        sendText(chatId, "Usage: /set_limit <amount>\nExample: /set_limit 100");
                    } else {
                        try {
                            double limit = Double.parseDouble(commandParts[1]);
                            sendText(chatId, "Setting auto-trade limit to $" + limit + "...");
                            String limitRes = backend.updatePermissions(chatId, null, limit);
                            sendText(chatId, "Result:\n" + limitRes);
                        } catch (NumberFormatException e) {
                            sendText(chatId, "Invalid amount. Please use numbers only.");
                        }
                    }
                    break;

                case "/trending":
                    sendText(chatId, "Fetching top 10 trending markets...");
                    String trendingRaw = backend.getTrendingMarkets();
                    try {
                        JSONObject trendingJson = new JSONObject(trendingRaw);
                        JSONArray markets = trendingJson.getJSONArray("markets");
                        StringBuilder trending = new StringBuilder();
                        trending.append("TOP 10 TRENDING MARKETS\n");
                        trending.append("═══════════════════════════════════════\n\n");
                        for (int i = 0; i < markets.length(); i++) {
                            JSONObject m    = markets.getJSONObject(i);
                            String mid      = m.optString("id", "?");
                            String question = m.optString("question", "Unknown");
                            double volume   = m.optDouble("volume", 0);
                            double odds     = m.optDouble("current_odds", 0);
                            String expires  = m.optString("expires_at", "?").split(" ")[0];
                            String oddsStr  = odds > 0 ? String.format("%.0f%%", odds * 100) : "-";
                            trending.append(String.format("%d. %s\n", i + 1, question));
                            trending.append(String.format("   ID: %-10s  Volume: $%,.0f\n", mid, volume));
                            trending.append(String.format("   Odds: %-8s  Expires: %s\n", oddsStr, expires));
                            trending.append("\n");
                        }
                        trending.append("───────────────────────────────────────\n");
                        trending.append("Use /advice <id> for a full AI analysis.");
                        sendText(chatId, trending.toString());
                    } catch (Exception e) {
                        sendText(chatId, "Unable to retrieve trending markets at this time.");
                    }
                    break;

                case "/advice":
                    if (commandParts.length < 2) {
                        sendText(chatId, "Usage: /advice <market_id>\nExample: /advice 527079");
                    } else {
                        String mktId = commandParts[1];
                        sendText(chatId, "Analyzing market " + mktId + " (" + (lang.equals("sw") ? "Kiswahili" : "English") + ")...");
                        String premiumResponse = backend.getPremiumAdvice(mktId, chatId, lang);

                        if (premiumResponse.contains("503") || premiumResponse.startsWith("Connection failed") || premiumResponse.startsWith("Error 5")) {
                            sendText(chatId, "⚠️ The backend is currently waking up or unavailable. Please wait 30 seconds and try again.");
                            break;
                        }

                        try {
                            JSONObject json = new JSONObject(premiumResponse);

                            if (json.has("summary")) {
                                String marketId  = json.optString("market_id", mktId);
                                String summary   = json.optString("summary", "");
                                String why       = json.optString("why_trending", "");
                                String plan      = json.optString("suggested_plan", "WAIT");
                                String disclaimer = json.optString("disclaimer", "This is not financial advice.");
                                double confidence = json.optDouble("confidence", 0.5);
                                String staleWarn = json.optString("stale_data_warning", "");

                                String riskList = "None listed";
                                try {
                                    Object risksObj = json.opt("risk_factors");
                                    if (risksObj instanceof JSONArray) {
                                        JSONArray risks = (JSONArray) risksObj;
                                        StringBuilder sb = new StringBuilder();
                                        for (int i = 0; i < risks.length(); i++)
                                            sb.append("• ").append(risks.getString(i)).append("\n");
                                        riskList = sb.toString().trim();
                                    }
                                } catch (Exception ignored) {}

                                StringBuilder msg = new StringBuilder();
                                msg.append("💎 MARKET ANALYSIS: ").append(marketId).append("\n\n");
                                msg.append("SUMMARY\n").append(summary).append("\n\n");
                                msg.append("TREND ANALYSIS\n").append(why).append("\n\n");
                                msg.append("RISK FACTORS\n").append(riskList).append("\n\n");
                                msg.append("RECOMMENDED ACTION: ").append(plan).append("\n");
                                msg.append("CONFIDENCE: ").append(String.format("%.0f%%", confidence * 100)).append("\n");
                                if (!staleWarn.isEmpty())
                                    msg.append("\n⚠️ DATA WARNING: ").append(staleWarn).append("\n");
                                msg.append("\n═══════════════════════\n_").append(disclaimer).append("_");

                                // Show Execution Confirmation if recommended plan contains actionable side (YES/NO/BUY/SELL)
                                if (!plan.toUpperCase().contains("WAIT")) {
                                    String side = "YES";
                                    if (plan.toUpperCase().contains("NO") || plan.toUpperCase().contains("SELL")) {
                                        side = "NO";
                                    }
                                    sendAdviceWithExecuteOption(chatId, msg.toString(), mktId, side);
                                } else {
                                    sendText(chatId, msg.toString());
                                }
                            } else {
                                sendText(chatId, "💎 *Premium Content:*\n" + json.getString("content"));
                            }
                        } catch (Exception e) {
                            if (premiumResponse.contains("402") || premiumResponse.contains("PAYMENT-REQUIRED")) {
                                try {
                                    JSONObject paymentJson = new JSONObject(premiumResponse);
                                    double amount  = paymentJson.getDouble("amount");
                                    String address = paymentJson.getString("address");
                                    String asset   = paymentJson.getString("asset");
                                    sendText(chatId, "💎 *Premium Content Locked*\n\nTo unlock, send $"
                                            + amount + " " + asset + " to:\n`" + address + "`\non Base network.\n\nReply with your transaction hash.");
                                } catch (Exception ex) {
                                    sendText(chatId, "Payment required, but could not parse payment details.\nRaw: " + premiumResponse);
                                }
                            } else {
                                sendText(chatId, "Error: " + e.getMessage() + "\nRaw: " + premiumResponse.substring(0, Math.min(200, premiumResponse.length())));
                            }
                        }
                    }
                    break;

                case "/portfolio":
                    sendText(chatId, "PORTFOLIO SUMMARY (Paper Trading Mode)\n" +
                            "═══════════════════════════════════════\n" +
                            "Account Balance: $1,000.00\n" +
                            "Active Positions: 0\n" +
                            "Total PNL: $0.00\n" +
                            "Win Rate: N/A\n\n" +
                            "Paper trading environment - no real funds at risk.");
                    break;

                case "/markets":
                    sendText(chatId, "Fetching market data...");
                    String rawMarkets = backend.getMarkets();
                    if (rawMarkets == null || rawMarkets.trim().isEmpty())
                        rawMarkets = "No market data available at this time.";
                    sendText(chatId, "LIVE MARKETS\n═══════════════════════\n\n" + rawMarkets);
                    break;

                case "/signals":
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
                        signals.append("TOP 10 MARKET SIGNALS\n");
                        signals.append("═══════════════════════════════════════\n\n");
                        for (int i = 0; i < signalList.length(); i++) {
                            JSONObject s    = signalList.getJSONObject(i);
                            String mid      = s.optString("market_id", "?");
                            String question = s.optString("question", "Unknown");
                            double score    = s.optDouble("score", 0);
                            String reason   = s.optString("reason", "");
                            double volume   = s.optDouble("volume", 0);
                            double odds     = s.optDouble("current_odds", 0);
                            String oddsStr  = odds > 0 ? String.format("%.0f%%", odds * 100) : "-";
                            int scorePct    = (int) Math.round(score * 100);
                            signals.append(String.format("%d. %s\n", i + 1, question));
                            signals.append(String.format("   ID: %-10s  Score: %d%%\n", mid, scorePct));
                            signals.append(String.format("   Volume: $%,.0f  |  Odds: %s\n", volume, oddsStr));
                            signals.append(String.format("   Note: %s\n", reason));
                            signals.append("\n");
                        }
                        signals.append("───────────────────────────────────────\n");
                        signals.append("Use /advice <id> to get a full AI analysis.");
                        sendText(chatId, signals.toString());
                    } catch (Exception e) {
                        sendText(chatId, "Signal parse error: " + e.getMessage() + "\nRaw preview: " + signalsRaw.substring(0, Math.min(200, signalsRaw.length())));
                    }
                    break;

                case "/papertrade":
                    if (commandParts.length < 4) {
                        sendText(chatId, "PAPER TRADE ORDER\n═══════════════════════\n" +
                                "Usage: /papertrade <market_id> <yes/no> <amount>\n" +
                                "Example: /papertrade 527079 yes 50\n\n" +
                                "Simulates trades without real money risk.");
                    } else {
                        try {
                            String result = backend.placePaperTrade(chatId, commandParts[1], commandParts[2], Double.parseDouble(commandParts[3]));
                            sendText(chatId, "PAPER TRADE EXECUTED\n═══════════════════════\n\n" + result);
                        } catch (NumberFormatException e) {
                            sendText(chatId, "Invalid amount. Please use numbers only (e.g. 50, 100.50).");
                        }
                    }
                    break;

                default:
                    sendText(chatId, "NORT67 AI MARKET ANALYST\n\n" +
                            "Available commands:\n" +
                            "• /trending - Hottest markets by volume\n" +
                            "• /advice <id> - AI analysis for market\n" +
                            "• /signals - Algorithmic trading signals\n" +
                            "• /markets - Live market listings\n" +
                            "• /portfolio - Paper trading summary\n" +
                            "• /papertrade <id> yes/no <amount> - Simulate trades\n" +
                            "• /lang - Set your preferred language (en/sw)\n\n" +
                            "Settings:\n" +
                            "• /enable_autotrade - Enable automated AI trading execution\n" +
                            "• /disable_autotrade - Disable automated trading\n" +
                            "• /set_limit <$amount> - Set auto-trade execution limit\n\n" +
                            "Type /start for interactive menu.");
            }
        }

        // Button callbacks
        else if (update.hasCallbackQuery()) {
            String callData = update.getCallbackQuery().getData();
            long chatId = update.getCallbackQuery().getMessage().getChatId();

            if (callData.startsWith("lang_")) {
                String newLang = callData.substring(5);
                userLanguages.put(chatId, newLang);
                sendText(chatId, "Language updated to: " + (newLang.equals("sw") ? "Kiswahili" : "English"));
            }
            else if (callData.startsWith("exe_yes_")) {
                // Format: exe_yes_<marketId>_<side>
                String[] parts = callData.split("_");
                if (parts.length >= 4) {
                    String mktId = parts[2];
                    String side = parts[3];
                    // Using default limit of 50$ for auto execution demo
                    double amount = 50.0;
                    sendText(chatId, "Executing trade... Placing $" + amount + " on " + side + " for market " + mktId);
                    String result = backend.placePaperTrade(chatId, mktId, side, amount);
                    sendText(chatId, "Trade Result:\n" + result);
                }
            }
            else if (callData.equals("exe_no")) {
                sendText(chatId, "Trade auto-execution cancelled.");
            }
            else {
                // Existing callbacks
                switch (callData) {
                    case "btn_trending":
                        String trendingRaw2 = backend.getTrendingMarkets();
                        try {
                            JSONObject trendingJson2 = new JSONObject(trendingRaw2);
                            JSONArray markets2 = trendingJson2.getJSONArray("markets");
                            StringBuilder trending2 = new StringBuilder();
                            trending2.append("TOP 10 TRENDING MARKETS\n");
                            trending2.append("═══════════════════════════════════════\n\n");
                            for (int i = 0; i < markets2.length(); i++) {
                                JSONObject m    = markets2.getJSONObject(i);
                                String mid      = m.optString("id", "?");
                                String question = m.optString("question", "Unknown");
                                double volume   = m.optDouble("volume", 0);
                                double odds     = m.optDouble("current_odds", 0);
                                String expires  = m.optString("expires_at", "?").split(" ")[0];
                                String oddsStr  = odds > 0 ? String.format("%.0f%%", odds * 100) : "-";
                                trending2.append(String.format("%d. %s\n", i + 1, question));
                                trending2.append(String.format("   ID: %s  |  $%,.0f  |  %s  |  %s\n\n", mid, volume, oddsStr, expires));
                            }
                            trending2.append("Use /advice <id> for a full AI analysis.");
                            sendText(chatId, trending2.toString());
                        } catch (Exception e) {
                            sendText(chatId, "Unable to retrieve trending markets at this time.");
                        }
                        break;

                    case "btn_advice":
                        sendText(chatId, "AI ADVICE\n═════════════\n" +
                                "Usage: /advice <market_id>\n" +
                                "Example: /advice 527079\n\n" +
                                "Get detailed AI-powered analysis for any market.");
                        break;

                    case "btn_portfolio":
                        String summary = backend.getWalletSummary(chatId);
                        sendText(chatId, "PORTFOLIO SUMMARY\n" +
                                "═══════════════════════════════════════\n" + summary);
                        break;
                }
            }
        }
    }

    public void sendMenu(long chatId) {
        SendMessage sm = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text("NORT67 AI MARKET ANALYST\n\n" +
                        "Real-time prediction market analysis powered by:\n" +
                        "• Live market data feeds\n" +
                        "• AI reasoning engine\n" +
                        "• Web intelligence gathering\n\n" +
                        "Paper trading environment - risk-free strategy testing\n\n" +
                        "Commands: /advice /signals /trending /portfolio /lang")
                .build();

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        row1.add(InlineKeyboardButton.builder().text("Trending Markets").callbackData("btn_trending").build());
        row1.add(InlineKeyboardButton.builder().text("AI Advice").callbackData("btn_advice").build());

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        row2.add(InlineKeyboardButton.builder().text("Portfolio").callbackData("btn_portfolio").build());

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        row3.add(InlineKeyboardButton.builder().text("🌎 Set Language").callbackData("lang_sw").build()); // Can change default behavior

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
        if (text.length() > 4096) text = text.substring(0, 4090) + "\n[...]";

        SendMessage sm = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text + "\n\n🚀 Auto-execute this trade?")
                .build();

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
        List<InlineKeyboardButton> row1 = new ArrayList<>();

        row1.add(InlineKeyboardButton.builder().text("✅ Yes").callbackData("exe_yes_" + marketId + "_" + side).build());
        row1.add(InlineKeyboardButton.builder().text("❌ No").callbackData("exe_no").build());

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
}
