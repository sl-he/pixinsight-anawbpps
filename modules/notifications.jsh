// ============================================================================
// ANAWBPPS - Notifications Module
// ============================================================================
// Handles notification delivery via various channels (Telegram, etc.)
// ============================================================================

#ifndef __ANAWBPPS_NOTIFICATIONS_MODULE__
#define __ANAWBPPS_NOTIFICATIONS_MODULE__

// ============================================================================
// Telegram Notifications
// ============================================================================

/**
 * Sends a message via Telegram Bot API
 * @param {string} botToken - Telegram bot token (from @BotFather)
 * @param {string} chatId - Telegram chat ID (from @userinfobot)
 * @param {string} message - Message text to send
 * @returns {boolean} true if sent successfully, false otherwise
 */
function NOTIF_sendTelegram(botToken, chatId, message){
    if (!botToken || !chatId || !message){
        Console.warningln("[notifications] Missing required parameters for Telegram");
        return false;
    }

    try{
        var url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        var postData = "chat_id=" + chatId + "&text=" + encodeURIComponent(message);

        Console.writeln("[notifications] Sending Telegram notification...");

        var nt = new NetworkTransfer();
        nt.setURL(url);
        nt.setCustomHTTPHeaders(["Content-Type: application/x-www-form-urlencoded"]);

        var success = nt.post(postData);

        if (success){
            Console.noteln("[notifications] ✓ Telegram notification sent");
            return true;
        }else{
            Console.warningln("[notifications] ✗ Failed to send Telegram notification");
            Console.warningln("[notifications] Error: " + nt.errorInformation);
            return false;
        }
    }catch(e){
        Console.criticalln("[notifications] ✗ Telegram exception: " + e.toString());
        return false;
    }
}

/**
 * Sends processing completion notification via Telegram
 * @param {object} params - { botToken, chatId, totalTime, errors }
 * @returns {boolean} success
 */
function NOTIF_sendCompletionTelegram(params){
    var botToken = params.botToken;
    var chatId = params.chatId;
    var totalTime = params.totalTime || 0;
    var errors = params.errors || 0;

    if (!botToken || !chatId){
        Console.warningln("[notifications] Telegram credentials not configured");
        return false;
    }

    // Format time
    var hours = Math.floor(totalTime / 3600000);
    var minutes = Math.floor((totalTime % 3600000) / 60000);
    var seconds = Math.floor((totalTime % 60000) / 1000);
    var timeStr = "";
    if (hours > 0) timeStr += hours + "h ";
    if (minutes > 0 || hours > 0) timeStr += minutes + "m ";
    timeStr += seconds + "s";

    // Build message
    var message = "🔭 ANAWBPPS Processing Complete\n\n";
    message += "⏱ Total time: " + timeStr + "\n";

    if (errors > 0){
        message += "⚠️ Completed with " + errors + " error(s)\n";
    }else{
        message += "✅ All stages completed successfully\n";
    }

    message += "\nCheck PixInsight for details.";

    return NOTIF_sendTelegram(botToken, chatId, message);
}

/**
 * Sends stage completion notification via Telegram
 * @param {object} params - { botToken, chatId, stageName, success, error }
 * @returns {boolean} success
 */
function NOTIF_sendStageTelegram(params){
    var botToken = params.botToken;
    var chatId = params.chatId;
    var stageName = params.stageName || "Unknown";
    var success = params.success !== false;
    var error = params.error || "";

    if (!botToken || !chatId){
        return false;
    }

    var message = "🔭 ANAWBPPS - " + stageName + "\n\n";

    if (success){
        message += "✅ Stage completed successfully";
    }else{
        message += "❌ Stage failed\n";
        if (error) message += "Error: " + error;
    }

    return NOTIF_sendTelegram(botToken, chatId, message);
}

#endif // __ANAWBPPS_NOTIFICATIONS_MODULE__
