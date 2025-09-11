/*
  modules/preprocessing_progress.jsh
  WBPP-like Progress UI + runner for preprocessing operations.

  - Универсальное окно с таблицей (Operation, Group, Elapsed, Status, Note).
  - Запускает операции сразу по появлению окна.
  - Поддерживает Cancel (между кадрами/группами).
  - Сейчас реализован шаг Calibration (через ImageCalibration) и CosmeticCorrection,
    но легко расширить другими: CosmeticCorrection, StarAlignment и т.п.
*/

/// ------- small helpers (без регекспов) -------
function CP__fmtHMS(sec){
    var t = Math.max(0, Math.floor(sec));
    var hh = Math.floor(t/3600), mm = Math.floor((t%3600)/60), ss = t%60;
    var pad = function(n){ return (n<10?"0":"")+n; };
    return pad(hh)+":"+pad(mm)+":"+pad(ss);
}
// Отформатировать ключ группы косметики: DARK::<passport>::<full/path>
// Вернуть «паспорт» из ключа DARK::<passport>::<full/path>
function CP__fmtCosmeticGroupLabel(key){
    var s = String(key||"");
    var first=-1, second=-1, i;
    for (i=0; i<s.length-1; i++){
        if (s.charAt(i)===":" && s.charAt(i+1)===":"){ first=i; break; }
    }
    if (first>=0){
        for (i=first+2; i<s.length-1; i++){
            if (s.charAt(i)===":" && s.charAt(i+1)===":"){ second=i; break; }
        }
        if (second>=0) return s.substring(first+2, second);
    }
    return s;
}
// Уникальная вставка строки (если уже есть — вернуть существующую)
function PP_addRowUnique(dlg, op, label, opKey){
    if (!dlg) return null;
    if (!dlg.rowIndex) dlg.rowIndex = {};
    if (dlg.rowIndex[opKey]) return dlg.rowIndex[opKey];
    var node = dlg.addRow(op, label);
    dlg.rowIndex[opKey] = node;
    return node;
}
function CP__updateTotal(dlg){
    try{
        var totalSec = (Date.now() - dlg.startEpoch)/1000;
        dlg.totalTimeLabel.text = "Total: " + CP__fmtHMS(totalSec);
    }catch(_){}
}


function CP__norm(p){
    var s = String(p||"");
    while (s.indexOf("\\") >= 0){ var i = s.indexOf("\\"); s = s.substring(0,i) + "/" + s.substring(i+1); }
    var changed = true;
    while (changed){
        changed = false;
        for (var j=0; j < s.length-1; j++){
            if (s.charAt(j)==="/" && s.charAt(j+1)==="/"){ s = s.substring(0,j) + "/" + s.substring(j+2); changed = true; break; }
        }
    }
    if (s.length>2 && s.charAt(1)===":" && s.charAt(2)=="/"){
        var c=s.charAt(0); if (c>="a" && c<="z") s=c.toUpperCase()+s.substring(1);
    }
    return s;
}
function CP__groupLights(g){
    if (!g) return [];
    if (g.lights && g.lights.length) return g.lights.slice(0);
    if (g.items && g.items.length){
        var a=[], i; for (i=0;i<g.items.length;i++){ var it=g.items[i]; if (typeof it==="string") a.push(it); else if (it && it.path) a.push(it.path); }
        return a;
    }
    if (g.frames && g.frames.length) return g.frames.slice(0);
    return [];
}
function CP__splitByPipe(s){
    var parts=[], cur=""; for (var i=0;i<s.length;i++){ var ch=s.charAt(i); if (ch==="|"){ parts.push(cur); cur=""; } else cur+=ch; }
    parts.push(cur); return parts;
}
function CP__extractMastersFromKey(gkey){
    var res={bias:"",dark:"",flat:""}; if (!gkey) return res;
    var parts = CP__splitByPipe(gkey); if (parts.length>=3){
        res.bias = parts[parts.length-3];
        res.dark = parts[parts.length-2];
        res.flat = parts[parts.length-1];
    }
    return res;
}
function CP__fmtGroupForUI(gkey){
    // пример: CYPRUS_FSQ106_F3_QHY600M|Sivan 2|B|High Gain Mode 16BIT|56|40|50|1x1|0|30|<bias>|<dark>|<flat>
    // хотим:  CYPRUS_FSQ106_F3_QHY600M|Sivan 2|B|High Gain Mode 16BIT|G56|OS40|U50|bin1x1|0C|30s
    var p = CP__splitByPipe(gkey);
    if (p.length < 10) return gkey; // fallback
    var cam = p[0], target = p[1], filt = p[2], mode = p[3];
    var g = p[4], os = p[5], u = p[6], bin = p[7], temp = p[8], exp = p[9];
    // нормализации
    var G  = "G"+g;
    var OS = "OS"+os;
    var U  = "U"+u;
    var BIN = (bin.indexOf("x")>=0?"bin":"") + bin;
    var TEMP = (String(temp).indexOf("C")>=0? temp : (temp+"C"));
    var EXP  = (String(exp).indexOf("s")>=0? exp : (exp+"s"));
    return cam + "|" + target + "|" + filt + "|" + mode + "|" + G + "|" + OS + "|" + U + "|" + BIN + "|" + TEMP + "|" + EXP;
}

/// ------- Progress Dialog -------
function CalibrationProgressDialog(){
    this.__base__ = Dialog;
    this.__base__();

    this.cancelled = false;
    this.groupRows = []; // [{node, counters, startedAt, total, key}...]

    // Title
    this.windowTitle = "ANAWBPPS — Progress";

    // Tree
    this.rowIndex = {};   // индекс уже добавленных строк
    this.tree = new TreeBox(this);
    this.tree.alternatingRowColors = true;
    this.tree.multipleSelection = false;
    this.tree.headerVisible = true;
    this.tree.rootDecoration = false;
    this.tree.minHeight = 1500;
    this.tree.minWidth = 1950;
    this.tree.numberOfColumns = 5;
    this.tree.setHeaderText(0, "Operation");
    this.tree.setHeaderText(1, "Group involved");
    this.tree.setHeaderText(2, "Elapsed");
    this.tree.setHeaderText(3, "Status");
    this.tree.setHeaderText(4, "Note");
    this.tree.setColumnWidth(0, 200);
    this.tree.setColumnWidth(1, 1200);
    this.tree.setColumnWidth(2, 130);
    this.tree.setColumnWidth(3, 150);
    // отметка старта/финиша строки
    this.markRowStarted = function(node){
        try { node._startedAt = Date.now(); node._finished = false; } catch(_){}
        // при старте сбросим elapsed в 00:00:00
        try { node.setText(2, "00:00:00"); } catch(_){}
    };
    this.markRowFinished = function(node, elapsedSec){
        try { node._finished = true; node._startedAt = 0; } catch(_){}
        // финальное значение уже выставляет раннер — здесь лишь флаг
    };

    // Тикер: обновляет Elapsed только у незавершённых строк
    var self = this;
    this._timer = new Timer;
    this._timer.interval = 1000;
    this._timer.onTimeout = function(){
        try {
            if (!self.tree) return;
            var n = self.tree.child(0);
            while (n){
                // обновляем только «живые» строки
                if (n._startedAt && !n._finished){
                    var sec = (Date.now() - n._startedAt)/1000;
                    n.setText(2, CP__fmtHMS(sec));
                }
                n = n.nextSibling();
            }
        } catch(_){}
    };
    this._timer.enabled = true;
    // Total time + Cancel
/*    this.totalTimeLabel = new Label(this);
    this.totalTimeLabel.text = "Total: 00:00:00";
    // Старые сборки PI могут не знать TextAlign_* — ставим выравнивание, если доступно
    try { this.totalTimeLabel.textAlignment = TextAlign_Left|TextAlign_VertCenter; } catch(_){}
*/
    this.totalTimeLabel = new Label(this);
    this.totalTimeLabel.text = "Total: 00:00:00";
    // безопасное выставление выравнивания
    var _TA_L = (typeof TextAlign_Left       !== "undefined") ? TextAlign_Left       : 0;
    var _TA_V = (typeof TextAlign_VertCenter !== "undefined") ? TextAlign_VertCenter : 0;
    try { this.totalTimeLabel.textAlignment = _TA_L | _TA_V; } catch(_) {}

    this.cancelButton = new PushButton(this);
    this.cancelButton.text = "Cancel";
    var self = this;
    this.cancelButton.onClick = function(){ self.cancelled = true; self.setGlobalStatus("Cancelling…"); };
    // Перевести кнопку Cancel в режим DONE (окно остаётся открытым до клика)
    this.setDone = function(){
        try {
            this.cancelled = false; // на всякий случай
            this.cancelButton.text = "DONE";
            var self = this;
            this.cancelButton.onClick = function(){
                // закрываем окно по нажатию DONE
                try { self.ok(); } catch(_){ try { self.cancel(); } catch(__){} }
            };
        } catch(_){}
    };
    var buttonsSizer = new HorizontalSizer;
    buttonsSizer.spacing = 8;
    buttonsSizer.add(this.totalTimeLabel, 100);
    buttonsSizer.add(this.cancelButton);

    // Layout
    this.sizer = new VerticalSizer;
    this.sizer.margin = 8;
    this.sizer.spacing = 8;
    this.sizer.add(this.tree, 100);
    this.sizer.add(buttonsSizer);

    // Timer to refresh total counter and any running row (между файлами)
    this.startEpoch = 0;
    this.tick = new Timer;
    this.tick.interval = 1000;
    this.tick.periodic = true;
    this.tick.onTimeout = function(){
        if (!self.startEpoch) return;
        var t = (Date.now() - self.startEpoch) / 1000;
        var hh = Math.floor(t/3600); var mm = Math.floor((t%3600)/60); var ss = Math.floor(t%60);
        var pad = function(n){ return (n<10?"0":"")+n; };
        self.totalTimeLabel.text = "Total: " + pad(hh)+":"+pad(mm)+":"+pad(ss);

        // update any running rows elapsed (после каждого файла будет живо)
        for (var i=0;i<self.groupRows.length;i++){
            var gr = self.groupRows[i];
            if (gr.startedAt && gr.node){ // only when running
                var tt = (Date.now() - gr.startedAt)/1000;
                var h = Math.floor(tt/3600); var m=Math.floor((tt%3600)/60); var s=Math.floor(tt%60);
                gr.node.setText(2, pad(h)+":"+pad(m)+":"+pad(s));
            }
        }
    };

    this.addRow = function(opName, groupUiText){
        var n = new TreeBoxNode(this.tree);
        // колонка Operation
        n.setText(0, String(opName||""));
        // иконки — по возможности
        if (opName === "ImageCalibration"){
            try { n.icon = this.iconCal; } catch(_){}
        } else if (opName === "CosmeticCorrection"){
            try { n.icon = this.iconCos; } catch(_){}
        }
        // колонка Group involved
        n.setText(1, String(groupUiText||""));
        // колонка Elapsed
        n.setText(2, "00:00:00");
        // колонка Status
        n.setText(3, "⏳ Queued");
        // колонка Note
        n.setText(4, "");
        return n;
    };

    this.setRowStatus = function(node, statusText){ node.setText(3, statusText); };
    this.setRowNote   = function(node, noteText){ node.setText(4, noteText); };
    this.setGlobalStatus = function(text){
        // (опционально можно вынести общий статус; сейчас обновляем только кнопку)
        this.cancelButton.text = text.indexOf("Cancel")>=0 ? text : ("Cancel — " + text);
    };
}
CalibrationProgressDialog.prototype = new Dialog;

/// ------- Публичная точка входа -------
function CAL_runCalibration_UI(plan, workFolders, dlg /* optional external dialog */){
    if (!plan || !plan.groups){
        Console.warningln("[cal-ui] No plan.groups — nothing to run.");
        return;
    }
    var gkeys = []; for (var k in plan.groups) if (plan.groups.hasOwnProperty(k)) gkeys.push(k);

    var outDirBase = (workFolders && workFolders.calibrated) ? CP__norm(workFolders.calibrated) : "";
    try{ if (outDirBase && !File.directoryExists(outDirBase)) File.createDirectory(outDirBase, true); }catch(_){}

    // Build or reuse UI
    var ownDlg = false;
    if (!dlg) { dlg = new CalibrationProgressDialog; ownDlg = true; }
    if (!dlg.startEpoch) dlg.startEpoch = Date.now();
    try{ dlg.totalTimeLabel.text = "Total: 00:00:00"; }catch(_){}
    try{ dlg.tick.start(); }catch(_){}
    if (ownDlg){ dlg.show(); processEvents(); }

    // rows
    var rows = [];
    for (var gi=0; gi<gkeys.length; gi++){
        var gkey = gkeys[gi];
        // считаем количество файлов в группе (lights)
        var g = plan.groups[gkey];
        var nSubs = 0;
        if (g && g.lights && g.lights.length) nSubs = g.lights.length;
        else if (g && g.items && g.items.length) nSubs = g.items.length;
        else if (g && g.frames && g.frames.length) nSubs = g.frames.length;

        var label = CP__fmtGroupForUI(gkey) + " (" + nSubs + " subs)";
        var n = dlg.addRow("ImageCalibration", label);
        rows.push({ node:n, key:gkey, counters:{ok:0, err:0, skip:0}, total:nSubs, startedAt:0 });
        dlg.groupRows.push(rows[rows.length-1]);
    }

    /* IC rows are now present — flush deferred CC rows so they appear AFTER IC */
    try{
        if (dlg && typeof dlg.flushDeferredCC === "function" && !dlg.__ccPreAdded){
            dlg.flushDeferredCC();
        }
    }catch(_){}


    // Show modeless and start running
    dlg.show();
    processEvents();

    // Runner (по группам; каждый запуск — вся группа целиком)
    for (var gi=0; gi<gkeys.length; gi++){
        var entry = rows[gi];
        var gkey = entry.key;
        var g = plan.groups[gkey];

        var lights = CP__groupLights(g);
        entry.total = lights.length;

        // resolve masters
        var biasP = ""; try{ if (g.masterBias) biasP = CP__norm(g.masterBias); }catch(_){}
        var darkP = ""; try{ if (g.masterDark) darkP = CP__norm(g.masterDark); }catch(_){}
        var flatP = ""; try{ if (g.masterFlat) flatP = CP__norm(g.masterFlat); }catch(_){}
        if (!biasP || !darkP || !flatP){
            var m = CP__extractMastersFromKey(gkey);
            if (!biasP && m.bias) biasP = CP__norm(m.bias);
            if (!darkP && m.dark) darkP = CP__norm(m.dark);
            if (!flatP && m.flat) flatP = CP__norm(m.flat);
        }

        // update UI: start group
        entry.startedAt = Date.now();
        dlg.setRowStatus(entry.node, "▶ Running");
        dlg.setRowNote(entry.node, "0/"+entry.total+" processing");
        if (dlg && typeof dlg.markRowStarted === "function")
            dlg.markRowStarted(entry.node);
        dlg.setRowNote(entry.node, "0/"+entry.total+" processing");
        entry.node.setText(2, "00:00:00");   // elapsed по группе
           if (dlg && typeof dlg.markRowStarted === "function")
               dlg.markRowStarted(entry.node);
        processEvents();

        if (typeof ImageCalibration !== "function"){
            // dry-run
            for (var i=0;i<lights.length;i++){
                if (dlg.cancelled){ entry.counters.skip += (entry.total - i); break; }
                entry.counters.ok++;
                dlg.setRowNote(entry.node, (entry.counters.ok)+"/"+entry.total+" processed");
                processEvents();
            }
            dlg.setRowStatus(entry.node, dlg.cancelled ? "⨯ Cancelled" : "✔ Success");
            continue;
        }

        // Пакетная калибровка: формируем таблицу targetFrames со всеми файлами
        if (dlg.cancelled){
            entry.counters.skip = entry.total;
            dlg.setRowStatus(entry.node, "⨯ Cancelled");
            processEvents();
            break;
        }

        var rowsTF = [];
        for (var i=0; i<lights.length; i++){
            var L = CP__norm(lights[i]);
            if (L && L.length) rowsTF.push([true, L]); else entry.counters.skip++;
        }

        var IC = new ImageCalibration;
        try{ CAL_applyUserParams(IC); }catch(_){}
        try{ if (biasP){ IC.masterBiasPath = biasP; IC.masterBiasEnabled = true; } }catch(_){}
        try{ if (darkP){ IC.masterDarkPath = darkP; IC.masterDarkEnabled = true; } }catch(_){}
        try{ if (flatP){ IC.masterFlatPath = flatP; IC.masterFlatEnabled = true; } }catch(_){}
        try{ if (outDirBase) IC.outputDirectory = outDirBase; }catch(_){}
        try{ IC.targetFrames = rowsTF; }
        catch(eTF){
            entry.counters.err = entry.total;
            dlg.setRowStatus(entry.node, "✖ Error");
            dlg.setRowNote(entry.node, "targetFrames failed");
            processEvents();
            continue;
        }

        // Обновим примечание перед запуском
        dlg.setRowNote(entry.node, "0/"+entry.total+" queued");
        processEvents();

        /// ВАЖНО: executeGlobal() блокирует UI; поэтому таймер не тикает. Проставим elapsed вручную ПОСЛЕ.
        var ok = true;
        try{
            if (typeof IC.executeGlobal === "function") ok = !!IC.executeGlobal();
            else if (typeof IC.executeOn === "function") ok = !!IC.executeOn(null);
            else throw new Error("No execute* method available");
        }catch(runErr){ ok = false; }

        // После возврата: считаем все как processed (без детализации по файлам от процесса)
        if (ok){
            entry.counters.ok = entry.total - entry.counters.skip;
            dlg.setRowStatus(entry.node, "✔ Success");
        } else {
            entry.counters.err = entry.total - entry.counters.skip;
            dlg.setRowStatus(entry.node, "✖ Error");
        }
        // Elapsed по группе — после выполнения
        var gElapsedFinal = (Date.now() - entry.startedAt)/1000;
        entry.node.setText(2, CP__fmtHMS(gElapsedFinal));


        /* заморозить elapsed */
        entry.startedAt = 0;
        try{ entry.node._startedAt = 0; }catch(_){}
        try{ entry.node.__frozen = true; }catch(_){}
        if (dlg && typeof dlg.markRowFinished === "function")
            dlg.markRowFinished(entry.node, gElapsedFinal);
//        processEvents();
        // Note
        dlg.setRowNote(entry.node,
            (entry.counters.ok)+"/"+entry.total+" processed" +
            (entry.counters.skip>0? ("; skip="+entry.counters.skip) : "") +
            (entry.counters.err>0? ("; err="+entry.counters.err) : "")
        );
        CP__updateTotal(dlg);
        processEvents();

        // finalize row
        if (dlg.cancelled){
            dlg.setRowStatus(entry.node, "⨯ Cancelled");
        } else if (entry.counters.err>0){
            dlg.setRowStatus(entry.node, "✖ Error");
        } else {
            dlg.setRowStatus(entry.node, "✔ Success");
        }
        processEvents();

        if (dlg.cancelled) break;
    }

    try{ dlg.tick.stop(); }catch(_){}
    if (!ownDlg){
        // Внешний диалог — не блокируем поток, управление остаётся у вызывающей стороны
        return;
    }
    if (dlg.cancelled){
        try{ dlg.cancel(); }catch(_){ try{ dlg.ok(); }catch(__){} }
        return;
    } else {
        // Оставляем окно открытым: меняем кнопку на DONE и ждём клика
        dlg.cancelButton.text = "DONE";
        dlg._done = false;
        dlg.cancelButton.onClick = function(){
            try { this.dialog._done = true; } catch(_) { dlg._done = true; }
            try { dlg.ok(); } catch(_) { try { dlg.cancel(); } catch(__) {} }
        };
        while (!dlg._done){
            processEvents();
            try { msleep(50); } catch(_) {}
        }
    }

}
