/*=============================================================================
  modules/masters_parse.jsh
  Exports: MP_parseMaster(path)
  Policy:
    - Prefer FITS/XISF headers.
    - If header field is missing, fall back to filename tokens.
    - If setup is still missing, the caller derives it from the path.
  Note:
    This version stubs header reading (to be implemented next step),
    so it currently relies on filename parsing while keeping the correct
    precedence structure for future header support.
=============================================================================*/

// --- tiny utils ------------------------------------------------------------

function MP_norm(p){ return String(p||"").replace(/\\/g,'/'); }

function MP_ext(path){
  var s = String(path), i = s.lastIndexOf('.');
  return (i>=0) ? s.substring(i+1).toLowerCase() : "";
}

function MP_numOrNull(x){
  var n = Number(x);
  return isFinite(n) ? n : null;
}

// Normalize filter tokens
function MP_normFilter(f){
  if (!f) return null;
  var s = String(f).toUpperCase();
  if (s === "H-ALPHA" || s === "Hα" || s === "H_A" || s === "HA") return "HA";
  if (s === "O3" || s === "OIII") return "OIII";
  if (s === "S2" || s === "SII")  return "SII";
  if (s === "L" || s === "R" || s === "G" || s === "B") return s;
  return s; // keep as-is if custom filter (OK for flats)
}

// --- header reader (stub for now) ------------------------------------------
// When we implement it, fill fields from headers and return an object.
// For now it returns an empty object to enforce filename fallback.
function MP_tryReadHeaders(path){
  // TODO: implement with FileFormat / FileFormatInstance to read FITS/XISF
  // keywords without loading the image.
  return {};
}

// --- filename parser --------------------------------------------------------
function MP_parseByName(path){
  var name = File.extractNameAndExtension(path);
  // Remove accidental noise like exclamation marks in temperature tokens, etc.
  var clean = name.replace(/!+/g, "");
  var up    = clean.toUpperCase();

  // type
  var type = up.indexOf("MASTERBIAS")>=0 ? "BIAS" :
             up.indexOf("MASTERDARK")>=0 ? "DARK" :
             (up.indexOf("MASTERF")>=0 || up.indexOf("MASTERFLAT")>=0 ? "FLAT" : null);

  // setup: everything before first "_Master"
  var setup = null;
  var mSetup = clean.match(/^(.+?)_Master/i);
  if (mSetup) setup = mSetup[1];

  // instrument/camera and telescope from common patterns
  // e.g. CYPRUS_FSQ106_F3_MasterDark_2024_..._QHY600M_...
  var camera = null, telescope = null;
  var parts = clean.split("_");
  // Heuristic: first token(s) before "_Master" belong to telescope rig
  if (setup) telescope = setup;
  // Camera: look for tokens like QHY*, ASI*, etc.
  for (var i=0;i<parts.length;++i){
    if (/^(QHY|ASI|ZWO|FLI|SBIG|ATIK)/i.test(parts[i])){ camera = parts[i]; break; }
  }

  // readout: capture middle phrase like "High Gain Mode 16BIT"
  var readout = null;
  var mRead = clean.match(/(High Gain Mode\s*\d*BIT|Low Gain Mode\s*\d*BIT)/i);
  if (mRead) readout = mRead[1];

  // binning: Bin1x1 or 1x1
  var binning = null;
  var mBin = clean.match(/(?:BIN)?(\d+)x(\d+)/i);
  if (mBin) binning = (mBin[1] + "x" + mBin[2]);

  // gain / offset / usb
  var gain   = null;
  var offset = null;
  var mG = clean.match(/_G(\d+)/i);
  if (mG) gain = MP_numOrNull(mG[1]);
  var mO = clean.match(/_OS(\d+)/i);
  if (mO) offset = MP_numOrNull(mO[1]);
  // usb not needed in index, so ignore

  // temperature like _0C or _-20C
  var tempC = null;
  var mT = clean.match(/_(-?\d+)C/i);
  if (mT) tempC = MP_numOrNull(mT[1]);

  // exposure like _015s (3 digits seconds) — for DARKs only
  var exposureSec = null;
  var mExp = clean.match(/_(\d{3})s/i);
  if (mExp) exposureSec = MP_numOrNull(mExp[1]);

  // filter (only for flats): _L_, _R_, _G_, _B_, _HA_, _OIII_, _SII_
  var filter = null;
  var mF = clean.match(/_(L|R|G|B|HA|H\-ALPHA|OIII|O3|SII|S2)_/i);
  if (mF) filter = MP_normFilter(mF[1]);

  // date: accept YYYY_MM_DD or YYYY-MM-DD
  var date = null;
  var mD = clean.match(/(\d{4}[-_]\d{2}[-_]\d{2})/);
  if (mD) date = mD[1].replace(/_/g, "-"); // normalize to YYYY-MM-DD

  return {
    // common
    path: MP_norm(path),
    type: type,            // BIAS/DARK/FLAT or null
    setup: setup,          // may be null (caller may fill from path)
    telescope: telescope,  // from setup (heuristic)
    camera: camera,        // INSTRUME guess
    readout: readout || null,
    binning: binning || null,
    gain: gain,
    offset: offset,
    tempC: tempC,
    date: date,
    // dark-only
    exposureSec: exposureSec,
    // flat-only
    filter: filter
  };
}

// --- main exported function -------------------------------------------------
function MP_parseMaster(path){
  // ---- helpers ----
  function _ext(p){ var s=String(p); var i=s.lastIndexOf('.'); return i>=0 ? s.substring(i+1).toLowerCase() : ""; }
  function _name(p){ return File.extractNameAndExtension(p); }
  function _isFitsLike(p){ var e=_ext(p); return e==="fit"||e==="fits"||e==="xisf"; }
  function _isCamera(tok){
    return /^(QHY\d+\w*|ASI\d+\w*|QSI\d+\w*|SBIG\w*|FLI\w*|ATIK\w*|MORAVIAN\w*|ZWO\w*)$/i.test(tok);
  }

  if (!_isFitsLike(path))
    throw new Error("Not a FITS/XISF: " + path);

  var fname  = _name(path);                 // без расширения
  var tokens = fname.split(/[_\s]+/);       // делим по "_" и пробелам
  var upTok  = []; for (var i=0;i<tokens.length;++i) upTok.push(tokens[i].toUpperCase());

  // ---- пропускаем DarkFlats/FlatDarks полностью ----
  if (/\b(DARKFLAT|FLATDARK)\b/i.test(fname))
    throw new Error("DarkFlat/FlatDark skipped");

  // ---- найти позицию токена Master* ----
  var masterIdx = -1;
  for (var t=0; t<upTok.length; ++t){
    if (/^MASTER/.test(upTok[t])) { masterIdx = t; break; }
  }

  // ---- TYPE по токену Master* ----
  var type = "UNKNOWN";
  if (masterIdx >= 0){
    var mt = upTok[masterIdx];
    if (/^MASTERBIAS$/.test(mt)) type = "BIAS";
    else if (/^MASTERDARK$/.test(mt)) type = "DARK";
    else if (/^MASTERF(LAT)?$/.test(mt)) type = "FLAT";
    else if (mt === "MASTER" && masterIdx+1 < upTok.length){
      var nxt = upTok[masterIdx+1];
      if (nxt==="BIAS") type = "BIAS";
      else if (nxt==="DARK") type = "DARK";
      else if (nxt==="FLAT") type = "FLAT";
    }
  }

  // ---- TELESCOP из левой части имени до Master* ----
  var telescope = "";
  if (masterIdx > 0){
    telescope = tokens.slice(0, masterIdx).join("_");
  }

  // ---- INSTRUME (камера) как отдельный токен справа ----
  var camera = "";
  for (var j = masterIdx >= 0 ? masterIdx+1 : 0; j < tokens.length; ++j){
    if (_isCamera(tokens[j])){ camera = tokens[j]; break; }
  }

  // ---- SETUP: только TELESCOP + "_" + INSTRUME, иначе UNKNOWN_SETUP ----
  var setup = (telescope && camera) ? (telescope + "_" + camera) : "UNKNOWN_SETUP";

  // ---- filter (берём только справа от Master) ----
  var filter = null;
  for (var k = (masterIdx>=0? masterIdx+1 : 0); k < upTok.length; ++k){
    var tk = upTok[k];
    if (/^(L|R|G|B|HA|H-ALPHA|OIII|O3|SII|S2)$/.test(tk)){
      filter = (tk === "H-ALPHA") ? "HA" : (tk === "O3" ? "OIII" : (tk === "S2" ? "SII" : tk));
      break;
    }
  }

  // ---- binning ----
  var binning = "";
  for (var b = 0; b < tokens.length; ++b){
    var m = tokens[b].match(/^(?:Bin)?(\d+)x(\d+)$/i);
    if (m){ binning = m[1]+"x"+m[2]; break; }
  }

  // ---- gain / offset / usbLimit ----
  var gain = null, offset = null, usbLimit = null;
  for (var g = 0; g < tokens.length; ++g){
    var tg = tokens[g];

    var mG  = tg.match(/^G(\d+)$/i);      if (mG)  gain     = parseInt(mG[1],10);
    var mOS = tg.match(/^OS(\d+)$/i);     if (mOS) offset   = parseInt(mOS[1],10);

    // USB limit: поддерживаем U50 и USB50
    var mU  = tg.match(/^U(\d+)$/i);      if (mU)  usbLimit = parseInt(mU[1],10);
    var mUSB= tg.match(/^USB(\d+)$/i);    if (mUSB) usbLimit= parseInt(mUSB[1],10);
  }

  // ---- tempC: допускаем точку/расширение после 'C'
  var tempC = null;
  (function(){
    var m = fname.match(/(^|[^A-Za-z0-9])(-?\d{1,3})C(?=$|[^A-Za-z0-9])/i);
    if (m) tempC = parseInt(m[2],10);
    else {
      for (var h = 0; h < tokens.length; ++h){
        var mt = tokens[h].match(/^(-?\d{1,3})C$/i);
        if (mt){ tempC = parseInt(mt[1],10); break; }
      }
    }
  })();

  // ---- date: YYYY-MM-DD, YYYY_MM_DD, либо YYYY-MM / YYYY_MM
  var date = null;
  (function(){
    var m3 = fname.match(/(\d{4})[-_](\d{2})[-_](\d{2})/);
    if (m3){ date = m3[1]+"-"+m3[2]+"-"+m3[3]; return; }
    var m2 = fname.match(/(\d{4})[-_](\d{2})(?![-_]\d{2})/);
    if (m2){ date = m2[1]+"-"+m2[2]; return; }
  })();

  // ---- exposureSec: секунды (целые/дробные), исключаем 'us'
  var exposureSec = null;
  (function(){
    var m = fname.match(/(\d+(?:\.\d+)?)s(?![A-Za-z])/i);
    if (m){
      var v = parseFloat(m[1]);
      if (!isNaN(v)) exposureSec = v;
    }
  })();

  // ---- readout (напр. "High Gain Mode 16BIT") ----
  var readout = "";
  for (var r = 0; r < upTok.length; ++r){
    if (upTok[r] === "HIGH" && r+2 < upTok.length && upTok[r+1]==="GAIN" && upTok[r+2]==="MODE"){
      var s = [tokens[r], tokens[r+1], tokens[r+2]];
      if (r+3 < tokens.length && /BIT$/i.test(tokens[r+3])) s.push(tokens[r+3]);
      readout = s.join(" ");
      break;
    }
  }

  return {
    path: path,
    filename: fname,
    type: type,

    setup: (setup || "UNKNOWN_SETUP"),
    telescope: telescope || null,
    camera:    camera    || null,

    readout: readout || null,
    binning: binning || null,
    gain: gain,
    offset: offset,
    usbLimit: usbLimit,      // ← добавили
    tempC: tempC,
    date: date,                 // "YYYY-MM-DD" или "YYYY-MM"
    exposureSec: exposureSec,   // число либо null (для '1us' и т.п.)
    filter: filter
  };
}

//EOF
