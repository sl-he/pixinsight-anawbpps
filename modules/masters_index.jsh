/* modules/masters_index.jsh
   Masters reindex module (no FITS reading, no disk writes)

   API:
     MI_reindexMasters(mastersRoot) -> { root, generatedUTC, setups{setup:{biases[],darks[],flats[]}} }
     MI_summarizeMastersIndex(idx)  -> string
*/

// -----------------------------------------------------------------------------
// local utils (namespaced)
// -----------------------------------------------------------------------------
function MI_joinPath(){
  var a = [];
  for (var i = 0; i < arguments.length; ++i)
    if (arguments[i]) a.push(arguments[i]);
  var p = a.join('/');
  return p.replace(/\\/g,'/').replace(/\/+/g,'/');
}

function MI_fileExt(p){
  var s = String(p).toLowerCase();
  var i = s.lastIndexOf('.');
  return i >= 0 ? s.substring(i) : '';
}

function MI_relPath(root, p){
  var r = String(root).replace(/\\/g,'/').replace(/\/+/g,'/');
  var s = String(p).replace(/\\/g,'/').replace(/\/+/g,'/');
  if (s.indexOf(r) === 0) return s.substring(r.length).replace(/^\/+/,'');
  return s;
}

// -----------------------------------------------------------------------------
// directory enumeration with FileFind (PixInsight 1.9.x)
// -----------------------------------------------------------------------------
function MI_listDir(path){
  var out = [];
  var ff = new FileFind;
  var mask = MI_joinPath(path, '*');
  if (ff.begin(mask)){
    do{
      var name = ff.name || ff.fileName; // some builds expose .name
      if (name === '.' || name === '..') continue;
      var isDir = (ff.isDirectory !== undefined) ? ff.isDirectory : !!ff.isDir;
      var full  = MI_joinPath(path, name);
      out.push({ path: full, isDir: isDir });
    } while (ff.next());
    ff.end();
  }
  return out;
}

function MI_walkFiles(path, sink){
  var ff = new FileFind;
  var mask = MI_joinPath(path, '*');
  if (ff.begin(mask)){
    do{
      var name = ff.name || ff.fileName;
      if (name === '.' || name === '..') continue;
      var isDir = (ff.isDirectory !== undefined) ? ff.isDirectory : !!ff.isDir;
      var full  = MI_joinPath(path, name);
      if (isDir) MI_walkFiles(full, sink);
      else sink.push(full);
    } while (ff.next());
    ff.end();
  }
}

// -----------------------------------------------------------------------------
// main indexing
// -----------------------------------------------------------------------------
function MI_reindexMasters(mastersRoot){
  var root = mastersRoot;
  var biasesRoot = MI_joinPath(root, '!!!BIASES_LIB');
  var darksRoot  = MI_joinPath(root, '!!!DARKS_LIB');
  var flatsRoot  = MI_joinPath(root, '!!!FLATS_LIB');

  function existingSubdirs(base){
    if (!File.directoryExists(base)) return [];
    var entries = MI_listDir(base);
    var dirs = [];
    for (var i=0; i<entries.length; ++i)
      if (entries[i].isDir) dirs.push(entries[i].path);
    return dirs;
  }

  var setupDirs = {
    biases: existingSubdirs(biasesRoot),
    darks:  existingSubdirs(darksRoot),
    flats:  existingSubdirs(flatsRoot)
  };

  var out = {
    root: root,
    generatedUTC: (new Date()).toUTCString(),
    setups: {} // name -> { biases:[], darks:[], flats:[] }
  };

  function ensureSetup(name){
    if (!out.setups[name]) out.setups[name] = { biases:[], darks:[], flats:[] };
    return out.setups[name];
  }

  function indexOne(groupKey, dirList){
    for (var i=0; i<dirList.length; ++i){
      var setupPath = dirList[i];
      var s = setupPath.replace(/\\/g,'/').replace(/\/+/g,'/');
      var j = s.lastIndexOf('/');
      var setupName = (j>=0) ? s.substring(j+1) : s;

      var files = [];
      MI_walkFiles(setupPath, files);

      var good = [];
      for (var k=0; k<files.length; ++k){
        var e = MI_fileExt(files[k]);
        if (e === '.fits' || e === '.fit' || e === '.xisf')
          good.push(MI_relPath(root, files[k]));
      }

      var bucket = ensureSetup(setupName);
      bucket[groupKey] = bucket[groupKey].concat(good);
    }
  }

  indexOne('biases', setupDirs.biases);
  indexOne('darks',  setupDirs.darks);
  indexOne('flats',  setupDirs.flats);

  return out;
}

// -----------------------------------------------------------------------------
// summary
// -----------------------------------------------------------------------------
function MI_summarizeMastersIndex(idx){
  var setups = idx.setups;
  var names = [];
  for (var k in setups) if (setups.hasOwnProperty(k)) names.push(k);
  names.sort();

  var lines = [];
  lines.push('Masters root: ' + idx.root);
  lines.push('Generated: ' + idx.generatedUTC);
  lines.push('Setups found: ' + names.length);
  lines.push('');

  for (var i=0; i<names.length; ++i){
    var n = names[i];
    var s = setups[n];
    lines.push('• ' + n);
    lines.push('    biases: ' + s.biases.length);
    lines.push('    darks : ' + s.darks.length);
    lines.push('    flats : ' + s.flats.length);
  }
  return lines.join('\n');
}
