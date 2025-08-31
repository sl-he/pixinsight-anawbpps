/* modules/masters_parse.jsh
   Master metadata parser: prefer FITS headers, fallback to filename tokens.
   No disk I/O. Pure string parsing and normalization.

   API:
     MP_parseMaster(path, hdrObj) -> {
       type,            // "BIAS"|"DARK"|"FLAT"|"LIGHT"|null
       TELESCOP, INSTRUME, setup, // setup = TELESCOP + "_" + INSTRUME (if both exist)
       FILTER,          // normalized: L,R,G,B,Ha,OIII,SII (or raw if unknown)
       XBINNING, YBINNING, BINNING, // numbers + "XxY"
       GAIN, OFFSET, USBLIMIT,      // numbers
       READOUTM,        // string
       SET_TEMP, CCD_TEMP,          // numbers (°C)
       EXPOSURE,        // seconds (number), if known
       DATE_OBS_ISO,    // ISO string "YYYY-MM-DDThh:mm:ss[.sss]Z" or "YYYY-MM-DD"
       FOCALLEN,        // mm (number) if present
       OBJECT,          // target name if present
       sources,         // map: field -> "header"|"filename"|"derived"
       warnings         // array of strings
     }
*/

// ------------------------------ helpers ------------------------------
function MP_join(){
  var a=[]; for (var i=0;i<arguments.length;++i) if (arguments[i]) a.push(arguments[i]);
  var p=a.join('/'); return p.replace(/\\/g,'/').replace(/\/+/g,'/');
}
function MP_numOrNull(v){
  if (v===null || v===undefined || v==="") return null;
  var n = Number(v);
  return isFinite(n) ? n : null;
}
function MP_trimQuotes(s){
  if (typeof s !== 'string') return s;
  return s.replace(/^['"\s]+|['"\s]+$/g,'');
}
function MP_header(hdr, key){
  if (!hdr) return null;
  // accept exact or case-insensitive
  if (hdr.hasOwnProperty(key)) return hdr[key];
  var u = String(key).toUpperCase();
  for (var k in hdr) if (hdr.hasOwnProperty(k) && String(k).toUpperCase()===u) return hdr[k];
  return null;
}
function MP_pick(dst, key, srcVal, srcName, transform){
  if (srcVal===null || srcVal===undefined || srcVal==="") return false;
  var v = transform ? transform(srcVal) : srcVal;
  if (v===null || v===undefined || v==="") return false;
  dst[key] = v;
  dst.sources[key] = srcName;
  return true;
}

// ------------------------- filename token parsing -------------------------
function MP_tokensFromName(path){
  var name = String(path);
  // strip dirs
  var i = name.lastIndexOf('/'); var j = name.lastIndexOf('\\');
  var cut = Math.max(i,j);
  if (cut>=0) name = name.substring(cut+1);
  // drop extension
  var dot = name.lastIndexOf('.');
  if (dot>0) name = name.substring(0,dot);
  // split by underscores, keep spaces as-is
  return name.split('_');
}

// e.g. "MasterBias"|"MasterDark"|"MasterF"|"MasterFlat"
function MP_typeFromNameTokens(toks){
  for (var i=0;i<toks.length;++i){
    var t = toks[i].toLowerCase();
    if (t.indexOf('masterbias')===0 || t==='bias') return "BIAS";
    if (t.indexOf('masterdark')===0 || t==='dark') return "DARK";
    if (t.indexOf('masterflat')===0 || t==='flat' || t==='masterf') return "FLAT";
    if (t==='light' || t==='lights') return "LIGHT";
  }
  return null;
}

function MP_filterNormalize(s){
  if (!s) return null;
  var x = s.toUpperCase().replace(/\s+/g,'');
  if (x==='L') return 'L';
  if (x==='R') return 'R';
  if (x==='G') return 'G';
  if (x==='B') return 'B';
  if (x==='HA' || x==='H-ALPHA' || x==='Hα' || x==='HII') return 'Ha';
  if (x==='OIII' || x==='O3' || x==='O-III') return 'OIII';
  if (x==='SII'  || x==='S2' || x==='S-II')  return 'SII';
  return s; // leave as-is if unknown (user may have other filters)
}

function MP_parseDateFromName(toks){
  // Accept YYYY_MM_DD or YYYY-MM-DD
  var re1 = /^(\d{4})[_-](\d{2})[_-](\d{2})$/;
  for (var i=0;i<toks.length;++i){
    var m = re1.exec(toks[i]);
    if (m){
      return m[1] + "-" + m[2] + "-" + m[3];
    }
  }
  return null;
}

function MP_parseBinningFromName(toks){
  // "Bin1x1" or "1x1"
  var reA = /^Bin(\d+)x(\d+)$/i;
  var reB = /^(\d+)x(\d+)$/;
  for (var i=0;i<toks.length;++i){
    var t = toks[i];
    var mA = reA.exec(t); if (mA) return { X: Number(mA[1]), Y: Number(mA[2]) };
    var mB = reB.exec(t); if (mB) return { X: Number(mB[1]), Y: Number(mB[2]) };
  }
  return null;
}
function MP_parseGainOffsetUSB(toks){
  var out={};
  for (var i=0;i<toks.length;++i){
    var t = toks[i];
    var mG = /^G(\d+)$/i.exec(t);    if (mG) out.gain = Number(mG[1]);
    var mO = /^OS?(\d+)$/i.exec(t);  if (mO) out.offset = Number(mO[1]); // allow "OS40" or "O40"
    var mU = /^U(\d+)$/i.exec(t);    if (mU) out.usb = Number(mU[1]);
  }
  return out;
}
function MP_parseExposureFromName(toks){
  // "015s" => 15; "3s"=>"3"
  for (var i=0;i<toks.length;++i){
    var m = /^(\d{1,4})s$/i.exec(toks[i]);
    if (m) return Number(m[1]);
  }
  return null;
}
function MP_parseTempFromName(toks){
  // "..._-10C", "__0C", "5C"
  for (var i=0;i<toks.length;++i){
    var m = /^-?\d+C$/i.exec(toks[i]);
    if (m){
      var n = Number(toks[i].slice(0,-1));
      return n;
    }
  }
  return null;
}
function MP_guessTeleAndCameraFromName(toks){
  // We expect pattern: [TELESCOP] _ MasterXxx _ [optional date] _ [INSTRUME] _ ...
  // TELESCOP may itself contain underscores, so safer approach:
  // find "Master" token index, then camera likely appears in next 1..3 tokens (uppercase model).
  var idxMaster=-1;
  for (var i=0;i<toks.length;++i){
    if (/^Master/i.test(toks[i]) || /^Bias$|^Dark$|^Flat$|^Light$/i.test(toks[i])){
      idxMaster = i; break;
    }
  }
  var tele = null, cam = null;

  if (idxMaster > 0){
    // TELESCOP = join tokens 0..idxMaster-1 with '_'
    tele = toks.slice(0, idxMaster).join('_');
    // Scan forward for camera-like token (e.g., QHY600M, ASI2600MM, etc.)
    for (var k=idxMaster+1; k<Math.min(toks.length, idxMaster+5); ++k){
      if (/^(QHY|ASI|ZWO|CCD|CMOS|FLI|SBIG|ATIK|QSI)[A-Za-z0-9\-]+$/i.test(toks[k])){
        cam = toks[k];
        break;
      }
      // fallback: an all-caps alnum token ending with M/MM
      if (/^[A-Z0-9\-]+M{1,2}$/.test(toks[k])){
        cam = toks[k];
        break;
      }
    }
  }
  return { tele: tele, cam: cam };
}
function MP_extractReadoutFromName(toks){
  // heuristics: any token with spaces that isn't date/bin/gain/etc and sits near Gxx
  // Simpler: take the longest token between camera and Gxx.
  var gIdx=-1, camIdx=-1;
  for (var i=0;i<toks.length;++i){
    if (/^G\d+$/i.test(toks[i])) gIdx=i;
    if (/^(QHY|ASI|ZWO|FLI|SBIG|ATIK|QSI)/i.test(toks[i]) || /^[A-Z0-9\-]+M{1,2}$/.test(toks[i])) camIdx=i;
  }
  if (camIdx>=0 && gIdx>camIdx){
    var cand = toks.slice(camIdx+1, gIdx);
    if (cand.length){
      // join back with spaces (original had spaces)
      return cand.join(' ').replace(/\s+/g,' ').trim();
    }
  }
  // Another case (flats): after filter token, before Gxx
  var filtIdx=-1;
  for (var j=0;j<toks.length;++j){
    if (/^(L|R|G|B|Ha|HA|OIII|O3|SII|S2)$/i.test(toks[j])) { filtIdx=j; break; }
  }
  if (filtIdx>=0 && gIdx>filtIdx){
    var cand2 = toks.slice(filtIdx+1, gIdx);
    if (cand2.length) return cand2.join(' ').replace(/\s+/g,' ').trim();
  }
  return null;
}

// ----------------------- main merge routine -----------------------
function MP_parseMaster(path, hdr){
  var out = {
    type: null,
    TELESCOP: null, INSTRUME: null, setup: null,
    FILTER: null,
    XBINNING: null, YBINNING: null, BINNING: null,
    GAIN: null, OFFSET: null, USBLIMIT: null,
    READOUTM: null,
    SET_TEMP: null, CCD_TEMP: null,
    EXPOSURE: null,
    DATE_OBS_ISO: null,
    FOCALLEN: null,
    OBJECT: null,
    sources: {},
    warnings: []
  };

  var toks = MP_tokensFromName(path);

  // --- TYPE ---
  var typeFromHdr = MP_header(hdr, 'IMAGETYP');
  if (typeFromHdr) typeFromHdr = MP_trimQuotes(String(typeFromHdr)).toUpperCase();
  if (typeFromHdr === 'BIAS' || typeFromHdr === 'DARK' || typeFromHdr === 'FLAT' || typeFromHdr === 'LIGHT')
    MP_pick(out, 'type', typeFromHdr, 'header');
  else {
    var tn = MP_typeFromNameTokens(toks);
    if (tn) MP_pick(out, 'type', tn, 'filename');
  }

  // --- TELESCOP / INSTRUME ---
  MP_pick(out, 'TELESCOP', MP_trimQuotes(MP_header(hdr,'TELESCOP')), 'header');
  MP_pick(out, 'INSTRUME', MP_trimQuotes(MP_header(hdr,'INSTRUME')), 'header');

  if (!out.TELESCOP || !out.INSTRUME){
    var guess = MP_guessTeleAndCameraFromName(toks);
    if (!out.TELESCOP && guess.tele){
      MP_pick(out, 'TELESCOP', guess.tele, 'filename');
      out.warnings.push("TELESCOP from filename");
    }
    if (!out.INSTRUME && guess.cam){
      MP_pick(out, 'INSTRUME', guess.cam, 'filename');
      out.warnings.push("INSTRUME from filename");
    }
  }
  if (out.TELESCOP && out.INSTRUME){
    out.setup = out.TELESCOP + "_" + out.INSTRUME;
    out.sources.setup = (out.sources.TELESCOP==='header' && out.sources.INSTRUME==='header') ? 'derived' : 'derived';
  }

  // --- FILTER ---
  if (MP_pick(out, 'FILTER', MP_trimQuotes(MP_header(hdr,'FILTER')), 'header', MP_filterNormalize)) {
    // ok
  } else {
    // single token filter in name (e.g., "_L_")
    for (var i=0;i<toks.length;++i){
      var f = MP_filterNormalize(toks[i]);
      if (f && /^(L|R|G|B|Ha|OIII|SII)$/i.test(f)){
        MP_pick(out, 'FILTER', f, 'filename'); break;
      }
    }
  }

  // --- BINNING ---
  var xb = MP_numOrNull(MP_header(hdr,'XBINNING'));
  var yb = MP_numOrNull(MP_header(hdr,'YBINNING'));
  if (xb) { MP_pick(out, 'XBINNING', xb, 'header'); }
  if (yb) { MP_pick(out, 'YBINNING', yb, 'header'); }
  if ((!out.XBINNING || !out.YBINNING)){
    var b = MP_parseBinningFromName(toks);
    if (b){
      if (!out.XBINNING) { MP_pick(out, 'XBINNING', b.X, 'filename'); out.warnings.push("XBINNING from filename"); }
      if (!out.YBINNING) { MP_pick(out, 'YBINNING', b.Y, 'filename'); out.warnings.push("YBINNING from filename"); }
    }
  }
  if (out.XBINNING && out.YBINNING) out.BINNING = out.XBINNING + "x" + out.YBINNING;

  // --- GAIN / OFFSET / USB ---
  if (!MP_pick(out, 'GAIN',   MP_numOrNull(MP_header(hdr,'GAIN')), 'header')){
    var go = MP_parseGainOffsetUSB(toks);
    if (go.gain!=null){ out.GAIN = go.gain; out.sources.GAIN='filename'; out.warnings.push("GAIN from filename"); }
    if (go.offset!=null){ out.OFFSET = go.offset; out.sources.OFFSET='filename'; out.warnings.push("OFFSET from filename"); }
    if (go.usb!=null){ out.USBLIMIT = go.usb; out.sources.USBLIMIT='filename'; out.warnings.push("USBLIMIT from filename"); }
  } else {
    // still try to fill missing OFFSET/USB from filename if header lacks them
    var go2 = MP_parseGainOffsetUSB(toks);
    if (out.OFFSET==null && go2.offset!=null){ out.OFFSET = go2.offset; out.sources.OFFSET='filename'; out.warnings.push("OFFSET from filename"); }
    if (out.USBLIMIT==null && go2.usb!=null){ out.USBLIMIT = go2.usb; out.sources.USBLIMIT='filename'; out.warnings.push("USBLIMIT from filename"); }
  }

  // --- READOUT MODE ---
  if (!MP_pick(out, 'READOUTM', MP_trimQuotes(MP_header(hdr,'READOUTM')), 'header')){
    var ro = MP_extractReadoutFromName(toks);
    if (ro){ out.READOUTM = ro; out.sources.READOUTM='filename'; out.warnings.push("READOUTM from filename"); }
  }

  // --- TEMPERATURES ---
  if (!MP_pick(out, 'SET_TEMP', MP_numOrNull(MP_header(hdr,'SET-TEMP')), 'header')){
    var tset = MP_parseTempFromName(toks);
    if (tset!=null){ out.SET_TEMP = tset; out.sources.SET_TEMP='filename'; out.warnings.push("SET-TEMP from filename"); }
  }
  MP_pick(out, 'CCD_TEMP', MP_numOrNull(MP_header(hdr,'CCD-TEMP')), 'header');

  // --- EXPOSURE ---
  var exp = MP_numOrNull(MP_header(hdr,'EXPOSURE'));
  if (exp===null) exp = MP_numOrNull(MP_header(hdr,'EXPTIME'));
  if (!MP_pick(out, 'EXPOSURE', exp, 'header')){
    var e2 = MP_parseExposureFromName(toks);
    if (e2!=null){ out.EXPOSURE = e2; out.sources.EXPOSURE='filename'; out.warnings.push("EXPOSURE from filename"); }
  }

  // --- DATE-OBS ---
  var dHdr = MP_trimQuotes(MP_header(hdr,'DATE-OBS'));
  if (dHdr){
    // header usually ISO: "YYYY-MM-DDThh:mm:ss[.sss]"
    var iso = String(dHdr).replace(/ /g,'T');
    MP_pick(out, 'DATE_OBS_ISO', iso, 'header');
  } else {
    var dName = MP_parseDateFromName(toks);
    if (dName){ out.DATE_OBS_ISO = dName; out.sources.DATE_OBS_ISO='filename'; out.warnings.push("DATE-OBS from filename"); }
  }

  // --- FOCALLEN / OBJECT (optional) ---
  MP_pick(out, 'FOCALLEN', MP_numOrNull(MP_header(hdr,'FOCALLEN')), 'header');
  MP_pick(out, 'OBJECT', MP_trimQuotes(MP_header(hdr,'OBJECT')), 'header');

  return out;
}
