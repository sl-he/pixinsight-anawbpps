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

/**
 * Sends module completion notification with group details
 * @param {object} params - { botToken, chatId, moduleName, groupNames, processedFiles, skippedFiles, elapsedTime }
 * @returns {boolean} success
 */
function NOTIF_sendModuleCompletionTelegram(params){
    var botToken = params.botToken;
    var chatId = params.chatId;
    var moduleName = params.moduleName || "Module";
    var groupNames = params.groupNames || [];
    var processedFiles = params.processedFiles || 0;
    var skippedFiles = params.skippedFiles || 0;
    var elapsedTime = params.elapsedTime || 0;

    if (!botToken || !chatId){
        return false;
    }

    // Format time
    var timeStr = CU_fmtElapsedMS(elapsedTime);

    // Build message
    var message = "🔭 ANAWBPPS - " + moduleName + " Complete\n\n";
    message += "✅ Processed: " + processedFiles + " files\n";
    if (skippedFiles > 0){
        message += "⊘ Skipped: " + skippedFiles + " files\n";
    }
    message += "⏱ Time: " + timeStr + "\n";

    // Add group names (limit to 10 groups to avoid long messages)
    if (groupNames.length > 0){
        message += "\n📂 Groups:\n";
        var maxGroups = Math.min(groupNames.length, 10);
        for (var i=0; i<maxGroups; i++){
            message += "  • " + groupNames[i] + "\n";
        }
        if (groupNames.length > 10){
            message += "  ... and " + (groupNames.length - 10) + " more";
        }
    }

    return NOTIF_sendTelegram(botToken, chatId, message);
}

#endif // __ANAWBPPS_NOTIFICATIONS_MODULE__
