/*
 * ANAWBPPS - Master Files Reorganizer
 * Reorganizes existing master calibration files into new directory structure
 *
 * New structure:
 *   mastersPath/!!!DARKS_LIB/SETUP/DARKS_YYYY_MM_DD/MasterDark_...xisf
 *   mastersPath/!!!DARKFLATS_LIB/SETUP/DARKFLATS_YYYY_MM_DD/MasterDarkFlat_...xisf
 *   mastersPath/!!!FLATS_LIB/SETUP/FLATS_YYYY_MM_DD/MasterFlat_...xisf
 *   mastersPath/!!!BIASES_LIB/SETUP/BIASES_YYYY_MM_DD/MasterBias_...xisf
 *
 * IMPORTANT: This script COPIES files (does not move/delete originals)
 * Uses FITS format for reading keywords (lightweight, no full image loading)
 *
 * Copyright (C) 2024-2025 sl-he
 */
#feature-id    sl-he > ReorganizeMasters
#feature-info  Reorganizes existing master calibration files into new directory structure.
#define TITLE "ReorganizeMasters"
#define VERSION "1.0.0"

#include <pjsr/StdButton.jsh>
#include <pjsr/StdDialogCode.jsh>
#include <pjsr/FrameStyle.jsh>
#include <pjsr/TextAlign.jsh>
#include <pjsr/Sizer.jsh>

#include "modules/common_utils.jsh"
#include "modules/fits_indexing.jsh"

// ============================================================================
// Helper Functions
// ============================================================================

// Reuse _fi_readFitsKeywords from fits_indexing.jsh
function RM_readFitsKeywords(filePath){
    return _fi_readFitsKeywords(filePath);
}

function RM_extractSetup(keywords){
    var telescop = CU_upperOrNull(CU_first(keywords["TELESCOP"], keywords["TELESCOPE"]));
    var instrume = CU_upperOrNull(CU_first(keywords["INSTRUME"], keywords["INSTRUMENT"]));

    var setup = "";
    if (telescop) setup += CU_sanitizeKey(telescop);
    if (instrume){
        if (setup) setup += "_";
        setup += CU_sanitizeKey(instrume);
    }

    return setup || "UNKNOWN_SETUP";
}

function RM_extractDate(keywords){
    var dateObs = keywords["DATE-OBS"];
    if (!dateObs) return null;

    var dateOnly = CU_extractDateOnly(dateObs);
    if (!dateOnly) return null;

    // Convert YYYY-MM-DD to YYYY_MM_DD
    return dateOnly.replace(/-/g, '_');
}

function RM_extractType(keywords){
    var imageTyp = CU_upperOrNull(keywords["IMAGETYP"]);
    if (!imageTyp) return null;

    // Master Dark, Master Flat, Master Bias, etc.
    if (imageTyp.indexOf("DARK") >= 0){
        // Check if it's a DarkFlat (has FILTER keyword)
        if (keywords["FILTER"]) return "DarkFlat";
        return "Dark";
    }
    if (imageTyp.indexOf("FLAT") >= 0) return "Flat";
    if (imageTyp.indexOf("BIAS") >= 0) return "Bias";

    return null;
}

function RM_generateTargetPath(filePath, keywords, targetBasePath){
    var setup = RM_extractSetup(keywords);
    var dateStr = RM_extractDate(keywords);
    var type = RM_extractType(keywords);

    if (!setup || !dateStr || !type){
        Console.warningln("[rm] Skipping (missing info): " + CU_basename(filePath));
        Console.warningln("[rm]   setup=" + setup + ", date=" + dateStr + ", type=" + type);
        return null;
    }

    // Determine library folder
    var libFolder;
    var typePrefix;
    if (type == "Dark"){
        libFolder = "!!!DARKS_LIB";
        typePrefix = "DARKS";
    } else if (type == "DarkFlat"){
        libFolder = "!!!DARKFLATS_LIB";
        typePrefix = "DARKFLATS";
    } else if (type == "Flat"){
        libFolder = "!!!FLATS_LIB";
        typePrefix = "FLATS";
    } else if (type == "Bias"){
        libFolder = "!!!BIASES_LIB";
        typePrefix = "BIASES";
    } else {
        return null;
    }

    // Build target path
    var dateFolder = typePrefix + "_" + dateStr;
    var targetDir = targetBasePath + "/" + libFolder + "/" + setup + "/" + dateFolder;
    var fileName = CU_basename(filePath);

    return targetDir + "/" + fileName;
}

function RM_findMasterFiles(sourcePath, recursive){
    Console.noteln("[rm] Scanning for master files in: " + sourcePath);

    var files = [];

    // Use existing _fi_walkDir if recursive, otherwise scan single directory
    if (recursive){
        _fi_walkDir(sourcePath, function(fullPath){
            var name = CU_basename(fullPath);
            // Check if it's a master file (*.xisf and contains "Master" in name)
            if (name.toLowerCase().indexOf(".xisf") > 0 && name.indexOf("Master") >= 0){
                files.push(fullPath);
            }
        });
    } else {
        // Non-recursive: use _fi_listNames for single directory
        var names = _fi_listNames(sourcePath);
        for (var i = 0; i < names.length; i++){
            var name = names[i];
            if (name.toLowerCase().indexOf(".xisf") > 0 && name.indexOf("Master") >= 0){
                files.push(sourcePath + "/" + name);
            }
        }
    }

    Console.noteln("[rm] Found " + files.length + " master files");
    return files;
}

function RM_copyFile(sourcePath, targetPath, dryRun, moveFiles){
    if (sourcePath == targetPath){
        Console.noteln("[rm] SKIP (source and target are the same): " + CU_basename(sourcePath));
        return true;
    }

    // Extract directory from target path
    var lastSlash = targetPath.lastIndexOf('/');
    var targetDir = targetPath.substring(0, lastSlash);

    if (dryRun){
        Console.noteln("[rm] DRY RUN: Would " + (moveFiles ? "move" : "copy") + ":");
        Console.noteln("[rm]   From: " + sourcePath);
        Console.noteln("[rm]   To:   " + targetPath);
        return true;
    }

    // Create target directory if needed
    if (!File.directoryExists(targetDir)){
        try {
            // File.createDirectory doesn't return value, but throws on error
            File.createDirectory(targetDir, true);

            // Check if it was actually created
            if (!File.directoryExists(targetDir)){
                Console.criticalln("\n[rm] ERROR: Directory not created: " + targetDir);
                return false;
            }
        } catch(e){
            Console.criticalln("\n[rm] ERROR: Exception creating directory: " + e);
            return false;
        }
    }

    // Check if target file already exists
    if (File.exists(targetPath)){
        return false; // Skip silently
    }

    // Copy file
    try {
        File.copyFile(targetPath, sourcePath); // PixInsight: File.copyFile(dest, src)

        // Delete original if move mode is enabled
        if (moveFiles){
            File.remove(sourcePath);
        }

        return true;
    } catch(e){
        Console.criticalln("\n[rm] ERROR: Failed to " + (moveFiles ? "move" : "copy") + " file: " + e);
        return false;
    }
}

// ============================================================================
// UI Dialog
// ============================================================================

function ReorganizeMastersDialog(){
    this.__base__ = Dialog;
    this.__base__();

    var self = this;

    // Default values
    this.sourcePath = "";
    this.targetPath = "";
    this.dryRun = true;
    this.recursive = true;
    this.moveFiles = false;

    // Source Path
    this.sourceLabel = new Label(this);
    this.sourceLabel.text = "Source directory (existing masters):";
    this.sourceLabel.textAlignment = TextAlign_Left | TextAlign_VertCenter;

    this.sourceEdit = new Edit(this);
    this.sourceEdit.text = this.sourcePath;
    this.sourceEdit.minWidth = 500;

    this.sourceBrowseButton = new ToolButton(this);
    this.sourceBrowseButton.icon = this.scaledResource(":/browser/select-file.png");
    this.sourceBrowseButton.setScaledFixedSize(20, 20);
    this.sourceBrowseButton.toolTip = "Select source directory";
    this.sourceBrowseButton.onClick = function(){
        var dir = new GetDirectoryDialog;
        dir.caption = "Select Source Directory";
        dir.initialPath = self.sourceEdit.text;
        if (dir.execute()){
            self.sourceEdit.text = dir.directory;
        }
    };

    this.sourceSizer = new HorizontalSizer;
    this.sourceSizer.spacing = 4;
    this.sourceSizer.add(this.sourceEdit, 100);
    this.sourceSizer.add(this.sourceBrowseButton);

    // Target Path
    this.targetLabel = new Label(this);
    this.targetLabel.text = "Target directory (reorganized masters):";
    this.targetLabel.textAlignment = TextAlign_Left | TextAlign_VertCenter;

    this.targetEdit = new Edit(this);
    this.targetEdit.text = this.targetPath;
    this.targetEdit.minWidth = 500;

    this.targetBrowseButton = new ToolButton(this);
    this.targetBrowseButton.icon = this.scaledResource(":/browser/select-file.png");
    this.targetBrowseButton.setScaledFixedSize(20, 20);
    this.targetBrowseButton.toolTip = "Select target directory";
    this.targetBrowseButton.onClick = function(){
        var dir = new GetDirectoryDialog;
        dir.caption = "Select Target Directory";
        dir.initialPath = self.targetEdit.text;
        if (dir.execute()){
            self.targetEdit.text = dir.directory;
        }
    };

    this.targetSizer = new HorizontalSizer;
    this.targetSizer.spacing = 4;
    this.targetSizer.add(this.targetEdit, 100);
    this.targetSizer.add(this.targetBrowseButton);

    // Options
    this.dryRunCheckBox = new CheckBox(this);
    this.dryRunCheckBox.text = "Dry run (show what would happen, don't copy files)";
    this.dryRunCheckBox.checked = this.dryRun;

    this.recursiveCheckBox = new CheckBox(this);
    this.recursiveCheckBox.text = "Recursive search in subdirectories";
    this.recursiveCheckBox.checked = this.recursive;

    this.moveCheckBox = new CheckBox(this);
    this.moveCheckBox.text = "Move files (delete originals after copying)";
    this.moveCheckBox.checked = this.moveFiles;

    // Info text
    this.infoLabel = new Label(this);
    this.infoLabel.text = "This script will COPY (not move) master files into new directory structure:\n" +
                         "  • !!!DARKS_LIB/SETUP/DARKS_YYYY_MM_DD/\n" +
                         "  • !!!DARKFLATS_LIB/SETUP/DARKFLATS_YYYY_MM_DD/\n" +
                         "  • !!!FLATS_LIB/SETUP/FLATS_YYYY_MM_DD/\n" +
                         "  • !!!BIASES_LIB/SETUP/BIASES_YYYY_MM_DD/\n\n" +
                         "Original files will NOT be deleted.";
    this.infoLabel.frameStyle = FrameStyle_Box;
    this.infoLabel.margin = 6;
    this.infoLabel.wordWrapping = true;

    // Buttons
    this.runButton = new PushButton(this);
    this.runButton.text = "Run";
    this.runButton.icon = this.scaledResource(":/icons/ok.png");
    this.runButton.onClick = function(){
        self.sourcePath = self.sourceEdit.text.trim();
        self.targetPath = self.targetEdit.text.trim();
        self.dryRun = self.dryRunCheckBox.checked;
        self.recursive = self.recursiveCheckBox.checked;
        self.moveFiles = self.moveCheckBox.checked;

        if (!self.sourcePath){
            new MessageBox("Source directory is required", TITLE, StdIcon_Error).execute();
            return;
        }
        if (!self.targetPath){
            new MessageBox("Target directory is required", TITLE, StdIcon_Error).execute();
            return;
        }

        self.ok();
    };

    this.cancelButton = new PushButton(this);
    this.cancelButton.text = "Cancel";
    this.cancelButton.icon = this.scaledResource(":/icons/cancel.png");
    this.cancelButton.onClick = function(){
        self.cancel();
    };

    this.buttonsSizer = new HorizontalSizer;
    this.buttonsSizer.spacing = 6;
    this.buttonsSizer.addStretch();
    this.buttonsSizer.add(this.runButton);
    this.buttonsSizer.add(this.cancelButton);

    // Layout
    this.sizer = new VerticalSizer;
    this.sizer.margin = 8;
    this.sizer.spacing = 6;
    this.sizer.add(this.sourceLabel);
    this.sizer.add(this.sourceSizer);
    this.sizer.addSpacing(4);
    this.sizer.add(this.targetLabel);
    this.sizer.add(this.targetSizer);
    this.sizer.addSpacing(8);
    this.sizer.add(this.dryRunCheckBox);
    this.sizer.add(this.recursiveCheckBox);
    this.sizer.add(this.moveCheckBox);
    this.sizer.addSpacing(8);
    this.sizer.add(this.infoLabel);
    this.sizer.addSpacing(8);
    this.sizer.add(this.buttonsSizer);

    this.windowTitle = TITLE + " v" + VERSION;
    this.adjustToContents();
    this.setFixedSize();
}

ReorganizeMastersDialog.prototype = new Dialog;

// ============================================================================
// Main Function
// ============================================================================

function RM_reorganizeMasters(sourcePath, targetPath, dryRun, recursive, moveFiles){
    Console.noteln("=".repeat(80));
    Console.noteln("[rm] " + TITLE + " v" + VERSION);
    Console.noteln("[rm] Source: " + sourcePath);
    Console.noteln("[rm] Target: " + targetPath);
    Console.noteln("[rm] Dry run: " + (dryRun ? "YES" : "NO"));
    Console.noteln("[rm] Recursive: " + (recursive ? "YES" : "NO"));
    Console.noteln("[rm] Move files: " + (moveFiles ? "YES (delete originals)" : "NO (copy only)"));
    Console.noteln("=".repeat(80));

    // Find all master files
    var files = RM_findMasterFiles(sourcePath, recursive);

    if (files.length == 0){
        Console.warningln("[rm] No master files found!");
        return;
    }

    // Process each file
    var stats = {
        total: files.length,
        copied: 0,
        skipped: 0,
        errors: 0
    };

    Console.noteln("");
    Console.show();

    for (var i = 0; i < files.length; i++){
        var filePath = files[i];

        // Update progress
        var percent = Math.round((i / files.length) * 100);
        Console.writeln(format("<end><cbr><br>Processing: %d/%d (%d%%) - %s",
            i + 1, files.length, percent, CU_basename(filePath)));
        processEvents();

        // Read FITS keywords
        var keywords = RM_readFitsKeywords(filePath);
        if (!keywords){
            Console.warningln("[rm] ERROR: Failed to read keywords from: " + filePath);
            stats.errors++;
            continue;
        }

        // Generate target path
        var targetFilePath = RM_generateTargetPath(filePath, keywords, targetPath);
        if (!targetFilePath){
            stats.skipped++;
            continue;
        }

        // Copy or move file
        if (RM_copyFile(filePath, targetFilePath, dryRun, moveFiles)){
            stats.copied++;
        } else {
            stats.errors++;
        }
    }

    Console.writeln("<end><cbr><br>");

    // Print summary
    Console.noteln("");
    Console.noteln("=".repeat(80));
    Console.noteln("[rm] Summary:");
    Console.noteln("[rm]   Total files: " + stats.total);
    if (moveFiles){
        Console.noteln("[rm]   Moved: " + stats.copied);
    } else {
        Console.noteln("[rm]   Copied: " + stats.copied);
    }
    Console.noteln("[rm]   Skipped: " + stats.skipped);
    Console.noteln("[rm]   Errors: " + stats.errors);
    Console.noteln("=".repeat(80));

    if (dryRun){
        Console.noteln("[rm] DRY RUN MODE - No files were actually " + (moveFiles ? "moved" : "copied"));
    } else if (moveFiles){
        Console.noteln("[rm] MOVE MODE - Original files were deleted after copying");
    }
}

// ============================================================================
// Execute
// ============================================================================

function main(){
    Console.show();

    // Show dialog
    var dialog = new ReorganizeMastersDialog();
    var result = dialog.execute();

    if (result != StdDialogCode_Ok){
        Console.noteln("[rm] Cancelled by user");
        return;
    }

    // Run reorganization with user-provided parameters
    try {
        RM_reorganizeMasters(
            dialog.sourcePath,
            dialog.targetPath,
            dialog.dryRun,
            dialog.recursive,
            dialog.moveFiles
        );
    } catch(e){
        Console.criticalln("[rm] FATAL ERROR: " + e);
    }
}

main();
