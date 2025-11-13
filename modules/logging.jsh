// ============================================================================
// ANAWBPPS - Logging Module
// ============================================================================
// Provides console logging with optional file output
// ============================================================================

#ifndef __ANAWBPPS_LOGGING_MODULE__
#define __ANAWBPPS_LOGGING_MODULE__

// Global state
var LOG_fileEnabled = false;
var LOG_fileHandle = null;
var LOG_filePath = "";

/**
 * Initialize logging to file
 * @param {string} outputDir - Directory for log files (optional, defaults to ~/.anawbpps/logs)
 * @returns {boolean} true if initialized successfully
 */
function LOG_init(outputDir){
    if (LOG_fileHandle){
        Console.warningln("[log] Already initialized, closing previous file");
        LOG_close();
    }

    try{
        // Determine output directory
        var logDir = outputDir;
        if (!logDir){
            var homeDir = File.homeDirectory;
            logDir = homeDir + "/.anawbpps/logs";
        }

        // Create directory if needed
        if (!File.directoryExists(logDir)){
            File.createDirectory(logDir, true);
        }

        // Generate filename with timestamp
        var now = new Date();
        var year = now.getFullYear();
        var month = String(now.getMonth() + 1);
        if (month.length < 2) month = "0" + month;
        var day = String(now.getDate());
        if (day.length < 2) day = "0" + day;
        var hour = String(now.getHours());
        if (hour.length < 2) hour = "0" + hour;
        var minute = String(now.getMinutes());
        if (minute.length < 2) minute = "0" + minute;
        var second = String(now.getSeconds());
        if (second.length < 2) second = "0" + second;

        var filename = year + "-" + month + "-" + day + "_" + hour + "-" + minute + "-" + second + ".log";
        LOG_filePath = logDir + "/" + filename;

        // Open file for writing
        LOG_fileHandle = new File();
        LOG_fileHandle.createForWriting(LOG_filePath);
        LOG_fileEnabled = true;

        Console.writeln("[log] Logging to file: " + LOG_filePath);
        LOG_write("=".repeat(80));
        LOG_write("ANAWBPPS Log File");
        LOG_write("Started: " + now.toString());
        LOG_write("=".repeat(80));

        return true;
    }catch(e){
        Console.criticalln("[log] Failed to initialize file logging: " + e);
        LOG_fileEnabled = false;
        LOG_fileHandle = null;
        LOG_filePath = "";
        return false;
    }
}

/**
 * Close log file
 */
function LOG_close(){
    if (LOG_fileHandle){
        try{
            LOG_write("=".repeat(80));
            LOG_write("Log file closed: " + (new Date()).toString());
            LOG_write("=".repeat(80));
            LOG_fileHandle.close();
            Console.writeln("[log] Log file closed: " + LOG_filePath);
        }catch(e){
            Console.warningln("[log] Error closing log file: " + e);
        }
        LOG_fileHandle = null;
        LOG_fileEnabled = false;
        LOG_filePath = "";
    }
}

/**
 * Write raw text to log file (internal)
 */
function LOG_write(text){
    if (LOG_fileEnabled && LOG_fileHandle){
        try{
            LOG_fileHandle.outTextLn(text);
        }catch(e){
            // Disable logging on error to avoid spam
            LOG_fileEnabled = false;
            Console.warningln("[log] Disabled file logging due to write error: " + e);
        }
    }
}

/**
 * Write timestamped message to log file
 */
function LOG_writeTimestamped(level, text){
    if (!LOG_fileEnabled) return;

    var now = new Date();
    var hour = String(now.getHours());
    if (hour.length < 2) hour = "0" + hour;
    var minute = String(now.getMinutes());
    if (minute.length < 2) minute = "0" + minute;
    var second = String(now.getSeconds());
    if (second.length < 2) second = "0" + second;
    var ms = String(now.getMilliseconds());
    while (ms.length < 3) ms = "0" + ms;

    var timestamp = hour + ":" + minute + ":" + second + "." + ms;
    var line = "[" + timestamp + "] [" + level + "] " + text;
    LOG_write(line);
}

// ============================================================================
// Console wrappers - use these instead of Console.* directly
// ============================================================================

function LOG_writeln(text){
    Console.writeln(text);
    LOG_writeTimestamped("INFO", text);
}

function LOG_noteln(text){
    Console.noteln(text);
    LOG_writeTimestamped("NOTE", text);
}

function LOG_warningln(text){
    Console.warningln(text);
    LOG_writeTimestamped("WARN", text);
}

function LOG_criticalln(text){
    Console.criticalln(text);
    LOG_writeTimestamped("ERROR", text);
}

/**
 * Flush log file to disk
 */
function LOG_flush(){
    if (LOG_fileHandle){
        try{
            LOG_fileHandle.flush();
        }catch(e){
            Console.warningln("[log] Failed to flush log file: " + e);
        }
    }
}

// ============================================================================
// Console hijacking - redirect Console.* to log file
// ============================================================================

var LOG_originalConsole = null;

/**
 * Enable automatic logging of all Console.* calls
 * Call this after LOG_init() to redirect all console output to log file
 */
function LOG_hijackConsole(){
    if (LOG_originalConsole){
        // Already hijacked
        return;
    }

    // Save original methods
    LOG_originalConsole = {
        writeln: Console.writeln,
        noteln: Console.noteln,
        warningln: Console.warningln,
        criticalln: Console.criticalln
    };

    // Replace with logging versions
    Console.writeln = function(text){
        LOG_originalConsole.writeln.call(Console, text);
        LOG_writeTimestamped("INFO", text);
    };

    Console.noteln = function(text){
        LOG_originalConsole.noteln.call(Console, text);
        LOG_writeTimestamped("NOTE", text);
    };

    Console.warningln = function(text){
        LOG_originalConsole.warningln.call(Console, text);
        LOG_writeTimestamped("WARN", text);
    };

    Console.criticalln = function(text){
        LOG_originalConsole.criticalln.call(Console, text);
        LOG_writeTimestamped("ERROR", text);
    };
}

/**
 * Restore original Console.* methods
 */
function LOG_restoreConsole(){
    if (!LOG_originalConsole){
        return;
    }

    Console.writeln = LOG_originalConsole.writeln;
    Console.noteln = LOG_originalConsole.noteln;
    Console.warningln = LOG_originalConsole.warningln;
    Console.criticalln = LOG_originalConsole.criticalln;

    LOG_originalConsole = null;
}

#endif // __ANAWBPPS_LOGGING_MODULE__
